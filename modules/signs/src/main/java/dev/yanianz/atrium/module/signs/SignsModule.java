package dev.yanianz.atrium.module.signs;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.Commands;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Sign formatting with permissions plus {@code /signedit}, {@code /signcopy} and {@code /signpaste}.
 *
 * <p>Formatting is applied at HIGH in {@link SignChangeEvent}, reading the lines as lower-priority plugins
 * left them. Edits fire their own {@code SignChangeEvent} on the sign's region thread, so a protection plugin
 * can still cancel them, and every block access happens on that region thread.
 */
public final class SignsModule implements AtriumModule, Listener {
    static final String PERM_EDIT = "atrium.signedit";
    static final String PERM_COPY = "atrium.signcopy";
    static final String PERM_PASTE = "atrium.signpaste";
    static final String PERM_COLOR = "atrium.signs.color";
    static final String PERM_FORMAT = "atrium.signs.format";
    static final String PERM_WAXED = "atrium.signs.waxed";
    static final String PERM_DYNAMIC = "atrium.dsign";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<SignsConfig> config;
    private final Map<UUID, SignClipboard> clipboards = new ConcurrentHashMap<>();
    private DynamicSigns dynamicSigns;

    @Override
    public String id() {
        return "signs";
    }

    @Override
    public String description() {
        return "Sign colours/formatting with permissions, /signedit, /signcopy and /signpaste";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(SignsConfig.class, SignsConfig::new);
        ctx.listen(this);

        ctx.permission(PERM_EDIT, PermissionDefault.OP, "Edit the sign you are looking at");
        ctx.permission(PERM_COPY, PermissionDefault.TRUE, "Copy the sign you are looking at");
        ctx.permission(PERM_PASTE, PermissionDefault.OP, "Paste a copied sign");
        ctx.permission(PERM_COLOR, PermissionDefault.TRUE, "Use colour tags on signs");
        ctx.permission(PERM_FORMAT, PermissionDefault.TRUE, "Use decoration tags on signs");
        ctx.permission(PERM_WAXED, PermissionDefault.OP, "Edit or paste onto waxed signs");
        ctx.permission(PERM_DYNAMIC, PermissionDefault.OP, "Create and change dynamic scrolling signs");

        ctx.command(CommandSpec.of(Cmds.literal("signedit", PERM_EDIT)
                .executes(this::editUsage)
                .then(Commands.argument("line", IntegerArgumentType.integer(1, 4))
                        .executes(this::editUsage)
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(this::edit)))
                .build(), "Edit one line of the sign you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("signcopy", PERM_COPY)
                .executes(this::copy)
                .build(), "Copy the sign you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("signpaste", PERM_PASTE)
                .executes(this::paste)
                .build(), "Paste a copied sign onto the sign you are looking at"));

