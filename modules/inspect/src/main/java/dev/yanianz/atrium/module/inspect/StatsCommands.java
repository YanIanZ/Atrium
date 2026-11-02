package dev.yanianz.atrium.module.inspect;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** {@code /stats} (menu) and {@code /statsedit} (administrative edit of one Bukkit statistic). */
final class StatsCommands {
    static final String PERM_STATS = "atrium.stats";
    static final String PERM_STATS_OTHERS = "atrium.stats.others";
    static final String PERM_STATSEDIT = "atrium.statsedit";

    private record Subtype(Material material, EntityType entity) {}

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<InspectConfig> config;

    StatsCommands(ModuleContext ctx, ConfigHandle<InspectConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("stats", PERM_STATS)
                .executes(this::statsSelf)
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(Cmds.permission(PERM_STATS_OTHERS))
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::statsOther))
                .build(), "Open the statistics menu of a player"));

        ctx.command(CommandSpec.of(Cmds.literal("statsedit", PERM_STATSEDIT)
                .executes(this::usage)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .then(Commands.argument("statistic", StringArgumentType.word())
                                .suggests(Cmds.suggest(StatsCommands::statisticKeys))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("set", "add", "take")))
                                        .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                                .executes(c -> edit(c, null))
                                                .then(Commands.argument("subtype", StringArgumentType.word())
                                                        .suggests(subtypeSuggestions())
                                                        .executes(c -> edit(c,
                                                                StringArgumentType.getString(c, "subtype"))))))))
                .build(), "Set, add or take one Bukkit statistic of an online player"));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "inspect.statsedit-usage");
        return Cmds.OK;
    }

    // ---- /stats ----

    private int statsSelf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player viewer = cmds.player(c.getSource());
        open(viewer, viewer);
        return Cmds.OK;
    }

    private int statsOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player viewer = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "player");
        Player target = visiblePlayer(sender, name);
        if (target == null) {
            throw cmds.fail(c.getSource(), "inspect.stats-offline", Msg.unparsed("player", name));
        }
        open(viewer, target);
        return Cmds.OK;
    }

    private void open(Player viewer, Player target) {
        ctx.scheduler().supplyOnEntity(target,
                        () -> StatsView.read(target, config.get().statsIncludeTypedTotals))
                .whenComplete((rows, error) -> {
                    if (error != null) {
                        report(viewer, "Reading statistics of " + target.getName(), error);
                        return;
                    }
                    onSender(viewer, () -> new StatsMenu(ctx, target.getName(), rows).open(viewer));
                });
    }

    // ---- /statsedit ----

    private int edit(CommandContext<CommandSourceStack> c, String subtypeRaw) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String statisticRaw = StringArgumentType.getString(c, "statistic");
        StatsEdit.Mode mode = StatsEdit.Mode.parse(StringArgumentType.getString(c, "mode"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "inspect.statsedit-invalid-mode"));
        long amount = LongArgumentType.getLong(c, "amount");
        StatisticsCatalog.Entry entry = StatisticsCatalog.byKey(statisticRaw)
                .orElseThrow(() -> cmds.fail(c.getSource(), "inspect.statsedit-unknown",
                        Msg.unparsed("statistic", statisticRaw)));
        Player target = visiblePlayer(sender, name);
        if (target == null) {
            throw cmds.fail(c.getSource(), "inspect.statsedit-offline", Msg.unparsed("player", name));
        }
        Subtype subtype = resolveSubtype(c.getSource(), entry, subtypeRaw);
        Statistic statistic = entry.statistic();
        Material material = subtype == null ? null : subtype.material();
        EntityType entity = subtype == null ? null : subtype.entity();
        ctx.scheduler().supplyOnEntity(target, () -> {
            int before;
            try {
                before = StatsView.readStrict(target, statistic, material, entity);
            } catch (IllegalArgumentException e) {
                return new int[] {-1, -1};
            }
            int after = StatsEdit.apply(before, mode, amount, entry.kind());
            if (!StatsView.write(target, statistic, material, entity, after)) {
                return new int[] {-1, -1};
            }
            return new int[] {before, after};
        }).whenComplete((values, error) -> {
            if (error != null) {
                report(sender, "Editing statistic " + entry.key() + " of " + target.getName(), error);
                return;
            }
            if (values[0] < 0) {
                onSender(sender, () -> ctx.messages().send(sender, "inspect.statsedit-unknown-subtype",
                        Msg.unparsed("subtype", subtypeRaw == null ? "" : subtypeRaw)));
                return;
            }
            onSender(sender, () -> ctx.messages().send(sender, "inspect.statsedit-done",
                    Msg.unparsed("player", target.getName()),
                    Msg.unparsed("statistic", entry.key()),
                    Msg.unparsed("mode", mode.name().toLowerCase(Locale.ROOT)),
                    Msg.number("before", values[0]),
                    Msg.number("after", values[1]),
                    Msg.unparsed("formatted", StatisticsCatalog.format(entry, values[1]))));
        });
        return Cmds.OK;
    }

    /** Validates the optional subtype against the statistic's Bukkit type. */
    private Subtype resolveSubtype(CommandSourceStack source, StatisticsCatalog.Entry entry, String raw)
            throws CommandSyntaxException {
        if (!entry.subtyped()) {
            if (raw != null) {
                throw cmds.fail(source, "inspect.statsedit-no-subtype", Msg.unparsed("statistic", entry.key()));
            }
            return null;
        }
        if (raw == null) {
            throw cmds.fail(source, "inspect.statsedit-needs-subtype", Msg.unparsed("statistic", entry.key()));
        }
        return switch (entry.statistic().getType()) {
            case ITEM -> {
                Material material = material(raw);
                if (material == null) {
                    throw cmds.fail(source, "inspect.statsedit-unknown-subtype", Msg.unparsed("subtype", raw));
                }
                yield new Subtype(material, null);
            }
            case BLOCK -> {
                Material material = material(raw);
                if (material == null) {
                    throw cmds.fail(source, "inspect.statsedit-unknown-subtype", Msg.unparsed("subtype", raw));
                }
                yield new Subtype(material, null);
            }
            case ENTITY -> {
                EntityType type = entityType(raw);
                if (type == null) {
                    throw cmds.fail(source, "inspect.statsedit-unknown-subtype", Msg.unparsed("subtype", raw));
                }
                yield new Subtype(null, type);
            }
            default -> null;
        };
    }

    /**
     * Material by enum name, without {@code Material.matchMaterial}: the legacy lookup may initialize
     * CraftLegacy on the command thread. Namespaced input is accepted by stripping {@code minecraft:}.
     */
    private static Material material(String raw) {
        String wanted = raw.strip();
        if (wanted.toLowerCase(Locale.ROOT).startsWith("minecraft:")) {
            wanted = wanted.substring("minecraft:".length());
        }
        for (Material material : Material.values()) {
            if (material.name().equalsIgnoreCase(wanted)) {
                return material;
            }
        }
        return null;
    }

    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> subtypeSuggestions() {
        return (context, builder) -> {
            String statisticRaw;
            try {
                statisticRaw = StringArgumentType.getString(context, "statistic");
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            Optional<StatisticsCatalog.Entry> entry = StatisticsCatalog.byKey(statisticRaw);
            if (entry.isEmpty()) {
                return builder.buildFuture();
            }
            String remaining = builder.getRemainingLowerCase();
            Statistic statistic = entry.get().statistic();
            List<String> names = new ArrayList<>();
            if (statistic.getType() == Statistic.Type.ENTITY) {
                for (EntityType type : EntityType.values()) {
                    if (type != EntityType.UNKNOWN) {
                        names.add(type.getKey().getKey());
                    }
                }
            } else {
                for (Material material : Material.values()) {
                    // No isBlock()/isItem(): those may initialize CraftLegacy while the player types.
                    names.add(material.name().toLowerCase(Locale.ROOT));
                }
            }
            names.sort(String.CASE_INSENSITIVE_ORDER);
            int suggested = 0;
            for (String candidate : names) {
                if (candidate.startsWith(remaining)) {
                    builder.suggest(candidate);
                    if (++suggested >= 100) {
                        break;
                    }
                }
            }
            return builder.buildFuture();
        };
    }

    private static EntityType entityType(String raw) {
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (EntityType type : EntityType.values()) {
            if (type == EntityType.UNKNOWN) {
                continue;
            }
            String key = type.getKey().getKey();
            if (key.equals(wanted) || type.name().equalsIgnoreCase(raw.strip())) {
                return type;
            }
        }
        return null;
    }

    private static List<String> statisticKeys() {
        return StatisticsCatalog.entries().stream().map(StatisticsCatalog.Entry::key).toList();
    }

    // ---- helpers ----

    /** Hidden vanished players are treated as offline, like the other inspect commands. */
    private Player visiblePlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            return null;
        }
        if (!(sender instanceof Player viewer)) {
            return target;
        }
        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        return vanish == null || vanish.canSee(viewer, target) ? target : null;
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void report(CommandSender sender, String action, Throwable error) {
        ctx.logger().warn("{} failed for {}", action, sender.getName(), error);
        onSender(sender, () -> ctx.messages().send(sender, "core.error"));
    }
}
