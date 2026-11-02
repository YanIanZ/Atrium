package dev.yanianz.atrium.module.holograms;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.holograms.HologramManager.Change;
import dev.yanianz.atrium.module.holograms.HologramManager.Outcome;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver;
import io.papermc.paper.math.FinePosition;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** {@code /holo} (aliases {@code /hologram}, {@code /holograms}). Every edit is stored before it is shown. */
final class HologramCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final Supplier<HologramsConfig> config;
    private final HologramManager manager;
    private final Displays displays;
    private final EngineController engines;
    private final Supplier<HologramSettings> defaults;

    HologramCommands(ModuleContext ctx, Supplier<HologramsConfig> config, HologramManager manager, Displays displays,
            EngineController engines, Supplier<HologramSettings> defaults) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.config = config;
        this.manager = manager;
        this.displays = displays;
        this.engines = engines;
        this.defaults = defaults;
    }

    LiteralCommandNode<CommandSourceStack> build(String permission) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmds.literal("holo", permission);
        root.executes(this::usage);
        root.then(Cmds.literal("create").then(Commands.argument("name", StringArgumentType.word())
                .executes(c -> create(c, null))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> create(c, StringArgumentType.getString(c, "text"))))));
        root.then(Cmds.literal("delete").then(name().executes(this::delete)));
        root.then(Cmds.literal("list").executes(c -> list(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).suggests(listPages())
                        .executes(c -> list(c, IntegerArgumentType.getInteger(c, "page")))));
        root.then(Cmds.literal("near").executes(c -> near(c, config.get().nearRadius))
                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 1024))
                        .executes(c -> near(c, DoubleArgumentType.getDouble(c, "radius")))));
        root.then(Cmds.literal("move").then(name().then(Commands.argument("position", ArgumentTypes.finePosition(true))
                .executes(this::move)
                .then(Commands.argument("world", ArgumentTypes.world()).executes(this::move)))));
        root.then(Cmds.literal("movehere").then(name().executes(this::moveHere)));
        root.then(Cmds.literal("teleport").then(name().executes(this::teleport)));
        root.then(Cmds.literal("tp").then(name().executes(this::teleport)));
        root.then(Cmds.literal("addline").then(name().then(text().executes(this::addLine))));
        root.then(Cmds.literal("setline").then(name().then(line().then(text().executes(this::setLine)))));
        root.then(Cmds.literal("insertline").then(name().then(line().then(text().executes(this::insertLine)))));
        root.then(Cmds.literal("removeline").then(name().then(line().executes(this::removeLine))));
        root.then(Cmds.literal("linehover").then(name().then(line()
                .then(Commands.argument("hover", StringArgumentType.greedyString())
                        .executes(this::lineHover)))));
        root.then(Cmds.literal("lineclick").then(name().then(line()
                .then(Commands.argument("command", StringArgumentType.greedyString())
                        .executes(this::lineClick)))));
        root.then(Cmds.literal("lineoffset").then(name().then(line()
                .then(Commands.argument("x", FloatArgumentType.floatArg(-16, 16))
                        .then(Commands.argument("y", FloatArgumentType.floatArg(-16, 16))
                                .then(Commands.argument("z", FloatArgumentType.floatArg(-16, 16))
                                        .executes(this::lineOffset)))))));
        root.then(Cmds.literal("addpage").then(name().executes(c -> addPage(c, null))
                .then(text().executes(c -> addPage(c, StringArgumentType.getString(c, "text"))))));
        root.then(Cmds.literal("removepage").then(name()
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .suggests(numbers("name", hologram -> hologram.pages().size()))
                        .executes(this::removePage))));
        root.then(Cmds.literal("setting").then(name()
                .then(Commands.argument("option", StringArgumentType.word()).suggests(Cmds.suggest(Setting::keys))
                        .then(Commands.argument("value", StringArgumentType.greedyString()).suggests(settingValues())
                                .executes(this::setting)))));
        root.then(Cmds.literal("action").then(name()
                .then(Cmds.literal("add").then(Commands.argument("action", StringArgumentType.greedyString())
                        .suggests(Cmds.suggest(() -> List.of("right [console] ", "left [player] ", "[message] ", "right [next]",
                                "left [prev]", "[page] ")))
                        .executes(this::actionAdd)))
                .then(Cmds.literal("remove").then(Commands.argument("index", IntegerArgumentType.integer(1))
                        .suggests(numbers("name", hologram -> hologram.actions().size()))
                        .executes(this::actionRemove)))
                .then(Cmds.literal("clear").executes(this::actionClear))));
        root.then(Cmds.literal("info").then(name().executes(this::info)));
        root.then(Cmds.literal("engine", HologramsModule.PERM_ENGINE)
                .executes(this::engineInfo)
                .then(Cmds.literal("migrate")
                        .then(Commands.argument("from", StringArgumentType.word()).suggests(engineNames())
                                .then(Commands.argument("to", StringArgumentType.word()).suggests(engineNames())
                                        .executes(this::engineMigrate)))));
        return root.build();
    }

    // ---- arguments ----

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name() {
        return Commands.argument("name", StringArgumentType.word()).suggests(Cmds.suggest(
                () -> manager.all().stream().map(Hologram::name).sorted(String.CASE_INSENSITIVE_ORDER).toList()));
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Integer> line() {
        return Commands.argument("line", IntegerArgumentType.integer(1))
                .suggests(numbers("name", Hologram::lineCount));
    }

    /** Suggests 1..count of an existing hologram, so line/page/action numbers never have to be looked up. */
    private SuggestionProvider<CommandSourceStack> numbers(String nameArgument,
            java.util.function.ToIntFunction<Hologram> count) {
        return (c, builder) -> {
            String name;
            try {
                name = StringArgumentType.getString(c, nameArgument);
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            manager.get(name).ifPresent(hologram -> {
                int max = count.applyAsInt(hologram);
                for (int value = 1; value <= max; value++) {
                    if (Integer.toString(value).startsWith(typed)) {
                        builder.suggest(value);
                    }
                }
            });
            return builder.buildFuture();
        };
    }

    /** Suggests the pages of the {@code /holo list} output. */
    private SuggestionProvider<CommandSourceStack> listPages() {
        return (c, builder) -> {
            int size = Math.max(1, config.get().listPageSize);
            int pages = Math.max(1, (manager.all().size() + size - 1) / size);
            String typed = builder.getRemainingLowerCase();
            for (int value = 1; value <= pages; value++) {
                if (Integer.toString(value).startsWith(typed)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> text() {
        return Commands.argument("text", StringArgumentType.greedyString());
    }

    private SuggestionProvider<CommandSourceStack> settingValues() {
        return (c, builder) -> {
            String option;
            try {
                option = StringArgumentType.getString(c, "option");
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            Setting.byKey(option).ifPresent(s -> s.suggestions().stream()
                    .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(typed)).forEach(builder::suggest));
            return builder.buildFuture();
        };
    }

    private static String nameArg(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    // ---- line validation (sender's thread) ----

    /** Checks a line typed by an admin; {@code #hand} becomes the saved main-hand item. */
    private String line(CommandContext<CommandSourceStack> c, String raw) throws CommandSyntaxException {
        if (raw.strip().equalsIgnoreCase("#hand")) {
            Player player = cmds.player(c.getSource());
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                throw cmds.fail(c.getSource(), "holograms.no-hand-item");
            }
            byte[] bytes = item.serializeAsBytes();
            int max = config.get().maxSavedItemBytes;
            if (bytes.length > max) {
                throw cmds.fail(c.getSource(), "holograms.item-too-large", Msg.number("size", bytes.length),
                        Msg.number("max", max));
            }
            return LineContent.ITEM_STACK + Base64.getEncoder().encodeToString(bytes);
        }
        String valid;
        try {
            valid = validateLine(raw, config.get().maxLineLength);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "holograms.invalid-line", Msg.unparsed("reason", e.getMessage()));
        }
        checkLine(c.getSource(), valid);
        return valid;
    }

    /** Refuses a line kind the active engine cannot show (item/block lines are native/DH features). */
    private void checkLine(CommandSourceStack source, String raw) throws CommandSyntaxException {
        LineContent content = LineContent.parse(raw);
        if (content instanceof LineContent.Image) {
            requireFeature(source, EngineCapabilities.Feature.IMAGE_LINES);
            if (!Bukkit.getServer().getOnlineMode()) {
                messages.send(source.getSender(), "holograms.image-offline-default");
            }
        } else if (content.isItemLike()) {
            requireFeature(source, EngineCapabilities.Feature.ITEM_LINES);
        } else if (content instanceof LineContent.Block) {
            requireFeature(source, EngineCapabilities.Feature.BLOCK_LINES);
        }
        if (content.viewerDependent()) {
            // #iteminhand and #img:head without a name only make sense with a copy per viewer.
            requireFeature(source, EngineCapabilities.Feature.PER_VIEWER_TEXT);
        }
    }

    private void requireFeature(CommandSourceStack source, EngineCapabilities.Feature feature)
            throws CommandSyntaxException {
        if (!engines.capabilities().supports(feature)) {
            throw cmds.fail(source, "holograms.engine-feature-unsupported",
                    Msg.unparsed("engine", engines.activeId()),
                    Msg.component("feature", messages.render(source.getSender(),
                            EngineCapabilities.capabilityKey(feature))));
        }
    }

    private void requireSettingFeature(CommandSourceStack source, Setting setting, Object value)
            throws CommandSyntaxException {
        switch (setting) {
            case PAGE_INTERVAL -> {
                if ((Integer) value > 0) {
                    requireFeature(source, EngineCapabilities.Feature.PER_VIEWER_PAGES);
                }
            }
            case CLICK_PAGES -> {
                if ((Boolean) value) {
                    requireFeature(source, EngineCapabilities.Feature.CLICK_ACTIONS);
                }
            }
            case PER_VIEWER -> {
                // AUTO degrades to shared text on engines without per-viewer text; ON explicitly requests it.
                if (value == Setting.PerViewer.ON) {
                    requireFeature(source, EngineCapabilities.Feature.PER_VIEWER_TEXT);
                }
            }
            case ROTATE_SPEED -> {
                if ((Float) value != 0f) {
                    requireFeature(source, EngineCapabilities.Feature.ANIMATIONS);
                }
            }
            case FADE_TICKS -> {
                if ((Integer) value > 0) {
                    requireFeature(source, EngineCapabilities.Feature.ANIMATIONS);
                }
            }
            default -> {
                // Other settings are either universal or harmless to store for a later engine switch.
            }
        }
    }

    /** Validates a stored line (any thread). Throws {@link IllegalArgumentException} with a reason. */
    static String validateLine(String raw, int maxLength) {
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line breaks are not allowed; add another line instead");
        }
        LineContent content = LineContent.parse(raw);
        switch (content) {
            case LineContent.Text text -> {
                if (raw.length() > maxLength) {
                    throw new IllegalArgumentException("longer than " + maxLength + " characters");
                }
            }
            case LineContent.Item item -> {
                Material material = Material.matchMaterial(item.material());
                if (material == null || !material.isItem() || material.isAir()) {
                    throw new IllegalArgumentException("unknown item " + item.material());
                }
            }
            case LineContent.Block block -> {
                try {
                    if (!Bukkit.createBlockData(block.blockData()).getMaterial().isBlock()) {
                        throw new IllegalArgumentException("not a block: " + block.blockData());
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("invalid block data " + block.blockData());
                }
            }
            case LineContent.SavedItem saved -> {
                try {
                    ItemStack.deserializeBytes(Base64.getDecoder().decode(saved.base64()));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("unreadable saved item");
                }
            }
            case LineContent.Head head -> {
                // The name pattern is already enforced by the parser; nothing else to look up.
                if (!head.player().isEmpty() && !LineContent.PLAYER_NAME.matcher(head.player()).matches()) {
                    throw new IllegalArgumentException("invalid player name " + head.player());
                }
            }
            case LineContent.Image image -> {
                if (!LineContent.PLAYER_NAME.matcher(image.player()).matches()) {
                    throw new IllegalArgumentException("invalid player name " + image.player());
                }
                if (image.size() < 1 || image.size() > 4) {
                    throw new IllegalArgumentException("image size must be from 1 to 4");
                }
            }
            case LineContent.Icon icon -> {
                if (icon.kind() == LineContent.IconKind.CUSTOM_MODEL_DATA && icon.customModelData() < 0) {
                    throw new IllegalArgumentException("custom model data must not be negative");
                }
            }
        }
        return raw;
    }

    // ---- create / delete / move ----

    private int create(CommandContext<CommandSourceStack> c, @Nullable String text) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = nameArg(c);
        if (!Hologram.validName(name)) {
            throw cmds.fail(c.getSource(), "holograms.invalid-name");
        }
        String first = text == null ? name : line(c, text);
        Location at = player.getLocation();
        StoredLocation location = new StoredLocation(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), 0f);
        HologramSettings settings = defaults.get();
        report(player, manager.edit(name, current -> current != null ? Change.fail(Outcome.EXISTS)
                : Change.ok(new Hologram(name, location, settings, List.of(List.of(HoloLine.of(first))), List.of(),
                        System.currentTimeMillis()))), "holograms.created", Msg.unparsed("name", name));
        return Cmds.OK;
    }

    private int delete(CommandContext<CommandSourceStack> c) {
        String name = nameArg(c);
        report(c.getSource().getSender(), manager.edit(name, current -> current == null ? Change.fail(Outcome.NOT_FOUND)
                : Change.delete()), "holograms.deleted", Msg.unparsed("name", name));
        return Cmds.OK;
    }

    private int move(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        FinePosition position = c.getArgument("position", FinePositionResolver.class).resolve(c.getSource());
        World world = Cmds.hasArgument(c, "world") ? c.getArgument("world", World.class) : c.getSource().getLocation().getWorld();
        return moveTo(c.getSource().getSender(), nameArg(c), world, position.x(), position.y(), position.z(), null);
    }

    private int moveHere(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Location at = player.getLocation();
        return moveTo(player, nameArg(c), at.getWorld(), at.getX(), at.getY(), at.getZ(), at.getYaw());
    }

    private int moveTo(CommandSender sender, String name, World world, double x, double y, double z, @Nullable Float yaw) {
        report(sender, manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            float newYaw = yaw == null ? current.location().yaw() : yaw;
            return Change.ok(current.withLocation(new StoredLocation(world.getName(), x, y, z, newYaw, 0f)));
        }), "holograms.moved", Msg.unparsed("name", name), Msg.unparsed("world", world.getName()),
                Msg.unparsed("x", fmt(x)), Msg.unparsed("y", fmt(y)), Msg.unparsed("z", fmt(z)));
        return Cmds.OK;
    }

    private int teleport(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Hologram hologram = require(c);
        Location target = hologram.location().toLocation().orElseThrow(() -> cmds.fail(c.getSource(),
                "holograms.world-not-loaded", Msg.unparsed("world", hologram.location().world())));
        target.setPitch(player.getLocation().getPitch());
        player.teleportAsync(target).whenComplete((ok, error) -> {
            if (error != null) {
                ctx.logger().error("Teleport to hologram {} failed", hologram.name(), error);
            }
            reply(player, Boolean.TRUE.equals(ok) ? "holograms.teleported" : "holograms.teleport-failed",
                    Msg.unparsed("name", hologram.name()));
        });
        return Cmds.OK;
    }

    // ---- lines ----

    private int addLine(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = nameArg(c);
        String raw = line(c, StringArgumentType.getString(c, "text"));
        AtomicInteger number = new AtomicInteger();
        int maxLines = config.get().maxLinesPerPage;
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            List<HoloLine> last = pages.getLast();
            if (last.size() >= maxLines) {
                return Change.fail(Outcome.LIMIT);
            }
            last.add(HoloLine.of(raw));
            number.set(LineIndex.count(pages));
            return Change.ok(current.withPages(pages));
        }), "holograms.line-added", Msg.unparsed("name", name), lazyNumber("line", number));
        return Cmds.OK;
    }

    private int setLine(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = nameArg(c);
        int line = IntegerArgumentType.getInteger(c, "line");
        String raw = line(c, StringArgumentType.getString(c, "text"));
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            var position = LineIndex.locate(pages, line);
            if (position.isEmpty()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            List<HoloLine> page = pages.get(position.get().page());
            page.set(position.get().index(), page.get(position.get().index()).withRaw(raw));
            return Change.ok(current.withPages(pages));
        }), "holograms.line-set", Msg.unparsed("name", name), Msg.number("line", line));
        return Cmds.OK;
    }

    private int insertLine(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = nameArg(c);
        int line = IntegerArgumentType.getInteger(c, "line");
        String raw = line(c, StringArgumentType.getString(c, "text"));
        int maxLines = config.get().maxLinesPerPage;
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            var position = LineIndex.insertionPoint(pages, line);
            if (position.isEmpty()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            List<HoloLine> page = pages.get(position.get().page());
            if (page.size() >= maxLines) {
                return Change.fail(Outcome.LIMIT);
            }
            page.add(position.get().index(), HoloLine.of(raw));
            return Change.ok(current.withPages(pages));
        }), "holograms.line-inserted", Msg.unparsed("name", name), Msg.number("line", line));
        return Cmds.OK;
    }

    private int removeLine(CommandContext<CommandSourceStack> c) {
        String name = nameArg(c);
        int line = IntegerArgumentType.getInteger(c, "line");
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            var position = LineIndex.locate(pages, line);
            if (position.isEmpty()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            pages.get(position.get().page()).remove(position.get().index());
            return Change.ok(current.withPages(pages));
        }), "holograms.line-removed", Msg.unparsed("name", name), Msg.number("line", line));
        return Cmds.OK;
    }

    private int lineOffset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        requireFeature(c.getSource(), EngineCapabilities.Feature.LINE_OFFSETS);
        String name = nameArg(c);
        int line = IntegerArgumentType.getInteger(c, "line");
        float x = FloatArgumentType.getFloat(c, "x");
        float y = FloatArgumentType.getFloat(c, "y");
        float z = FloatArgumentType.getFloat(c, "z");
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            var position = LineIndex.locate(pages, line);
            if (position.isEmpty()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            List<HoloLine> page = pages.get(position.get().page());
            page.set(position.get().index(), page.get(position.get().index()).withOffset(x, y, z));
            return Change.ok(current.withPages(pages));
        }), "holograms.line-offset", Msg.unparsed("name", name), Msg.number("line", line),
                Msg.unparsed("x", Float.toString(x)), Msg.unparsed("y", Float.toString(y)), Msg.unparsed("z", Float.toString(z)));
        return Cmds.OK;
    }

    /** Sets the hover text of one line, or clears it with 'none'. Native engines only. */
    private int lineHover(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        requireFeature(c.getSource(), EngineCapabilities.Feature.LINE_EVENTS);
        String name = nameArg(c);
        int line = IntegerArgumentType.getInteger(c, "line");
        String raw = StringArgumentType.getString(c, "hover").strip();
        String hover = raw.equalsIgnoreCase("none") ? "" : raw;
        if (!hover.isEmpty() && !isTextLine(name, line)) {
            throw cmds.fail(c.getSource(), "holograms.line-not-text", Msg.number("line", line));
        }
        if (hover.length() > config.get().maxLineLength) {
            throw cmds.fail(c.getSource(), "holograms.invalid-line",
                    Msg.unparsed("reason", "longer than " + config.get().maxLineLength + " characters"));
        }
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            var position = LineIndex.locate(pages, line);
            if (position.isEmpty()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            List<HoloLine> page = pages.get(position.get().page());
            HoloLine old = page.get(position.get().index());
            if (!old.content().isText()) {
                return Change.fail(Outcome.INVALID);
            }
            page.set(position.get().index(), old.withHover(hover));
            return Change.ok(current.withPages(pages));
        }), hover.isEmpty() ? "holograms.line-hover-cleared" : "holograms.line-hover-set",
                Msg.unparsed("name", name), Msg.number("line", line));
        return Cmds.OK;
    }

    /** Sets the click command of one line, or clears it with 'none'. Native engines only. */
    private int lineClick(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        requireFeature(c.getSource(), EngineCapabilities.Feature.LINE_EVENTS);
        String name = nameArg(c);
        int line = IntegerArgumentType.getInteger(c, "line");
        String raw = StringArgumentType.getString(c, "command").strip();
        String command;
        try {
            command = raw.equalsIgnoreCase("none") ? "" : validateClick(raw);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "holograms.invalid-line", Msg.unparsed("reason", e.getMessage()));
        }
        if (!command.isEmpty() && !isTextLine(name, line)) {
            throw cmds.fail(c.getSource(), "holograms.line-not-text", Msg.number("line", line));
        }
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            var position = LineIndex.locate(pages, line);
            if (position.isEmpty()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            List<HoloLine> page = pages.get(position.get().page());
            HoloLine old = page.get(position.get().index());
            if (!old.content().isText()) {
                return Change.fail(Outcome.INVALID);
            }
            page.set(position.get().index(), old.withClick(command));
            return Change.ok(current.withPages(pages));
        }), command.isEmpty() ? "holograms.line-click-cleared" : "holograms.line-click-set",
                Msg.unparsed("name", name), Msg.number("line", line), Msg.unparsed("command", command));
        return Cmds.OK;
    }

    /** True when the numbered line exists and is text (only text can carry hover text or a click). */
    private boolean isTextLine(String name, int line) {
        return manager.get(name).map(hologram -> {
            var position = LineIndex.locate(hologram.pages(), line);
            return position.map(index -> hologram.pages().get(index.page()).get(index.index()).content().isText())
                    .orElse(false);
        }).orElse(false);
    }

    /**
     * A click command is stored without the leading slash and must stay one printable line: a newline would let a
     * stored line become a second command, and a section sign would smuggle legacy colour codes into the client.
     */
    static String validateClick(String raw) {
        String command = raw.strip();
        if (command.startsWith("/")) {
            command = command.substring(1).strip();
        }
        if (command.isEmpty()) {
            throw new IllegalArgumentException("the command is empty");
        }
        if (command.length() > 256) {
            throw new IllegalArgumentException("longer than 256 characters");
        }
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\u00a7') {
                throw new IllegalArgumentException("control characters and section signs are not allowed");
            }
        }
        return command;
    }

    // ---- pages ----

    private int addPage(CommandContext<CommandSourceStack> c, @Nullable String text) throws CommandSyntaxException {
        requireFeature(c.getSource(), EngineCapabilities.Feature.PAGES);
        String name = nameArg(c);
        String raw = text == null ? null : line(c, text);
        int maxPages = config.get().maxPages;
        AtomicInteger number = new AtomicInteger();
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            if (pages.size() >= maxPages) {
                return Change.fail(Outcome.LIMIT);
            }
            pages.add(raw == null ? new ArrayList<>() : new ArrayList<>(List.of(HoloLine.of(raw))));
            number.set(pages.size());
            return Change.ok(current.withPages(pages));
        }), "holograms.page-added", Msg.unparsed("name", name), lazyNumber("page", number));
        return Cmds.OK;
    }

    private int removePage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        requireFeature(c.getSource(), EngineCapabilities.Feature.PAGES);
        String name = nameArg(c);
        int page = IntegerArgumentType.getInteger(c, "page");
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<List<HoloLine>> pages = current.mutablePages();
            if (page > pages.size()) {
                return Change.fail(Outcome.NO_SUCH_PAGE);
            }
            pages.remove(page - 1);
            // The hologram keeps one (possibly empty) page; viewers on a removed page fall back to page 1.
            return Change.ok(current.withPages(pages));
        }), "holograms.page-removed", Msg.unparsed("name", name), Msg.number("page", page));
        return Cmds.OK;
    }

    // ---- settings / actions ----

    private int setting(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = nameArg(c);
        String option = StringArgumentType.getString(c, "option");
        String value = StringArgumentType.getString(c, "value");
        Setting setting = Setting.byKey(option).orElseThrow(() -> cmds.fail(c.getSource(), "holograms.unknown-setting",
                Msg.unparsed("option", option), Msg.unparsed("options", String.join(", ", Setting.keys()))));
        Object parsed;
        try {
            parsed = setting.parse(value);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "holograms.setting-invalid", Msg.unparsed("option", setting.key),
                    Msg.unparsed("reason", e.getMessage()));
        }
        requireSettingFeature(c.getSource(), setting, parsed);
        String normalized = setting.format(parsed);
        report(c.getSource().getSender(), manager.edit(name, current -> current == null ? Change.fail(Outcome.NOT_FOUND)
                : Change.ok(current.withSettings(current.settings().with(setting, normalized)))),
                "holograms.setting-set", Msg.unparsed("name", name), Msg.unparsed("option", setting.key),
                Msg.unparsed("value", normalized.isEmpty() ? "none" : normalized));
        return Cmds.OK;
    }

    private int actionAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        requireFeature(c.getSource(), EngineCapabilities.Feature.CLICK_ACTIONS);
        String name = nameArg(c);
        ClickAction action;
        try {
            action = ClickAction.parse(StringArgumentType.getString(c, "action"));
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "holograms.invalid-action", Msg.unparsed("reason", e.getMessage()));
        }
        AtomicInteger index = new AtomicInteger();
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            List<ClickAction> actions = new ArrayList<>(current.actions());
            if (actions.size() >= 64) {
                return Change.fail(Outcome.LIMIT);
            }
            actions.add(action);
            index.set(actions.size());
            return Change.ok(current.withActions(actions));
        }), "holograms.action-added", Msg.unparsed("name", name), lazyNumber("index", index));
        return Cmds.OK;
    }

    private int actionRemove(CommandContext<CommandSourceStack> c) {
        String name = nameArg(c);
        int index = IntegerArgumentType.getInteger(c, "index");
        report(c.getSource().getSender(), manager.edit(name, current -> {
            if (current == null) {
                return Change.fail(Outcome.NOT_FOUND);
            }
            if (index > current.actions().size()) {
                return Change.fail(Outcome.NO_SUCH_LINE);
            }
            List<ClickAction> actions = new ArrayList<>(current.actions());
            actions.remove(index - 1);
            return Change.ok(current.withActions(actions));
        }), "holograms.action-removed", Msg.unparsed("name", name), Msg.number("index", index),
                Msg.number("line", index));
        return Cmds.OK;
    }

    private int actionClear(CommandContext<CommandSourceStack> c) {
        String name = nameArg(c);
        report(c.getSource().getSender(), manager.edit(name, current -> current == null ? Change.fail(Outcome.NOT_FOUND)
                : Change.ok(current.withActions(List.of()))), "holograms.actions-cleared", Msg.unparsed("name", name));
        return Cmds.OK;
    }

    // ---- read-only ----

    private int usage(CommandContext<CommandSourceStack> c) {
        messages.send(c.getSource().getSender(), "holograms.usage");
        return Cmds.OK;
    }

    private Hologram require(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = nameArg(c);
        return manager.get(name).orElseThrow(() -> cmds.fail(c.getSource(), "holograms.not-found", Msg.unparsed("name", name)));
    }

    private int list(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        List<Hologram> all = manager.all().stream().sorted(Comparator.comparing(Hologram::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (all.isEmpty()) {
            messages.send(sender, "holograms.list-empty");
            return Cmds.OK;
        }
        int size = Math.max(1, config.get().listPageSize);
        int pages = (all.size() + size - 1) / size;
        int shown = Math.min(page, pages);
        messages.send(sender, "holograms.list-header", Msg.number("count", all.size()), Msg.number("page", shown),
                Msg.number("pages", pages));
        for (Hologram hologram : all.subList((shown - 1) * size, Math.min(all.size(), shown * size))) {
            StoredLocation l = hologram.location();
            // Names are validated ([A-Za-z0-9_-]), so they are safe inside a click command.
            sender.sendMessage(messages.render(sender, "holograms.list-entry", Msg.unparsed("name", hologram.name()),
                    Msg.unparsed("world", l.world()), Msg.number("x", l.blockX()), Msg.number("y", l.blockY()),
                    Msg.number("z", l.blockZ()), Msg.number("lines", hologram.lineCount()),
                    Msg.number("pages", hologram.pages().size()))
                    .clickEvent(ClickEvent.runCommand("/holo teleport " + hologram.name()))
                    .hoverEvent(HoverEvent.showText(messages.render(sender, "holograms.list-hover",
                            Msg.unparsed("name", hologram.name())))));
        }
        return Cmds.OK;
    }

    private int near(CommandContext<CommandSourceStack> c, double radius) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Location at = player.getLocation();
        record Near(Hologram hologram, double distance) {}
        List<Near> found = new ArrayList<>();
        for (Hologram hologram : manager.all()) {
            StoredLocation l = hologram.location();
            if (!l.world().equals(at.getWorld().getName())) {
                continue;
            }
            double dx = l.x() - at.getX();
            double dy = l.y() - at.getY();
            double dz = l.z() - at.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= radius) {
                found.add(new Near(hologram, distance));
            }
        }
        if (found.isEmpty()) {
            messages.send(player, "holograms.near-empty", Msg.number("radius", radius));
            return Cmds.OK;
        }
        found.sort(Comparator.comparingDouble(Near::distance));
        messages.send(player, "holograms.near-header", Msg.number("radius", radius));
        for (Near near : found) {
            player.sendMessage(messages.render(player, "holograms.near-entry", Msg.unparsed("name", near.hologram().name()),
                    Msg.unparsed("distance", fmt(near.distance())))
                    .clickEvent(ClickEvent.runCommand("/holo teleport " + near.hologram().name())));
        }
        return Cmds.OK;
    }

    private int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Hologram hologram = require(c);
        StoredLocation l = hologram.location();
        HologramRuntime runtime = displays.runtime(hologram.name());
        boolean spawned = runtime != null && runtime.snapshot().spawned();
        messages.send(sender, "holograms.info-header", Msg.unparsed("name", hologram.name()));
        messages.send(sender, "holograms.info-engine", Msg.unparsed("engine", engines.activeId()));
        messages.send(sender, "holograms.info-location", Msg.unparsed("world", l.world()), Msg.unparsed("x", fmt(l.x())),
                Msg.unparsed("y", fmt(l.y())), Msg.unparsed("z", fmt(l.z())), Msg.unparsed("loaded", Boolean.toString(spawned)));
        messages.send(sender, "holograms.info-mode",
                Msg.unparsed("personal", Boolean.toString(runtime != null && runtime.personalMode())),
                Msg.unparsed("clickable", Boolean.toString(runtime != null && runtime.clickable())));
        StringBuilder settings = new StringBuilder();
        for (Setting setting : Setting.values()) {
            String value = hologram.settings().formatted(setting);
            settings.append(settings.isEmpty() ? "" : ", ").append(setting.key).append('=').append(value.isEmpty() ? "none" : value);
        }
        messages.send(sender, "holograms.info-settings", Msg.unparsed("settings", settings.toString()));
        int number = 1;
        for (int page = 0; page < hologram.pages().size(); page++) {
            messages.send(sender, "holograms.info-page", Msg.number("page", page + 1),
                    Msg.number("pages", hologram.pages().size()));
            for (HoloLine line : hologram.pages().get(page)) {
                String raw = line.raw();
                String shown = raw.startsWith(LineContent.ITEM_STACK) ? LineContent.ITEM_STACK + "<" + raw.length() + " chars>" : raw;
                if (line.hasOffset()) {
                    shown += "  [offset " + line.offsetX() + " " + line.offsetY() + " " + line.offsetZ() + "]";
                }
                if (!line.hover().isEmpty()) {
                    shown += "  [hover " + line.hover() + "]";
                }
                if (!line.click().isEmpty()) {
                    shown += "  [click /" + line.click() + "]";
                }
                messages.send(sender, "holograms.info-line", Msg.number("line", number++), Msg.unparsed("text", shown));
            }
        }
        if (!hologram.actions().isEmpty()) {
            messages.send(sender, "holograms.info-actions");
            int index = 1;
            for (ClickAction action : hologram.actions()) {
                messages.send(sender, "holograms.info-action", Msg.number("index", index++),
                        Msg.unparsed("action", action.encode()));
            }
        }
        return Cmds.OK;
    }

    // ---- engine ----

    private static SuggestionProvider<CommandSourceStack> engineNames() {
        return Cmds.suggest(EngineKind::names);
    }

    private int engineInfo(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        messages.send(sender, "holograms.engine-header", Msg.unparsed("engine", engines.activeId()),
                Msg.unparsed("configured", engines.configuredId()));
        EngineCapabilities capabilities = engines.capabilities();
        messages.send(sender, "holograms.engine-capabilities",
                Msg.unparsed("supported", EngineCapabilities.describe(capabilities.supported())));
        if (capabilities.missing().isEmpty()) {
            messages.send(sender, "holograms.engine-all-capabilities");
        } else {
            messages.send(sender, "holograms.engine-capabilities-missing",
                    Msg.unparsed("missing", EngineCapabilities.describe(capabilities.missing())));
        }
        messages.send(sender, "holograms.engine-available",
                Msg.unparsed("engines", String.join(", ", engines.availableIds())));
        String fallback = engines.fallbackReason();
        if (!fallback.isEmpty()) {
            messages.send(sender, "holograms.engine-fallback-note", Msg.component("reason", reason(sender, fallback)));
        }
        return Cmds.OK;
    }

    private Component reason(CommandSender sender, String code) {
        String key = switch (code) {
            case EngineSelection.PLUGIN_MISSING -> "holograms.engine-reason-plugin-missing";
            case EngineSelection.FOLIA_UNSUPPORTED -> "holograms.engine-reason-folia-unsupported";
            case "start-failed" -> "holograms.engine-reason-start-failed";
            case "migration-failed" -> "holograms.engine-reason-migration-failed";
            case EngineSelection.PLUGIN_DISABLED -> "holograms.engine-reason-plugin-disabled";
            default -> "";
        };
        return key.isEmpty() ? Component.text(code) : messages.render(sender, key);
    }

    private int engineMigrate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String fromRaw = StringArgumentType.getString(c, "from");
        String toRaw = StringArgumentType.getString(c, "to");
        EngineKind from = EngineKind.parse(fromRaw).orElseThrow(() -> cmds.fail(c.getSource(),
                "holograms.engine-unknown", Msg.unparsed("engine", fromRaw)));
        EngineKind to = EngineKind.parse(toRaw).orElseThrow(() -> cmds.fail(c.getSource(),
                "holograms.engine-unknown", Msg.unparsed("engine", toRaw)));
        // The holograms are read inside the global task, when the switch starts; edits stored while it runs
        // are buffered by the controller and applied to the new engine afterwards.
        messages.send(sender, "holograms.engine-migrate-start", Msg.number("count", manager.all().size()),
                Msg.unparsed("engine", to.id));
        ctx.scheduler().supplyGlobal(() -> engines.migrate(from, to)).whenComplete((migration, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                if ("same-engine".equals(reason)) {
                    reply(sender, "holograms.engine-same");
                } else if ("migration-running".equals(reason)) {
                    reply(sender, "holograms.engine-migrate-busy");
                } else if (reason.startsWith("unavailable:")) {
                    String[] parts = reason.split(":", 3);
                    reply(sender, "holograms.engine-unavailable",
                            Msg.unparsed("engine", parts.length > 1 ? parts[1] : to.id),
                            Msg.component("reason", reason(sender, parts.length > 2 ? parts[2] : reason)));
                } else {
                    ctx.logger().error("Hologram engine migration failed", cause);
                    reply(sender, "holograms.engine-migrate-failed", Msg.unparsed("reason", reason));
                }
                return;
            }
            reply(sender, "holograms.engine-migrate-done", Msg.number("count", migration.count()),
                    Msg.unparsed("engine", to.id));
            if (!migration.sourceCleaned()) {
                reply(sender, "holograms.engine-migrate-source-kept", Msg.unparsed("engine", from.id));
            }
            reply(sender, "holograms.engine-migrate-config", Msg.unparsed("engine", to.id));
        });
        return Cmds.OK;
    }

    // ---- replies ----

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static TagResolver lazyNumber(String name, AtomicInteger holder) {
        return TagResolver.resolver(name, (args, context) ->
                net.kyori.adventure.text.minimessage.tag.Tag.selfClosingInserting(
                        net.kyori.adventure.text.Component.text(holder.get())));
    }

    private void report(CommandSender sender, CompletableFuture<Outcome> future, String okKey, TagResolver... tags) {
        boolean migratingAtStart = engines.migrating();
        future.whenComplete((outcome, error) -> {
            if (error != null) {
                ctx.logger().error("Saving a hologram change failed", error);
                reply(sender, "holograms.save-failed", tags);
                return;
            }
            String key = switch (outcome) {
                case OK -> okKey;
                case UNCHANGED -> "holograms.unchanged";
                case NOT_FOUND -> "holograms.not-found";
                case EXISTS -> "holograms.exists";
                case NO_SUCH_LINE -> okKey.startsWith("holograms.action") ? "holograms.no-such-action" : "holograms.no-such-line";
                case NO_SUCH_PAGE -> "holograms.no-such-page";
                case LIMIT -> "holograms.limit";
                case INVALID -> "holograms.invalid-name";
            };
            List<TagResolver> all = new ArrayList<>(List.of(tags));
            all.add(Msg.number("max", config.get().maxLinesPerPage));
            all.add(Msg.number("max_pages", config.get().maxPages));
            reply(sender, key, all.toArray(TagResolver[]::new));
            if (outcome == Outcome.OK && (migratingAtStart || engines.migrating())) {
                // Stored, but the world shows it only once the engine switch is done.
                reply(sender, "holograms.engine-migrating-edit");
            }
        });
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> messages.send(player, key, tags), null);
        } else {
            messages.send(sender, key, tags);
        }
    }
}