        Path dynamicFile = ctx.plugin().getDataPath().resolve("modules").resolve("signs")
                .resolve("dynamic-signs.yml");
        this.dynamicSigns = new DynamicSigns(ctx, config, dynamicFile);
        try {
            dynamicSigns.start();
        } catch (java.io.IOException e) {
            ctx.logger().warn("Dynamic signs could not be read; starting with none", e);
        }
        ctx.listen(dynamicSigns);
        registerDynamicCommands();
    }

    @Override
    public void reload() {
        if (dynamicSigns != null) {
            try {
                dynamicSigns.start();
            } catch (java.io.IOException e) {
                ctx.logger().warn("Dynamic signs could not be re-read; keeping the previous ones", e);
            }
        }
    }

    @Override
    public void disable() {
        clipboards.clear();
        if (dynamicSigns != null) {
            dynamicSigns.stop();
        }
    }

    // ---- formatting on placement ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        SignsConfig cfg = config.get();
        if (!cfg.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (cfg.blockMuted && muted(player)) {
            event.setCancelled(true);
            ctx.messages().send(player, "signs.muted");
            return;
        }
        if (!cfg.applyOnPlace) {
            return;
        }
        boolean colors = allowsColors(player);
        boolean decorations = allowsDecorations(player);
        if (!colors && !decorations) {
            return;
        }
        for (int line = 0; line < event.lines().size(); line++) {
            Component current = event.line(line);
            Component formatted = SignText.format(current, colors, decorations);
            if (!formatted.equals(current)) {
                event.line(line, formatted);
            }
        }
    }

    // ---- /signedit ----

    private int editUsage(CommandContext<CommandSourceStack> context) {
        ctx.messages().send(context.getSource().getSender(), "signs.usage-signedit");
        return Cmds.OK;
    }

    private int edit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            ctx.messages().send(sender, "core.player-only");
            return 0;
        }
        if (!config.get().enabled) {
            throw cmds.fail(context.getSource(), "signs.disabled");
        }
        if (config.get().blockMuted && muted(player)) {
            throw cmds.fail(context.getSource(), "signs.muted");
        }
        Block block = targetBlock(player);
        if (block == null) {
            throw cmds.fail(context.getSource(), "signs.no-sign", Msg.number("reach", reach()));
        }
        Side side = ((Sign) block.getState()).getInteractableSideFor(player);
        int line = IntegerArgumentType.getInteger(context, "line");
        String text = StringArgumentType.getString(context, "text");
        Component proposed = SignText.format(Component.text(text), allowsColors(player), allowsDecorations(player));
        int max = maxLineLength();
        if (SignText.exceeds(proposed, max)) {
            throw cmds.fail(context.getSource(), "signs.too-long", Msg.number("max", max));
        }

        ctx.track(ctx.scheduler().region(block.getLocation(), () -> {
            if (!player.isOnline()) {
                return;
            }
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                ctx.messages().send(player, "signs.no-sign", Msg.number("reach", reach()));
                return;
            }
            if (sign.isWaxed() && !player.hasPermission(PERM_WAXED)) {
                ctx.messages().send(player, "signs.waxed");
                return;
            }
            List<Component> lines = new ArrayList<>(sign.getSide(side).lines());
            pad(lines);
            lines.set(line - 1, proposed);
            SignChangeEvent event = new SignChangeEvent(block, player, lines, side);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                if (!muted(player)) {
                    ctx.messages().send(player, "signs.cancelled");
                }
                return;
            }
            apply(sign, event);
            ctx.messages().send(player, "signs.edited", Msg.number("line", line));
        }));
        return Cmds.OK;
    }

    // ---- /signcopy and /signpaste ----

    private int copy(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            ctx.messages().send(sender, "core.player-only");
            return 0;
        }
        if (!config.get().enabled) {
            ctx.messages().send(player, "signs.disabled");
            return 0;
        }
        Block block = targetBlock(player);
        if (block == null) {
            ctx.messages().send(player, "signs.no-sign", Msg.number("reach", reach()));
            return 0;
        }
        ctx.track(ctx.scheduler().region(block.getLocation(), () -> {
            if (!player.isOnline()) {
                return;
            }
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                ctx.messages().send(player, "signs.no-sign", Msg.number("reach", reach()));
                return;
            }
            SignSide front = sign.getSide(Side.FRONT);
            SignSide back = sign.getSide(Side.BACK);
            SignClipboard clipboard = new SignClipboard(front.lines(), back.lines(),
                    front.isGlowingText(), back.isGlowingText(), front.getColor());
            clipboards.put(player.getUniqueId(), clipboard);
            ctx.messages().send(player, "signs.copied");
        }));
        return Cmds.OK;
    }

    private int paste(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            ctx.messages().send(sender, "core.player-only");
            return 0;
        }
        if (!config.get().enabled) {
            ctx.messages().send(player, "signs.disabled");
            return 0;
        }
        if (config.get().blockMuted && muted(player)) {
            ctx.messages().send(player, "signs.muted");
            return 0;
        }
        SignClipboard clipboard = clipboards.get(player.getUniqueId());
        if (clipboard == null) {
            ctx.messages().send(player, "signs.paste-empty");
            return 0;
        }
        Block block = targetBlock(player);
        if (block == null) {
            ctx.messages().send(player, "signs.no-sign", Msg.number("reach", reach()));
            return 0;
        }
        List<Component> front = sanitize(clipboard.front(), player);
        List<Component> back = sanitize(clipboard.back(), player);
        int max = maxLineLength();
        if (tooLong(front, max) || tooLong(back, max)) {
            ctx.messages().send(player, "signs.too-long", Msg.number("max", max));
            return 0;
        }
        ctx.track(ctx.scheduler().region(block.getLocation(), () -> {
            if (!player.isOnline()) {
                return;
            }
            BlockState state = block.getState();
            if (!(state instanceof Sign sign)) {
                ctx.messages().send(player, "signs.no-sign", Msg.number("reach", reach()));
                return;
            }
            if (sign.isWaxed() && !player.hasPermission(PERM_WAXED)) {
                ctx.messages().send(player, "signs.waxed");
                return;
            }
            SignChangeEvent frontEvent = new SignChangeEvent(block, player, front, Side.FRONT);
            Bukkit.getPluginManager().callEvent(frontEvent);
            if (frontEvent.isCancelled()) {
                if (!muted(player)) {
                    ctx.messages().send(player, "signs.cancelled");
                }
                return;
            }
            SignChangeEvent backEvent = new SignChangeEvent(block, player, back, Side.BACK);
            Bukkit.getPluginManager().callEvent(backEvent);
            if (backEvent.isCancelled()) {
                if (!muted(player)) {
                    ctx.messages().send(player, "signs.cancelled");
                }
                return;
            }
            SignSide frontSide = sign.getSide(frontEvent.getSide());
            SignSide backSide = sign.getSide(backEvent.getSide());
            for (int i = 0; i < 4; i++) {
                frontSide.line(i, frontEvent.line(i));
                backSide.line(i, backEvent.line(i));
            }
            frontSide.setGlowingText(clipboard.frontGlowing());
            backSide.setGlowingText(clipboard.backGlowing());
            frontSide.setColor(clipboard.color());
            backSide.setColor(clipboard.color());
            sign.update(true);
            ctx.messages().send(player, "signs.pasted");
        }));
        return Cmds.OK;
    }

    // ---- helpers ----

    @Nullable
    private Block targetBlock(Player player) {
        Block block = player.getTargetBlockExact(reach());
        if (block == null || !(block.getState() instanceof Sign)) {
            return null;
        }
        return block;
    }

    private int reach() {
        return Math.clamp(config.get().reach, 1, 32);
    }

    private int maxLineLength() {
        return Math.clamp(config.get().maxLineLength, 1, 384);
    }

    private boolean allowsColors(Player player) {
        SignsConfig cfg = config.get();
        return cfg.allowColors && player.hasPermission(PERM_COLOR);
    }

    private boolean allowsDecorations(Player player) {
        SignsConfig cfg = config.get();
        return cfg.allowDecorations && player.hasPermission(PERM_FORMAT);
    }

    private boolean muted(Player player) {
        return ctx.services().get(MuteService.class)
                .map(mutes -> mutes.isMuted(player.getUniqueId()) || mutes.isShadowMuted(player.getUniqueId()))
                .orElse(false);
    }

    private void apply(Sign sign, SignChangeEvent event) {
        SignSide side = sign.getSide(event.getSide());
        for (int i = 0; i < 4; i++) {
            side.line(i, event.line(i));
        }
        sign.update(true);
    }

    /** Strips styles a paster is not allowed to use; keeps the copied lines intact for permitted players. */
    private List<Component> sanitize(List<Component> lines, Player player) {
        boolean colors = allowsColors(player);
        boolean decorations = allowsDecorations(player);
        boolean full = colors && decorations;
        List<Component> result = new ArrayList<>(lines.size());
        for (Component line : lines) {
            Component value = full ? SignText.stripInteractive(line)
                    : SignText.format(Component.text(SignText.plain(line)), colors, decorations);
            result.add(value);
        }
        pad(result);
        return result;
    }

    private static void pad(List<Component> lines) {
        while (lines.size() < 4) {
            lines.add(Component.empty());
        }
        while (lines.size() > 4) {
            lines.removeLast();
        }
    }

    private static boolean tooLong(List<Component> lines, int max) {
        return lines.stream().anyMatch(line -> SignText.exceeds(line, max));
    }

    // ---- dynamic signs (/dsign) ----

    private void registerDynamicCommands() {
        SuggestionProvider<CommandSourceStack> names = Cmds.suggest(() -> dynamicSigns == null
                ? List.of() : dynamicSigns.store().names());
        ctx.command(CommandSpec.of(Cmds.literal("dsign", PERM_DYNAMIC)
                .executes(this::dynamicUsage)
                .then(Cmds.literal("list").executes(this::dynamicList))
                .then(Cmds.literal("info").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(names).executes(this::dynamicInfo)))
                .then(Cmds.literal("create").then(Commands.argument("name", StringArgumentType.word())
                        .executes(this::dynamicCreate)))
                .then(Cmds.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(names).executes(this::dynamicRemove)))
                .then(Cmds.literal("lines").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(names)
                        .then(Commands.argument("lines", StringArgumentType.greedyString())
                                .executes(this::dynamicLines))))
                .then(Cmds.literal("interval").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(names)
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("500ms", "1s", "2s", "5s")))
                                .executes(this::dynamicInterval))))
                .then(Cmds.literal("range").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(names)
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 64))
                                .suggests(Cmds.suggest(() -> List.of("8", "16", "24", "32")))
                                .executes(this::dynamicRange))))
                .then(Cmds.literal("personal").then(Commands.argument("name", StringArgumentType.word())
                        .suggests(names)
                        .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                .executes(this::dynamicPersonal))))
                .then(Cmds.literal("reload").executes(this::dynamicReload))
                .build(), "Create scrolling signs that only send packets to the players near them", "dynamicsigns"));
    }

    private int dynamicUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "signs.dynamic-usage");
        return Cmds.OK;
    }

    private int dynamicList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<String> names = dynamicSigns.store().names();
        if (names.isEmpty()) {
            ctx.messages().send(sender, "signs.dynamic-list-empty");
        } else {
            ctx.messages().send(sender, "signs.dynamic-list", Msg.number("count", names.size()),
                    Msg.unparsed("signs", String.join(", ", names)));
        }
        return Cmds.OK;
    }

    private int dynamicInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DynamicSignStore.DynamicSign sign = dynamicSigns.store().get(name).orElseThrow(() ->
                cmds.fail(c.getSource(), "signs.dynamic-unknown", Msg.unparsed("name", name)));
        ctx.messages().send(sender, "signs.dynamic-info", Msg.unparsed("name", sign.name()),
                Msg.unparsed("location", sign.location()), Msg.number("range", sign.range()),
                Msg.number("interval", sign.intervalMillis()),
                Msg.unparsed("personal", Boolean.toString(sign.personal())),
                Msg.number("lines", sign.lines().size()));
        ctx.messages().send(sender, "signs.dynamic-info-lines",
                Msg.unparsed("lines", SignScroll.join(sign.lines())));
        return Cmds.OK;
    }

    private int dynamicCreate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (!DynamicSignStore.validName(name)) {
            throw cmds.fail(c.getSource(), "signs.dynamic-invalid-name");
        }
        if (dynamicSigns.store().get(name).isPresent()) {
            throw cmds.fail(c.getSource(), "signs.dynamic-exists", Msg.unparsed("name", name));
        }
        if (dynamicSigns.store().all().size() >= config.get().dynamicSignsMax()) {
            throw cmds.fail(c.getSource(), "signs.dynamic-limit", Msg.number("max", config.get().dynamicSignsMax()));
        }
        Block block = targetBlock(player);
        if (block == null) {
            throw cmds.fail(c.getSource(), "signs.no-sign", Msg.number("reach", reach()));
        }
        Location location = block.getLocation();
        if (dynamicSigns.store().at(location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ()).isPresent()) {
            throw cmds.fail(c.getSource(), "signs.dynamic-occupied");
        }
        if (!(block.getState() instanceof Sign sign)) {
            throw cmds.fail(c.getSource(), "signs.no-sign", Msg.number("reach", reach()));
        }
        DynamicSigns.SignSnapshot snapshot = DynamicSigns.snapshot(sign);
        long interval = DynamicSigns.parseInterval(config.get().dynamicSignsDefaultInterval, config)
                .orElse(1000L);
        DynamicSignStore.DynamicSign created = new DynamicSignStore.DynamicSign(name, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                SignScroll.sanitize(snapshot.lines()), interval, config.get().dynamicSignsDefaultRange(), true,
                snapshot.color(), snapshot.glowing());
        ctx.scheduler().supplyIo(() -> {
            dynamicSigns.store().put(created);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Dynamic sign '{}' could not be saved", name, error);
                replyLater(player, "signs.dynamic-save-failed");
                return;
            }
            dynamicSigns.restart();
            replyLater(player, "signs.dynamic-created", Msg.unparsed("name", name),
                    Msg.number("lines", created.lines().size()));
        });
        return Cmds.OK;
    }

    private int dynamicRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        if (dynamicSigns.store().get(name).isEmpty()) {
            throw cmds.fail(c.getSource(), "signs.dynamic-unknown", Msg.unparsed("name", name));
        }
        ctx.scheduler().supplyIo(() -> dynamicSigns.store().remove(name)).whenComplete((removed, error) -> {
            if (error != null) {
                ctx.logger().error("Dynamic sign '{}' could not be removed", name, error);
                replyNow(sender, "signs.dynamic-save-failed");
                return;
            }
            dynamicSigns.restart();
            replyNow(sender, "signs.dynamic-removed", Msg.unparsed("name", name));
        });
        return Cmds.OK;
    }

    private int dynamicLines(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DynamicSignStore.DynamicSign sign = dynamicSigns.store().get(name).orElseThrow(() ->
                cmds.fail(c.getSource(), "signs.dynamic-unknown", Msg.unparsed("name", name)));
        List<String> lines = SignScroll.sanitize(SignScroll.split(StringArgumentType.getString(c, "lines")));
        if (lines.isEmpty() || lines.stream().allMatch(String::isBlank)) {
            throw cmds.fail(c.getSource(), "signs.dynamic-empty-lines");
        }
        updateDynamic(sender, sign.withLines(lines), "signs.dynamic-lines-set", Msg.number("lines", lines.size()));
        return Cmds.OK;
    }

    private int dynamicInterval(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DynamicSignStore.DynamicSign sign = dynamicSigns.store().get(name).orElseThrow(() ->
                cmds.fail(c.getSource(), "signs.dynamic-unknown", Msg.unparsed("name", name)));
        String text = StringArgumentType.getString(c, "duration");
        long interval = DynamicSigns.parseInterval(text, config).orElseThrow(() ->
                cmds.fail(c.getSource(), "signs.dynamic-bad-interval", Msg.unparsed("value", text)));
        updateDynamic(sender, sign.withInterval(interval), "signs.dynamic-interval-set",
                Msg.number("interval", interval));
        return Cmds.OK;
    }

    private int dynamicRange(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DynamicSignStore.DynamicSign sign = dynamicSigns.store().get(name).orElseThrow(() ->
                cmds.fail(c.getSource(), "signs.dynamic-unknown", Msg.unparsed("name", name)));
        int range = IntegerArgumentType.getInteger(c, "blocks");
        updateDynamic(sender, sign.withRange(range), "signs.dynamic-range-set", Msg.number("range", range));
        return Cmds.OK;
    }

    private int dynamicPersonal(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name").toLowerCase(Locale.ROOT);
        DynamicSignStore.DynamicSign sign = dynamicSigns.store().get(name).orElseThrow(() ->
                cmds.fail(c.getSource(), "signs.dynamic-unknown", Msg.unparsed("name", name)));
        boolean personal = com.mojang.brigadier.arguments.BoolArgumentType.getBool(c, "value");
        updateDynamic(sender, sign.withPersonal(personal), "signs.dynamic-personal-set",
                Msg.unparsed("value", Boolean.toString(personal)));
        return Cmds.OK;
    }

    private int dynamicReload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyIo(() -> {
            dynamicSigns.store().load();
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().warn("Dynamic signs could not be reloaded", error);
                replyNow(sender, "core.error");
                return;
            }
            dynamicSigns.restart();
            replyNow(sender, "signs.dynamic-reloaded", Msg.number("count", dynamicSigns.store().all().size()));
        });
        return Cmds.OK;
    }

    /** Saves a changed sign and replies only after the write succeeded. */
    private void updateDynamic(CommandSender sender, DynamicSignStore.DynamicSign sign, String key, TagResolver... tags) {
        ctx.scheduler().supplyIo(() -> {
            dynamicSigns.store().put(sign);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Dynamic sign '{}' could not be saved", sign.name(), error);
                replyNow(sender, "signs.dynamic-save-failed");
                return;
            }
            dynamicSigns.restart();
            replyNow(sender, key, tags);
        });
    }

    private void replyNow(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            replyLater(player, key, tags);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    private void replyLater(Player player, String key, TagResolver... tags) {
        ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clipboards.remove(event.getPlayer().getUniqueId());
    }
}
