package dev.yanianz.atrium.module.npcs;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** {@code /npc}: create, edit and remove NPCs. Every change is stored before it is shown. */
final class NpcCommands {
    private final NpcsModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;

    NpcCommands(NpcsModule module, ModuleContext ctx) {
        this.module = module;
        this.ctx = ctx;
        this.cmds = ctx.commands();
    }

    void register() {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmds.literal("npc", NpcsModule.PERM_ADMIN);
        root.executes(this::usage);
        root.then(Cmds.literal("help").executes(this::usage));
        root.then(Cmds.literal("create")
                .executes(c -> usageLine(c, "/npc create <name> [skin]"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> create(c, null))
                        .then(Commands.argument("skin", StringArgumentType.word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(c -> create(c, StringArgumentType.getString(c, "skin"))))));
        root.then(Cmds.literal("delete")
                .executes(c -> usageLine(c, "/npc delete <name>"))
                .then(name().executes(this::delete)));
        root.then(Cmds.literal("list").executes(c -> list(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1)).suggests(pageSuggestions())
                        .executes(c -> list(c, IntegerArgumentType.getInteger(c, "page")))));
        root.then(Cmds.literal("info").executes(c -> info(c, null))
                .then(name().executes(c -> info(c, StringArgumentType.getString(c, "name")))));
        root.then(Cmds.literal("select").executes(this::select));
        root.then(Cmds.literal("movehere").executes(c -> moveHere(c, "npcs.moved")));
        root.then(Cmds.literal("tphere").executes(c -> moveHere(c, "npcs.moved")));
        root.then(Cmds.literal("skin")
                .executes(c -> usageLine(c, "/npc skin <player|reset>"))
                .then(Commands.argument("skin", StringArgumentType.word())
                        .suggests((c, builder) -> {
                            builder.suggest("reset");
                            return Cmds.knownPlayers(ctx.users()).getSuggestions(c, builder);
                        })
                        .executes(this::skin)));
        root.then(Cmds.literal("name")
                .executes(c -> usageLine(c, "/npc name <text>"))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(this::displayName)));
        root.then(Cmds.literal("equip")
                .executes(c -> usageLine(c, "/npc equip <head|chest|legs|feet|hand|offhand|clear>"))
                .then(Commands.argument("slot", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("head", "chest", "legs", "feet", "hand", "offhand", "clear")))
                        .executes(this::equip)));
        root.then(Cmds.literal("lookclose")
                .executes(this::toggleLook)
                .then(Commands.argument("range", DoubleArgumentType.doubleArg(0, 64))
                        .suggests(Cmds.suggest(() -> List.of("0", "4", "8", "16", "32")))
                        .executes(c -> lookClose(c, DoubleArgumentType.getDouble(c, "range")))));
        root.then(Cmds.literal("glow")
                .executes(c -> glow(c, null))
                .then(Commands.argument("colour", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> {
                            List<String> values = new ArrayList<>(NpcsModule.glowColors());
                            values.add("off");
                            return values;
                        }))
                        .executes(c -> glow(c, StringArgumentType.getString(c, "colour")))));
        root.then(Cmds.literal("action")
                .executes(c -> usageLine(c, "/npc action add|remove|list <left|right> ..."))
                .then(Cmds.literal("add")
                        .executes(c -> usageLine(c, "/npc action add <left|right> <action>"))
                        .then(Commands.argument("click", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("left", "right")))
                                .executes(c -> usageLine(c, "/npc action add <left|right> <action>"))
                                .then(Commands.argument("action", StringArgumentType.greedyString())
                                        .suggests(Cmds.suggest(() -> List.of("console: ", "player: ", "msg: ",
                                                "warp ", "server ", "open dialog ")))
                                        .executes(this::actionAdd))))
                .then(Cmds.literal("remove")
                        .executes(c -> usageLine(c, "/npc action remove <left|right> <index>"))
                        .then(Commands.argument("click", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("left", "right")))
                                .executes(c -> usageLine(c, "/npc action remove <left|right> <index>"))
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .suggests(actionIndexSuggestions())
                                        .executes(this::actionRemove))))
                .then(Cmds.literal("list")
                        .executes(c -> usageLine(c, "/npc action list <left|right>"))
                        .then(Commands.argument("click", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("left", "right")))
                                .executes(this::actionList))));
        root.then(Cmds.literal("permission")
                .executes(c -> usageLine(c, "/npc permission <node|none>"))
                .then(Commands.argument("node", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("none")))
                        .executes(this::permission)));
        root.then(Cmds.literal("cooldown")
                .executes(c -> usageLine(c, "/npc cooldown <0|30s|5m|1h|1d>"))
                .then(Commands.argument("duration", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("0", "30s", "5m", "1h", "1d")))
                        .executes(this::cooldown)));
        root.then(Cmds.literal("cost")
                .executes(c -> usageLine(c, "/npc cost <amount>"))
                .then(Commands.argument("amount", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("0", "10", "100", "1000")))
                        .executes(this::cost)));
        root.then(Cmds.literal("pose")
                .executes(c -> usageOf(c, "npcs.usage-pose"))
                .then(Commands.argument("pose", StringArgumentType.word())
                        .suggests(poseSuggestions())
                        .executes(this::pose)));
        root.then(Cmds.literal("scale")
                .executes(c -> usageOf(c, "npcs.usage-scale"))
                .then(Commands.argument("amount", StringArgumentType.word())
                        .suggests(Cmds.suggest(NpcScale::suggestions))
                        .executes(this::scale)));
        root.then(Cmds.literal("nameplate")
                .executes(c -> usageOf(c, "npcs.usage-nameplate"))
                .then(Cmds.literal("on").executes(c -> nameplate(c, true)))
                .then(Cmds.literal("off").executes(c -> nameplate(c, false)))
                .then(Cmds.literal("text")
                        .executes(c -> usageOf(c, "npcs.usage-nameplate"))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(this::nameplateText))));
        root.then(Cmds.literal("copy")
                .executes(c -> usageOf(c, "npcs.usage-copy"))
                .then(name()
                        .executes(c -> usageOf(c, "npcs.usage-copy"))
                        .then(Commands.argument("newname", StringArgumentType.word())
                                .executes(this::copy))));
        root.then(Cmds.literal("engine").executes(this::engine));
        root.then(Cmds.literal("migrate")
                .executes(c -> usageLine(c, "/npc migrate <from> <to> confirm"))
                .then(Commands.argument("from", StringArgumentType.word())
                        .suggests(engineSuggestions())
                        .executes(c -> usageLine(c, "/npc migrate <from> <to> confirm"))
                        .then(Commands.argument("to", StringArgumentType.word())
                                .suggests(engineSuggestions())
                                .executes(c -> usageLine(c, "/npc migrate <from> <to> confirm"))
                                .then(Cmds.literal("confirm").executes(this::migrate)))));
        ctx.command(new dev.yanianz.atrium.core.command.CommandSpec(root.build(),
                "Create and edit NPCs", List.of("npcs")));
    }

    // ---- arguments ----

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> name() {
        return Commands.argument("name", StringArgumentType.word()).suggests(Cmds.suggest(
                () -> module.definitions().stream().map(NpcDef::name)
                        .sorted(String.CASE_INSENSITIVE_ORDER).toList()));
    }

    private static SuggestionProvider<CommandSourceStack> engineSuggestions() {
        return Cmds.suggest(() -> List.of("auto", "native", "citizens", "fancynpcs"));
    }

    private SuggestionProvider<CommandSourceStack> pageSuggestions() {
        return (c, builder) -> {
            int pages = Math.max(1, (module.definitions().size() + 9) / 10);
            for (int i = 1; i <= pages; i++) {
                builder.suggest(i);
            }
            return builder.buildFuture();
        };
    }

    /** Suggests only the poses the selected NPC's engine can apply. */
    private SuggestionProvider<CommandSourceStack> poseSuggestions() {
        return (c, builder) -> {
            NpcDef def = c.getSource().getSender() instanceof Player player ? module.selected(player) : null;
            NpcType engine = def == null ? module.activeEngine() : module.effectiveType(def);
            String remaining = builder.getRemainingLowerCase();
            for (String pose : NpcPose.suggestions(module.supportedPoses(engine))) {
                if (pose.startsWith(remaining)) {
                    builder.suggest(pose);
                }
            }
            return builder.buildFuture();
        };
    }

    /** Suggests the 1-based action numbers that exist for the click argument already typed. */
    private SuggestionProvider<CommandSourceStack> actionIndexSuggestions() {
        return (c, builder) -> {
            if (c.getSource().getSender() instanceof Player player) {
                NpcDef def = module.selected(player);
                NpcInteractClick click = NpcInteractClick.parse(StringArgumentType.getString(c, "click"))
                        .orElse(null);
                if (def != null && click != null) {
                    for (int i = 1; i <= def.actions(click).size(); i++) {
                        builder.suggest(i);
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    // ---- subcommands ----

    private int usage(CommandContext<CommandSourceStack> c) {
        return usageOf(c, "npcs.usage");
    }

    private int usageOf(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    private int usageLine(CommandContext<CommandSourceStack> c, String usage) {
        ctx.messages().send(c.getSource().getSender(), "npcs.usage-line", Msg.unparsed("usage", usage));
        return Cmds.OK;
    }

    private int create(CommandContext<CommandSourceStack> c, String skin) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        if (!NpcsModule.validName(name)) {
            throw cmds.fail(c.getSource(), "npcs.invalid-name");
        }
        if (module.def(name) != null) {
            throw cmds.fail(c.getSource(), "npcs.exists", Msg.unparsed("npc", name));
        }
        if (skin != null && !NpcSkinResolver.validAccount(skin)) {
            throw cmds.fail(c.getSource(), "npcs.invalid-skin", Msg.unparsed("skin", skin));
        }
        NpcDef def = NpcDef.create(name, "<highlight>" + name, StoredLocation.of(player.getLocation()), skin, NpcType.AUTO)
                .withCooldown(module.defaultCooldownMillis());
        module.persist(def, NpcsModule.Apply.REFRESH, player, "npcs.created",
                stored -> module.select(player, stored), Msg.unparsed("npc", name));
        return Cmds.OK;
    }

    private int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        NpcDef def = named(c, StringArgumentType.getString(c, "name"));
        module.delete(def, c.getSource().getSender());
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c, int page) {
        List<NpcDef> all = module.definitions().stream()
                .sorted(java.util.Comparator.comparing(NpcDef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        int pages = Math.max(1, (all.size() + 9) / 10);
        int safePage = Math.max(1, Math.min(page, pages));
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "npcs.list-header", Msg.number("total", all.size()), Msg.number("page", safePage),
                Msg.number("pages", pages));
        int from = (safePage - 1) * 10;
        for (int i = from; i < Math.min(all.size(), from + 10); i++) {
            NpcDef def = all.get(i);
            ctx.messages().send(sender, "npcs.list-entry",
                    Msg.unparsed("npc", def.name()),
                    Msg.unparsed("engine", module.effectiveType(def).configName()),
                    Msg.unparsed("world", def.world()),
                    Msg.number("x", def.x()), Msg.number("y", def.y()), Msg.number("z", def.z()));
        }
        return Cmds.OK;
    }

    private int info(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        NpcDef def = name == null ? selected(c) : named(c, name);
        CommandSender sender = c.getSource().getSender();
        // The edit suggestions below act on the selected NPC, so an explicit name selects it too.
        if (sender instanceof Player player) {
            module.select(player, def);
        }
        ctx.messages().send(sender, "npcs.info-header", Msg.unparsed("npc", def.name()));
        infoLine(sender, "npcs.info-engine", "/npc migrate ",
                Msg.unparsed("engine", module.effectiveType(def).configName()),
                Msg.unparsed("stored", def.engine().configName()));
        StoredLocation location = def.location();
        infoLine(sender, "npcs.info-location", "/npc movehere",
                Msg.unparsed("world", location.world()),
                Msg.number("x", location.x()), Msg.number("y", location.y()), Msg.number("z", location.z()));
        infoLine(sender, "npcs.info-skin", "/npc skin ",
                Msg.unparsed("skin", def.skin() == null ? "-" : def.skin()));
        infoLine(sender, "npcs.info-equipment", "/npc equip ",
                Msg.unparsed("slots", def.equipment().isEmpty() ? "-" : String.join(", ", def.equipment().keySet())));
        infoLine(sender, "npcs.info-pose", "/npc pose ", Msg.unparsed("pose", def.pose()));
        infoLine(sender, "npcs.info-scale", "/npc scale ", Msg.number("scale", def.scale()));
        infoLine(sender, "npcs.info-nameplate", "/npc nameplate ",
                Msg.component("enabled", state(sender, def.nameplate())),
                Msg.component("mode", nameplateMode(sender)));
        infoLine(sender, "npcs.info-permission", "/npc permission ",
                Msg.unparsed("permission", def.permission() == null ? "-" : def.permission()));
        infoLine(sender, "npcs.info-cooldown", "/npc cooldown ",
                Msg.duration("cooldown", Duration.ofMillis(def.cooldownMillis())));
        infoLine(sender, "npcs.info-cost", "/npc cost ", Msg.unparsed("cost", module.formatCost(def.cost())));
        infoLine(sender, "npcs.info-look", "/npc lookclose ",
                Msg.component("enabled", state(sender, def.lookAt())), Msg.number("range", def.lookRange()));
        infoLine(sender, "npcs.info-glow", "/npc glow ",
                Msg.unparsed("glow", def.glow().isEmpty() ? "-" : def.glow()));
        infoLine(sender, "npcs.info-actions", "/npc action list left ",
                Msg.number("left", def.leftActions().size()), Msg.number("right", def.rightActions().size()));
        return Cmds.OK;
    }

    private void infoLine(CommandSender sender, String key, String suggestion, TagResolver... tags) {
        TagResolver[] resolvers = Arrays.copyOf(tags, tags.length + 1);
        resolvers[tags.length] = Msg.component("edit", editButton(sender, suggestion));
        ctx.messages().send(sender, key, resolvers);
    }

    private Component editButton(CommandSender sender, String suggestion) {
        return ctx.messages().render(sender, "npcs.info-edit-button")
                .clickEvent(ClickEvent.suggestCommand(suggestion))
                .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "npcs.info-edit-hover",
                        Msg.unparsed("command", suggestion))));
    }

    private Component state(CommandSender sender, boolean value) {
        return ctx.messages().render(sender, value ? "npcs.state-on" : "npcs.state-off");
    }

    private Component nameplateMode(CommandSender sender) {
        return ctx.messages().render(sender,
                module.hologramsActive() ? "npcs.nameplate-mode-hologram" : "npcs.nameplate-mode-vanilla");
    }

    private int select(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = module.pickSelect(player);
        if (def == null) {
            ctx.messages().send(player, "npcs.select-none", Msg.number("range", module.selectRange()));
            return Cmds.OK;
        }
        module.select(player, def);
        ctx.messages().send(player, "npcs.selected", Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int moveHere(CommandContext<CommandSourceStack> c, String key) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        NpcDef moved = def.withLocation(StoredLocation.of(player.getLocation()));
        module.persist(moved, NpcsModule.Apply.MOVE, player, key, null, Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int skin(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String value = StringArgumentType.getString(c, "skin");
        String skin;
        if (value.equalsIgnoreCase("reset") || value.equalsIgnoreCase("none")) {
            skin = null;
        } else {
            if (!NpcSkinResolver.validAccount(value)) {
                throw cmds.fail(c.getSource(), "npcs.invalid-skin", Msg.unparsed("skin", value));
            }
            skin = value;
        }
        module.persist(def.withSkin(skin), NpcsModule.Apply.REFRESH, player, "npcs.skin",
                null, Msg.unparsed("skin", skin == null ? "-" : skin), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int displayName(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return displayName(c, "npcs.name");
    }

    private int displayName(CommandContext<CommandSourceStack> c, String successKey) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String text = StringArgumentType.getString(c, "text").trim();
        if (text.isBlank() || text.length() > 512) {
            throw cmds.fail(c.getSource(), "npcs.invalid-name-text");
        }
        try {
            ctx.messages().parseTrusted(text);
        } catch (RuntimeException e) {
            throw cmds.fail(c.getSource(), "npcs.invalid-name-text");
        }
        module.persist(def.withDisplayName(text), NpcsModule.Apply.REFRESH, player, successKey,
                null, Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int pose(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        NpcType engine = module.effectiveType(def);
        requireCapability(c, def, NpcCapabilities.Capability.POSE);
        String value = StringArgumentType.getString(c, "pose");
        List<String> allowed = module.supportedPoses(engine);
        String pose = NpcPose.parse(value).orElseThrow(() -> cmds.fail(c.getSource(), "npcs.invalid-pose",
                Msg.unparsed("poses", String.join(", ", allowed))));
        if (!allowed.contains(pose)) {
            throw cmds.fail(c.getSource(), "npcs.pose-engine",
                    Msg.unparsed("engine", engine.configName()),
                    Msg.unparsed("poses", String.join(", ", allowed)));
        }
        module.persist(def.withPose(pose), NpcsModule.Apply.REFRESH, player, "npcs.pose",
                null, Msg.unparsed("pose", pose), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int scale(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        requireCapability(c, def, NpcCapabilities.Capability.SCALE);
        OptionalDouble value = NpcScale.parse(StringArgumentType.getString(c, "amount"));
        if (value.isEmpty()) {
            throw cmds.fail(c.getSource(), "npcs.invalid-scale",
                    Msg.number("min", NpcScale.MIN), Msg.number("max", NpcScale.MAX));
        }
        module.persist(def.withScale(value.getAsDouble()), NpcsModule.Apply.REFRESH, player, "npcs.scale",
                null, Msg.number("scale", value.getAsDouble()), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int nameplate(CommandContext<CommandSourceStack> c, boolean enabled) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        requireCapability(c, def, NpcCapabilities.Capability.NAMEPLATE);
        module.persist(def.withNameplate(enabled), NpcsModule.Apply.REFRESH, player,
                enabled ? "npcs.nameplate-on" : "npcs.nameplate-off", null,
                Msg.unparsed("npc", def.name()), Msg.component("mode", nameplateMode(c.getSource().getSender())));
        return Cmds.OK;
    }

    private int nameplateText(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        requireCapability(c, selected(c), NpcCapabilities.Capability.NAMEPLATE);
        return displayName(c, "npcs.nameplate-text");
    }

    private int copy(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef source = named(c, StringArgumentType.getString(c, "name"));
        String newName = StringArgumentType.getString(c, "newname");
        if (!NpcsModule.validName(newName)) {
            throw cmds.fail(c.getSource(), "npcs.invalid-name");
        }
        if (module.def(newName) != null) {
            throw cmds.fail(c.getSource(), "npcs.exists", Msg.unparsed("npc", newName));
        }
        NpcDef copy = source.copyAs(newName, StoredLocation.of(player.getLocation()));
        module.persist(copy, NpcsModule.Apply.REFRESH, player, "npcs.copied",
                stored -> module.select(player, stored),
                Msg.unparsed("source", source.name()), Msg.unparsed("npc", newName));
        return Cmds.OK;
    }

    private void requireCapability(CommandContext<CommandSourceStack> c, NpcDef def,
            NpcCapabilities.Capability capability) throws CommandSyntaxException {
        if (module.capability(def, capability) == NpcCapabilities.Support.NO) {
            throw unsupported(c, def, capability);
        }
    }

    private CommandSyntaxException unsupported(CommandContext<CommandSourceStack> c, NpcDef def,
            NpcCapabilities.Capability capability) {
        return cmds.fail(c.getSource(), "npcs.unsupported",
                Msg.unparsed("engine", module.effectiveType(def).configName()),
                Msg.component("feature", ctx.messages().render(c.getSource().getSender(), capability.messageKey())));
    }

    private int equip(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String slot = StringArgumentType.getString(c, "slot").toLowerCase(Locale.ROOT);
        if (slot.equals("clear")) {
            NpcDef next = def;
            for (String value : NpcDef.SLOTS) {
                next = next.withEquipment(value, null);
            }
            module.persist(next, NpcsModule.Apply.REFRESH, player, "npcs.equipment-cleared",
                    null, Msg.unparsed("npc", def.name()));
            return Cmds.OK;
        }
        String mapped = slotName(slot);
        if (mapped == null) {
            throw cmds.fail(c.getSource(), "npcs.invalid-slot");
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String encoded = NpcItems.encode(hand);
        module.persist(def.withEquipment(mapped, encoded), NpcsModule.Apply.REFRESH, player, "npcs.equipped",
                null, Msg.unparsed("slot", mapped), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private static String slotName(String value) {
        return switch (value) {
            case "head", "helmet", "helm" -> "HEAD";
            case "chest", "chestplate" -> "CHEST";
            case "legs", "leggings" -> "LEGS";
            case "feet", "boots" -> "FEET";
            case "hand", "mainhand", "main_hand" -> "HAND";
            case "offhand", "off_hand" -> "OFF_HAND";
            default -> null;
        };
    }

    private int toggleLook(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        NpcDef def = selected(c);
        boolean enable = !def.lookAt();
        return lookClose(c, enable ? Math.max(1, def.lookRange()) : 0);
    }

    private int lookClose(CommandContext<CommandSourceStack> c, double range) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        boolean enabled = range > 0;
        NpcDef next = def.withLook(enabled, enabled ? range : def.lookRange());
        module.persist(next, NpcsModule.Apply.REFRESH, player,
                enabled ? "npcs.look-enabled" : "npcs.look-disabled", null,
                Msg.unparsed("npc", def.name()), Msg.number("range", range));
        return Cmds.OK;
    }

    private int glow(CommandContext<CommandSourceStack> c, String value) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String color;
        if (value == null) {
            color = def.glow().isEmpty() ? "white" : "";
        } else if (value.equalsIgnoreCase("off") || value.equalsIgnoreCase("none")) {
            color = "";
        } else {
            color = value.toLowerCase(Locale.ROOT);
            if (!NpcsModule.glowColors().contains(color)) {
                throw cmds.fail(c.getSource(), "npcs.invalid-glow");
            }
        }
        if (!color.isEmpty() && !color.equals("white")
                && module.capability(def, NpcCapabilities.Capability.GLOW_COLOUR) != NpcCapabilities.Support.YES) {
            throw unsupported(c, def, NpcCapabilities.Capability.GLOW_COLOUR);
        }
        module.persist(def.withGlow(color), NpcsModule.Apply.REFRESH, player, "npcs.glow",
                null, Msg.unparsed("npc", def.name()), Msg.unparsed("glow", color.isEmpty() ? "off" : color));
        return Cmds.OK;
    }

    private int actionAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        NpcInteractClick click = click(c);
        String raw = StringArgumentType.getString(c, "action").trim();
        Optional<String> problem = module.actionProblem(raw);
        if (problem.isPresent()) {
            throw cmds.fail(c.getSource(), "npcs.action-invalid", Msg.unparsed("problem", problem.get()));
        }
        List<String> lines = new ArrayList<>(def.actions(click));
        if (lines.size() >= module.maxActions()) {
            throw cmds.fail(c.getSource(), "npcs.action-limit", Msg.number("max", module.maxActions()));
        }
        lines.add(raw);
        module.persist(def.withActions(click, lines), NpcsModule.Apply.NONE, player, "npcs.action-added",
                null, Msg.number("index", lines.size()), Msg.unparsed("click", click.configName()),
                Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int actionRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        NpcInteractClick click = click(c);
        int index = IntegerArgumentType.getInteger(c, "index");
        List<String> lines = new ArrayList<>(def.actions(click));
        if (index < 1 || index > lines.size()) {
            throw cmds.fail(c.getSource(), "npcs.action-missing");
        }
        String removed = lines.remove(index - 1);
        module.persist(def.withActions(click, lines), NpcsModule.Apply.NONE, player, "npcs.action-removed",
                null, Msg.unparsed("line", removed), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int actionList(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        NpcDef def = selected(c);
        NpcInteractClick click = click(c);
        List<String> lines = def.actions(click);
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "npcs.action-header", Msg.unparsed("npc", def.name()),
                Msg.unparsed("click", click.configName()), Msg.number("count", lines.size()));
        for (int i = 0; i < lines.size(); i++) {
            ctx.messages().send(sender, "npcs.action-entry", Msg.number("index", i + 1),
                    Msg.unparsed("line", lines.get(i)));
        }
        return Cmds.OK;
    }

    private NpcInteractClick click(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String value = StringArgumentType.getString(c, "click");
        return NpcInteractClick.parse(value)
                .orElseThrow(() -> cmds.fail(c.getSource(), "npcs.invalid-click"));
    }

    private int permission(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String value = StringArgumentType.getString(c, "node");
        String node;
        if (value.equalsIgnoreCase("none")) {
            node = null;
        } else {
            if (!value.matches("[A-Za-z0-9_.*\\-]{1,128}")) {
                throw cmds.fail(c.getSource(), "npcs.invalid-permission");
            }
            node = value;
        }
        module.persist(def.withPermission(node), NpcsModule.Apply.NONE, player, "npcs.permission",
                null, Msg.unparsed("permission", node == null ? "-" : node), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int cooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String value = StringArgumentType.getString(c, "duration");
        long millis;
        if (value.equals("0") || value.equalsIgnoreCase("none")) {
            millis = 0;
        } else {
            Optional<Duration> parsed = Durations.parse(value);
            if (parsed.isEmpty()) {
                throw cmds.fail(c.getSource(), "core.invalid-duration", Msg.unparsed("input", value));
            }
            millis = Math.min(parsed.get().toMillis(), Duration.ofDays(365).toMillis());
        }
        module.persist(def.withCooldown(millis), NpcsModule.Apply.NONE, player, "npcs.cooldown",
                null, Msg.duration("cooldown", Duration.ofMillis(millis)), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int cost(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = selected(c);
        String value = StringArgumentType.getString(c, "amount");
        BigDecimal amount;
        try {
            amount = new BigDecimal(value);
        } catch (NumberFormatException e) {
            throw cmds.fail(c.getSource(), "npcs.invalid-cost");
        }
        if (amount.signum() < 0 || amount.compareTo(new BigDecimal("1000000000")) > 0) {
            throw cmds.fail(c.getSource(), "npcs.invalid-cost");
        }
        module.persist(def.withCost(amount), NpcsModule.Apply.NONE, player, "npcs.cost",
                null, Msg.unparsed("cost", module.formatCost(amount)), Msg.unparsed("npc", def.name()));
        return Cmds.OK;
    }

    private int engine(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "npcs.engine-header");
        ctx.messages().send(sender, "npcs.engine-configured",
                Msg.unparsed("engine", module.configuredEngine().configName()));
        ctx.messages().send(sender, "npcs.engine-active",
                Msg.unparsed("engine", module.activeEngine().configName()));
        ctx.messages().send(sender, "npcs.engine-available",
                Msg.unparsed("native", "yes"),
                Msg.unparsed("citizens", module.citizensAvailable() ? "yes" : "no"),
                Msg.unparsed("fancy", module.fancyAvailable() ? "yes" : "no"));
        long nativeCount = module.definitions().stream()
                .filter(def -> module.effectiveType(def) == NpcType.NATIVE).count();
        long citizensCount = module.definitions().stream()
                .filter(def -> module.effectiveType(def) == NpcType.CITIZENS).count();
        long fancyCount = module.definitions().stream()
                .filter(def -> module.effectiveType(def) == NpcType.FANCYNPC).count();
        ctx.messages().send(sender, "npcs.engine-counts", Msg.number("native", nativeCount),
                Msg.number("citizens", citizensCount), Msg.number("fancy", fancyCount));
        ctx.messages().send(sender, "npcs.engine-capabilities");
        for (NpcCapabilities.Capability capability : NpcCapabilities.all()) {
            ctx.messages().send(sender, "npcs.engine-capability",
                    Msg.component("capability", ctx.messages().render(sender, capability.messageKey())),
                    Msg.component("native", support(sender, NpcType.NATIVE, capability)),
                    Msg.component("citizens", support(sender, NpcType.CITIZENS, capability)),
                    Msg.component("fancy", support(sender, NpcType.FANCYNPC, capability)));
        }
        ctx.messages().send(sender, "npcs.engine-holograms",
                Msg.component("state", state(sender, module.hologramsActive())));
        return Cmds.OK;
    }

    private Component support(CommandSender sender, NpcType type, NpcCapabilities.Capability capability) {
        return ctx.messages().render(sender, NpcCapabilities.support(type, capability).messageKey());
    }

    private int migrate(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        NpcType from = NpcType.parse(StringArgumentType.getString(c, "from"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "npcs.invalid-engine"));
        NpcType to = NpcType.parse(StringArgumentType.getString(c, "to"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "npcs.invalid-engine"));
        if (from == NpcType.AUTO) {
            from = module.activeEngine();
        }
        if (to == NpcType.AUTO) {
            throw cmds.fail(c.getSource(), "npcs.migrate-to-auto");
        }
        if (from == to) {
            throw cmds.fail(c.getSource(), "npcs.migrate-same");
        }
        if (!NpcEngines.usable(to, module.citizensAvailable(), module.fancyAvailable())) {
            throw cmds.fail(c.getSource(), "npcs.backend-unavailable", Msg.unparsed("engine", to.configName()));
        }
        module.migrate(from, to, c.getSource().getSender());
        return Cmds.OK;
    }

    // ---- helpers ----

    private NpcDef selected(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        NpcDef def = module.selected(player);
        if (def == null) {
            throw cmds.fail(c.getSource(), "npcs.not-selected");
        }
        return def;
    }

    private NpcDef named(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        NpcDef def = module.def(name);
        if (def == null) {
            throw cmds.fail(c.getSource(), "npcs.unknown", Msg.unparsed("npc", name));
        }
        return def;
    }
}
