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
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
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

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<SignsConfig> config;
    private final Map<UUID, SignClipboard> clipboards = new ConcurrentHashMap<>();

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
    }

    @Override
    public void disable() {
        clipboards.clear();
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clipboards.remove(event.getPlayer().getUniqueId());
    }
}
