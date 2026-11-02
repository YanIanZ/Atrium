package dev.yanianz.atrium.module.moderation;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.yanianz.atrium.api.moderation.PunishResult;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentRequest;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.moderation.RevokeResult;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Every moderation command. Commands resolve their target asynchronously and never block the sender's thread. */
final class ModerationCommands {
    static final String PERM = "atrium.moderation.";
    private static final Pattern JAIL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final ConfigHandle<ModerationConfig> config;
    private final PunishmentManager manager;

    ModerationCommands(ModuleContext ctx, ConfigHandle<ModerationConfig> config, PunishmentManager manager) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.config = config;
        this.manager = manager;
    }

    /** A resolved account. */
    private record Target(UUID uuid, String name) {}

    void register() {
        // Account punishments: /<cmd> <player> [reason...]  and  /<cmd> <player> <duration> [reason...]
        ctx.command(spec(punishRoot("ban", PunishmentType.BAN, false), "Ban a player permanently"));
        ctx.command(spec(punishRoot("tempban", PunishmentType.TEMPBAN, true), "Ban a player for a time"));
        ctx.command(spec(punishRoot("mute", PunishmentType.MUTE, false), "Mute a player permanently"));
        ctx.command(spec(punishRoot("tempmute", PunishmentType.TEMPMUTE, true), "Mute a player for a time"));
        ctx.command(spec(punishRoot("warn", PunishmentType.WARN, false), "Warn a player"));
        ctx.command(spec(Cmds.literal("kick", PERM + "kick")
                .executes(usage("moderation.usage.kick"))
                .then(player("player").executes(this::kick)
                        .then(reason().executes(this::kick))), "Kick a player"));
        ctx.command(spec(Cmds.literal("jail", PERM + "jail")
                .executes(usage("moderation.usage.jail"))
                .then(player("player").executes(this::jail)
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(Cmds.suggest(manager.jails()::names)).executes(this::jail))), "Jail a player"));
        ctx.command(spec(Cmds.literal("freeze", PERM + "freeze")
                .executes(usage("moderation.usage.freeze"))
                .then(player("player").executes(this::freeze)
                        .then(reason().executes(this::freeze))), "Freeze or unfreeze a player"));

        // IP bans: target is a player name, an address or a CIDR range.
        ctx.command(spec(Cmds.literal("ipban", PERM + "ipban")
                .executes(usage("moderation.usage.ipban"))
                .then(Commands.argument("input", StringArgumentType.greedyString()).suggests(this::suggestPlayers)
                        .executes(this::ipban)), "Ban an IP address or range"));
        ctx.command(spec(Cmds.literal("tempipban", PERM + "tempipban")
                .executes(usage("moderation.usage.tempipban"))
                .then(Commands.argument("input", StringArgumentType.word()).suggests(this::suggestPlayers)
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("30m", "1h", "6h", "1d", "7d", "30d")))
                                .executes(this::tempipban)
                                .then(reason().executes(this::tempipban)))), "Temporarily ban an IP address or range"));
        ctx.command(spec(Cmds.literal("unbanip", PERM + "unbanip")
                .executes(usage("moderation.usage.unbanip"))
                .then(Commands.argument("input", StringArgumentType.greedyString()).suggests(this::suggestPlayers)
                        .executes(this::unbanip)), "Lift an IP ban"));

        // Lifting.
        ctx.command(spec(revokeRoot("unban", PunishmentType.BANS), "Lift a ban"));
        ctx.command(spec(revokeRoot("unmute", PunishmentType.MUTES), "Lift a mute"));
        ctx.command(spec(revokeRoot("unjail", PunishmentManager.JAIL), "Release a player from jail"));
        ctx.command(spec(revokeRoot("clearwarns", EnumSet.of(PunishmentType.WARN)), "Remove every warning of a player"));
        ctx.command(spec(Cmds.literal("delwarn", PERM + "delwarn")
                .executes(usage("moderation.usage.delwarn"))
                .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(this::delwarn)
                        .then(reason().executes(this::delwarn))), "Remove one warning"));

        // Queries.
        ctx.command(spec(Cmds.literal("warnings", PERM + "warnings")
                .executes(this::warnings)
                .then(player("player").executes(this::warnings)), "List active warnings"));
        ctx.command(spec(Cmds.literal("history", PERM + "history")
                .executes(usage("moderation.usage.history"))
                .then(player("player").executes(this::history)
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .suggests(historyPages()).executes(this::history))
                        .then(Commands.literal("revoke")
                                .then(id("id").suggests(historyRevokeIds()).executes(c -> historyRevoke(c, false))
                                        .then(Commands.literal("confirm")
                                                .executes(c -> historyRevoke(c, true)))))),
                "Show the punishment history of a player"));
        ctx.command(spec(Cmds.literal("editwarnings", PERM + "editwarnings")
                .executes(this::editWarningsUsage)
                .then(player("player").executes(this::editWarnings)
                        .then(Commands.literal("list").executes(this::editWarnings))
                        .then(Commands.literal("remove")
                                .then(id("id").suggests(warningIds()).executes(c -> editWarningRemove(c, false))
                                        .then(Commands.literal("confirm").executes(c -> editWarningRemove(c, true)))))
                        .then(Commands.literal("reason")
                                .then(id("id").suggests(warningIds()).then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(this::editWarningReason))))
                        .then(Commands.literal("expiry")
                                .then(id("id").suggests(warningIds()).then(Commands.argument("expiry", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("1h", "1d", "7d", "30d", "permanent")))
                                        .executes(this::editWarningExpiry))))
                        .then(Commands.literal("clear")
                                .executes(c -> editWarningClear(c, false))
                                .then(Commands.literal("confirm").executes(c -> editWarningClear(c, true))))),
                "Edit the warnings of a player (list, remove, reason, expiry, clear)"));
        ctx.command(spec(Cmds.literal("banlist", PERM + "banlist")
                .executes(c -> listBans(c, PunishmentType.BANS, "banlist"))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .suggests(pageSuggestions(PunishmentType.BANS))
                        .executes(c -> listBans(c, PunishmentType.BANS, "banlist"))), "List active account bans"));
        ctx.command(spec(Cmds.literal("ipbanlist", PERM + "ipbanlist")
                .executes(c -> listBans(c, Set.of(PunishmentType.IPBAN), "ipbanlist"))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .suggests(pageSuggestions(Set.of(PunishmentType.IPBAN)))
                        .executes(c -> listBans(c, Set.of(PunishmentType.IPBAN), "ipbanlist"))), "List active IP bans"));
        ctx.command(spec(Cmds.literal("checkban", PERM + "checkban")
                .executes(usage("moderation.usage.checkban"))
                .then(Commands.argument("input", StringArgumentType.word()).suggests(this::suggestBanInputs)
                        .executes(this::checkban)
                        .then(Commands.literal("edit")
                                .requires(Cmds.permission(PERM + "checkban.modify"))
                                .then(id("id")
                                        .then(Commands.literal("reason")
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(this::checkbanReason)))
                                        .then(Commands.literal("duration")
                                                .then(Commands.argument("value", StringArgumentType.word())
                                                        .suggests(Cmds.suggest(() -> List.of("1h", "1d", "7d", "30d",
                                                                "permanent")))
                                                        .executes(this::checkbanDuration)))))),
                "Show the details of a ban (and edit its reason or duration)"));
        ctx.command(spec(Cmds.literal("seereason", PERM + "checkban")
                .executes(usage("moderation.usage.seereason"))
                .then(Commands.argument("input", StringArgumentType.word()).suggests(this::suggestBanInputs)
                        .executes(this::checkban)), "Show the details of a ban (alias of /checkban)"));
        ctx.command(spec(Cmds.literal("lockip", PERM + "lockip")
                .executes(usage("moderation.usage.lockip"))
                .then(player("player").executes(this::lockipUsage)
                        .then(Commands.literal("add").executes(this::lockAdd)
                                .then(Commands.argument("ip", StringArgumentType.word())
                                        .suggests(this::suggestKnownAddresses).executes(this::lockAdd)))
                        .then(Commands.literal("remove").executes(this::lockipUsage)
                                .then(Commands.argument("ip", StringArgumentType.word())
                                        .suggests(this::suggestLockedAddresses).executes(this::lockRemove)))
                        .then(Commands.literal("list").executes(this::lockList))
                        .then(Commands.literal("clear").executes(this::lockClear))),
                "Restrict the addresses an account may log in from"));

        // Server-wide chat mute.
        ctx.command(spec(Cmds.literal("mutechat", PERM + "mutechat")
                .executes(this::muteChat)
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(Cmds.suggest(() -> List.of("30m", "1h", "6h", "1d", "-s")))
                        .executes(this::muteChat)), "Mute chat for everyone (bypass with " + ModerationListener.MUTECHAT_BYPASS + ")"));
        ctx.command(spec(Cmds.literal("unmutechat", PERM + "unmutechat")
                .executes(this::unmuteChat)
                .then(Commands.argument("args", StringArgumentType.greedyString())
                        .suggests(Cmds.suggest(() -> List.of("-s")))
                        .executes(this::unmuteChat)), "Lift the server-wide chat mute"));

        // Staff notes.
        ctx.command(spec(Cmds.literal("note", PERM + "note")
                .executes(usage("moderation.usage.note"))
                .then(player("player").executes(this::noteList)
                        .then(Commands.literal("add")
                                .requires(Cmds.permission(PERM + "note.add"))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(this::noteAdd)))
                        .then(Commands.literal("list").executes(this::noteList)
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(this::noteList)))
                        .then(Commands.literal("remove")
                                .requires(Cmds.permission(PERM + "note.remove"))
                                .then(id("id").executes(this::noteRemove)))
                        .then(Commands.literal("clear")
                                .requires(Cmds.permission(PERM + "note.remove"))
                                .executes(c -> noteClear(c, false))
                                .then(Commands.literal("confirm").executes(c -> noteClear(c, true))))),
                "Keep internal staff notes about a player"));

        // Jails.
        ctx.command(spec(Cmds.literal("setjail", PERM + "setjail")
                .executes(usage("moderation.usage.setjail"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(manager.jails()::names)).executes(this::setjail)
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 512)).executes(this::setjail))),
                "Create or move a jail at your position"));
        ctx.command(spec(Cmds.literal("deljail", PERM + "deljail")
                .executes(usage("moderation.usage.deljail"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(manager.jails()::names)).executes(this::deljail)), "Delete a jail"));
        ctx.command(spec(Cmds.literal("jails", PERM + "jails").executes(this::jails), "List jails"));
        // /jaillist is the historical alias of /jails and shares its permission.
        ctx.command(spec(Cmds.literal("jaillist", PERM + "jails").executes(this::jails), "List jails (alias of /jails)"));
        ctx.command(spec(Cmds.literal("jailedit", PERM + "jailedit")
                .executes(this::jaileditUsage)
                .then(jailArgument().executes(this::jailInfo)
                        .then(Commands.literal("info").executes(this::jailInfo))
                        .then(Commands.literal("move").executes(this::jailMove))
                        .then(Commands.literal("radius")
                                .then(Commands.argument("radius", DoubleArgumentType.doubleArg(1, 512))
                                        .executes(this::jailRadius)))
                        .then(Commands.literal("release")
                                .executes(this::jailInfo)
                                .then(Commands.literal("here").executes(this::jailReleaseHere))
                                .then(Commands.literal("clear").executes(this::jailReleaseClear)))
                        .then(Commands.literal("allowed")
                                .executes(this::jailAllowedList)
                                .then(Commands.literal("list").executes(this::jailAllowedList))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                                .executes(this::jailAllowedAdd)))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                                .suggests(jailAllowedCommands())
                                                .executes(this::jailAllowedRemove)))
                                .then(Commands.literal("clear").executes(this::jailAllowedClear)))
                        .then(Commands.literal("maxsentence")
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("none", "1h", "1d", "7d", "30d")))
                                        .executes(this::jailMaxSentence)))
                        .then(Commands.literal("cell")
                                .executes(this::jailCellList)
                                .then(Commands.literal("list").executes(this::jailCellList))
                                .then(Commands.literal("add").executes(this::jailCellAdd))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(0))
                                                .executes(this::jailCellRemove)))
                                .then(Commands.literal("clear")
                                        .executes(c -> jailCellClear(c, false))
                                        .then(Commands.literal("confirm").executes(c -> jailCellClear(c, true)))))
                        .then(Commands.literal("chat")
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("true", "false")))
                                        .executes(this::jailChat)))),
                "Edit a jail: location, radius, release point, allowed commands, maximum sentence and chat"));
    }

    private static CommandSpec spec(com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root,
            String description) {
        return CommandSpec.of(root.build(), description);
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> punishRoot(String name,
            PunishmentType type, boolean timed) {
        var player = player("player");
        if (timed) {
            player.then(Commands.argument("duration", StringArgumentType.word())
                    .suggests(Cmds.suggest(() -> List.of("30m", "1h", "6h", "1d", "7d", "30d")))
                    .executes(c -> punish(c, type))
                    .then(reason().executes(c -> punish(c, type))));
        } else {
            player.executes(c -> punish(c, type)).then(reason().executes(c -> punish(c, type)));
        }
        return Cmds.literal(name, PERM + name).executes(usage("moderation.usage." + name)).then(player);
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> revokeRoot(String name,
            Set<PunishmentType> types) {
        return Cmds.literal(name, PERM + name)
                .executes(usage("moderation.usage." + name))
                .then(player("player").executes(c -> revoke(c, types))
                        .then(reason().executes(c -> revoke(c, types))));
    }

    /** Fails with the usage line of a command, so a bare root is never a silent or generic error. */
    private Command<CommandSourceStack> usage(String key) {
        return c -> {
            throw cmds.fail(c.getSource(), key);
        };
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> player(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests(this::suggestPlayers);
    }

    private RequiredArgumentBuilder<CommandSourceStack, Long> id(String name) {
        return Commands.argument(name, LongArgumentType.longArg(1));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> jailArgument() {
        return Commands.argument("jail", StringArgumentType.word())
                .suggests(Cmds.suggest(manager.jails()::names));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> reason() {
        return Commands.argument("reason", StringArgumentType.greedyString())
                .suggests(Cmds.suggest(() -> {
                    List<String> options = new ArrayList<>(config.get().reasonPresets.keySet());
                    options.add("-s");
                    return options;
                }));
    }

    private java.util.concurrent.CompletableFuture<Suggestions> suggestPlayers(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        Set<String> seen = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(remaining) && seen.add(player.getName())) {
                builder.suggest(player.getName());
            }
        }
        for (String name : ctx.users().knownNames(remaining, 20)) {
            if (seen.add(name)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    /** Suggests players and the addresses or ranges that currently have an IP ban. */
    private java.util.concurrent.CompletableFuture<Suggestions> suggestBanInputs(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        suggestPlayers(c, builder);
        String remaining = builder.getRemainingLowerCase();
        return manager.store().activeIpBans(Instant.now()).thenApply(bans -> {
            bans.stream()
                    .map(Punishment::ip)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .filter(ip -> ip.toLowerCase(Locale.ROOT).startsWith(remaining))
                    .forEach(builder::suggest);
            return builder.build();
        }).exceptionally(t -> {
            ctx.logger().debug("Could not suggest banned addresses", t);
            return builder.build();
        });
    }

    /** Suggests the addresses an account is currently locked to. */
    private java.util.concurrent.CompletableFuture<Suggestions> suggestLockedAddresses(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        String name = StringArgumentType.getString(c, "player");
        String remaining = builder.getRemainingLowerCase();
        return resolve(name)
                .thenCompose(found -> found
                        .map(target -> manager.store().lockedAddresses(target.uuid()))
                        .orElseGet(() -> CompletableFuture.completedFuture(List.of())))
                .thenApply(locks -> {
                    locks.stream()
                            .map(PunishmentStore.LockedAddress::address)
                            .filter(ip -> ip.toLowerCase(Locale.ROOT).startsWith(remaining))
                            .forEach(builder::suggest);
                    return builder.build();
                }).exceptionally(t -> {
                    ctx.logger().debug("Could not suggest locked addresses", t);
                    return builder.build();
                });
    }

    /** Suggests the address an account is known by, for {@code /lockip <player> add <ip>}. */
    private java.util.concurrent.CompletableFuture<Suggestions> suggestKnownAddresses(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        String name = StringArgumentType.getString(c, "player");
        String remaining = builder.getRemainingLowerCase();
        return resolve(name)
                .thenCompose(found -> {
                    if (found.isEmpty()) {
                        return CompletableFuture.completedFuture(List.<String>of());
                    }
                    Target target = found.get();
                    Player online = Bukkit.getPlayer(target.uuid());
                    if (online != null) {
                        return CompletableFuture.completedFuture(
                                PunishmentManager.address(online).map(List::of).orElseGet(List::of));
                    }
                    return manager.store().playerInfo(target.uuid()).thenApply(info -> info
                            .map(PunishmentStore.PlayerInfo::lastIp)
                            .flatMap(IpAddresses::normalize)
                            .map(List::of)
                            .orElseGet(List::of));
                })
                .thenApply(addresses -> {
                    addresses.stream()
                            .filter(ip -> ip.toLowerCase(Locale.ROOT).startsWith(remaining))
                            .forEach(builder::suggest);
                    return builder.build();
                }).exceptionally(t -> {
                    ctx.logger().debug("Could not suggest known addresses", t);
                    return builder.build();
                });
    }

    /** Suggests the existing pages of a ban list; a failing count just offers nothing. */
    private SuggestionProvider<CommandSourceStack> pageSuggestions(Set<PunishmentType> types) {
        return (c, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            return manager.store().countActive(types).thenApply(total -> {
                Pagination page = Pagination.of(total, 1, Math.max(1, config.get().pageSize));
                for (int i = 1; i <= page.pages(); i++) {
                    if (Integer.toString(i).startsWith(remaining)) {
                        builder.suggest(i);
                    }
                }
                return builder.build();
            }).exceptionally(t -> {
                ctx.logger().debug("Could not suggest ban list pages", t);
                return builder.build();
            });
        };
    }

    /** Suggests the pages that exist in the history of the player named earlier in the command. */
    private SuggestionProvider<CommandSourceStack> historyPages() {
        return (c, builder) -> {
            String name = StringArgumentType.getString(c, "player");
            String remaining = builder.getRemainingLowerCase();
            return resolve(name)
                    .thenCompose(found -> found
                            .map(target -> manager.historyCount(target.uuid()))
                            .orElseGet(() -> CompletableFuture.completedFuture(0)))
                    .thenApply(total -> {
                        Pagination page = Pagination.of(total, 1, Math.max(1, config.get().pageSize));
                        for (int i = 1; i <= page.pages(); i++) {
                            if (Integer.toString(i).startsWith(remaining)) {
                                builder.suggest(i);
                            }
                        }
                        return builder.build();
                    }).exceptionally(t -> {
                        ctx.logger().debug("Could not suggest history pages", t);
                        return builder.build();
                    });
        };
    }

    /** Suggests the ids of the active warnings of the player named earlier in the command. */
    private SuggestionProvider<CommandSourceStack> warningIds() {
        return (c, builder) -> {
            String name = StringArgumentType.getString(c, "player");
            String remaining = builder.getRemainingLowerCase();
            return resolve(name)
                    .thenCompose(found -> found
                            .map(target -> manager.active(target.uuid()))
                            .orElseGet(() -> CompletableFuture.completedFuture(List.of())))
                    .thenApply(active -> {
                        active.stream()
                                .filter(p -> p.type() == PunishmentType.WARN)
                                .map(p -> Long.toString(p.id()))
                                .filter(id -> id.startsWith(remaining))
                                .forEach(builder::suggest);
                        return builder.build();
                    }).exceptionally(t -> {
                        ctx.logger().debug("Could not suggest warning ids", t);
                        return builder.build();
                    });
        };
    }

    /** Suggests the ids of revocable rows on the first history page of the player named earlier. */
    private SuggestionProvider<CommandSourceStack> historyRevokeIds() {
        return (c, builder) -> {
            String name = StringArgumentType.getString(c, "player");
            String remaining = builder.getRemainingLowerCase();
            Instant now = Instant.now();
            return resolve(name)
                    .thenCompose(found -> found
                            .map(target -> manager.history(target.uuid(), 0, Math.max(1, config.get().pageSize)))
                            .orElseGet(() -> CompletableFuture.completedFuture(List.of())))
                    .thenApply(rows -> {
                        rows.stream()
                                .filter(p -> HistoryView.revocable(p, now))
                                .map(p -> Long.toString(p.id()))
                                .filter(id -> id.startsWith(remaining))
                                .forEach(builder::suggest);
                        return builder.build();
                    }).exceptionally(t -> {
                        ctx.logger().debug("Could not suggest revocable punishment ids", t);
                        return builder.build();
                    });
        };
    }

    /** Suggests the commands currently allowed in the jail named earlier in the command. */
    private SuggestionProvider<CommandSourceStack> jailAllowedCommands() {
        return (c, builder) -> {
            String jailName = StringArgumentType.getString(c, "jail");
            String remaining = builder.getRemainingLowerCase();
            manager.jails().get(jailName).ifPresent(jail -> jail.allowedCommands().stream()
                    .filter(command -> command.startsWith(remaining))
                    .forEach(builder::suggest));
            return builder.buildFuture();
        };
    }

    // ---- punish ----

    private int punish(CommandContext<CommandSourceStack> c, PunishmentType type) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        Duration duration = null;
        if (Cmds.hasArgument(c, "duration")) {
            String input = StringArgumentType.getString(c, "duration");
            duration = Durations.parse(input).filter(d -> !d.isZero()).orElseThrow(() ->
                    cmds.fail(c.getSource(), "core.invalid-duration", Msg.unparsed("input", input)));
        } else if (type == PunishmentType.WARN) {
            duration = warningLifetime();
        }
        ArgParser.Parsed parsed = flags(c, optional(c, "reason"), type == PunishmentType.MUTE || type == PunishmentType.TEMPMUTE);
        PunishmentType finalType = parsed.shadow() ? PunishmentType.SHADOWMUTE : type;
        String reason = reason(parsed.text());
        Duration finalDuration = duration;
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            if (isSelf(sender, t)) {
                messages.send(sender, "moderation.self");
                return CompletableFuture.completedFuture(null);
            }
            PunishmentRequest request = new PunishmentRequest(finalType, t.uuid(), t.name(), null,
                    PunishmentManager.actorId(sender), PunishmentManager.actorName(sender), reason, finalDuration,
                    parsed.silent(), null);
            return manager.punish(request, sender).thenAccept(result -> report(sender, result, t.name()));
        }).exceptionally(t -> failed(sender, "/" + type.name().toLowerCase(Locale.ROOT), t));
        return Cmds.OK;
    }

    private int kick(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            throw cmds.fail(c.getSource(), "core.player-not-found");
        }
        if (target.equals(sender)) {
            throw cmds.fail(c.getSource(), "moderation.self");
        }
        ArgParser.Parsed parsed = flags(c, optional(c, "reason"), false);
        PunishmentRequest request = new PunishmentRequest(PunishmentType.KICK, target.getUniqueId(), target.getName(), null,
                PunishmentManager.actorId(sender), PunishmentManager.actorName(sender), reason(parsed.text()), null,
                parsed.silent(), null);
        manager.punish(request, sender).thenAccept(result -> report(sender, result, target.getName()))
                .exceptionally(t -> failed(sender, "/kick", t));
        return Cmds.OK;
    }

    private int jail(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ArgParser.Parsed parsed = flags(c, optional(c, "args"), false);
        ArgParser.JailArgs args = ArgParser.jailArgs(parsed.words(), manager.jails().names());
        if (manager.jails().resolve(args.jail(), config.get().jail.defaultJail).isEmpty()) {
            throw cmds.fail(c.getSource(), "moderation.jail.none");
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            if (isSelf(sender, t)) {
                messages.send(sender, "moderation.self");
                return CompletableFuture.completedFuture(null);
            }
            PunishmentRequest request = new PunishmentRequest(PunishmentType.JAIL, t.uuid(), t.name(), null,
                    PunishmentManager.actorId(sender), PunishmentManager.actorName(sender), reason(args.reason()),
                    args.duration(), parsed.silent(), args.jail());
            return manager.punish(request, sender).thenAccept(result -> report(sender, result, t.name()));
        }).exceptionally(t -> failed(sender, "/jail", t));
        return Cmds.OK;
    }

    private int freeze(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ArgParser.Parsed parsed = flags(c, optional(c, "reason"), false);
        String reason = reason(parsed.text());
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            if (isSelf(sender, t)) {
                messages.send(sender, "moderation.self");
                return CompletableFuture.completedFuture(null);
            }
            // Toggle: read storage so that a freeze of an offline player is seen too.
            return manager.active(t.uuid()).thenCompose(active -> {
                boolean frozen = active.stream().anyMatch(p -> p.type() == PunishmentType.FREEZE);
                if (frozen) {
                    return manager.revoke(t.uuid(), PunishmentManager.FREEZE, PunishmentManager.actorId(sender),
                            PunishmentManager.actorName(sender), parsed.silent(), sender)
                            .thenAccept(result -> reportRevoke(sender, result, t.name(), "moderation.not-frozen"));
                }
                PunishmentRequest request = new PunishmentRequest(PunishmentType.FREEZE, t.uuid(), t.name(), null,
                        PunishmentManager.actorId(sender), PunishmentManager.actorName(sender), reason, null,
                        parsed.silent(), null);
                return manager.punish(request, sender).thenAccept(result -> report(sender, result, t.name()));
            });
        }).exceptionally(t -> failed(sender, "/freeze", t));
        return Cmds.OK;
    }

    private int ipban(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        ArgParser.Parsed parsed = flags(c, StringArgumentType.getString(c, "input"), false);
        if (parsed.words().isEmpty()) {
            throw cmds.fail(c.getSource(), "moderation.usage.ipban");
        }
        String first = parsed.words().getFirst();
        String reason = reason(String.join(" ", parsed.words().subList(1, parsed.words().size())));
        UUID actor = PunishmentManager.actorId(sender);
        String actorName = PunishmentManager.actorName(sender);
        Optional<String> range = IpAddresses.normalizeRange(first);
        if (range.isPresent()) {
            PunishmentRequest request = new PunishmentRequest(PunishmentType.IPBAN, null, null, range.get(), actor,
                    actorName, reason, null, parsed.silent(), null);
            manager.punish(request, sender).thenAccept(result -> report(sender, result, range.get()))
                    .exceptionally(t -> failed(sender, "/ipban", t));
            return Cmds.OK;
        }
        resolve(first).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", first));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            if (isSelf(sender, t)) {
                messages.send(sender, "moderation.self");
                return CompletableFuture.completedFuture(null);
            }
            PunishmentRequest request = new PunishmentRequest(PunishmentType.IPBAN, t.uuid(), t.name(), null, actor,
                    actorName, reason, null, parsed.silent(), null);
            return manager.punish(request, sender).thenAccept(result -> report(sender, result, t.name()));
        }).exceptionally(t -> failed(sender, "/ipban", t));
        return Cmds.OK;
    }

    private int tempipban(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String input = StringArgumentType.getString(c, "input");
        String durationText = StringArgumentType.getString(c, "duration");
        Duration duration = ArgParser.parseDuration(durationText).orElseThrow(() ->
                cmds.fail(c.getSource(), "core.invalid-duration", Msg.unparsed("input", durationText)));
        ArgParser.Parsed parsed = flags(c, optional(c, "reason"), false);
        String reason = reason(parsed.text());
        UUID actor = PunishmentManager.actorId(sender);
        String actorName = PunishmentManager.actorName(sender);
        Optional<String> range = IpAddresses.normalizeRange(input);
        if (range.isPresent()) {
            PunishmentRequest request = new PunishmentRequest(PunishmentType.IPBAN, null, null, range.get(), actor,
                    actorName, reason, duration, parsed.silent(), null);
            manager.punish(request, sender).thenAccept(result -> report(sender, result, range.get()))
                    .exceptionally(t -> failed(sender, "/tempipban", t));
            return Cmds.OK;
        }
        resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", input));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            if (isSelf(sender, t)) {
                messages.send(sender, "moderation.self");
                return CompletableFuture.completedFuture(null);
            }
            // A temporary address ban linked to the account: the manager fills in the last known address.
            PunishmentRequest request = new PunishmentRequest(PunishmentType.IPBAN, t.uuid(), t.name(), null, actor,
                    actorName, reason, duration, parsed.silent(), null);
            return manager.punish(request, sender).thenAccept(result -> report(sender, result, t.name()));
        }).exceptionally(t -> failed(sender, "/tempipban", t));
        return Cmds.OK;
    }

    // ---- revoke ----

    private int revoke(CommandContext<CommandSourceStack> c, Set<PunishmentType> types) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ArgParser.Parsed parsed = flags(c, optional(c, "reason"), false);
        String notActiveKey = types.contains(PunishmentType.BAN) ? "moderation.not-banned"
                : types.contains(PunishmentType.MUTE) ? "moderation.not-muted"
                : types.contains(PunishmentType.JAIL) ? "moderation.not-jailed"
                : "moderation.no-warnings";
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.revoke(t.uuid(), types, PunishmentManager.actorId(sender), PunishmentManager.actorName(sender),
                    parsed.silent(), sender).thenAccept(result -> reportRevoke(sender, result, t.name(), notActiveKey));
        }).exceptionally(t -> failed(sender, "revoke", t));
        return Cmds.OK;
    }

    private int unbanip(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        ArgParser.Parsed parsed = flags(c, StringArgumentType.getString(c, "input"), false);
        if (parsed.words().isEmpty()) {
            throw cmds.fail(c.getSource(), "moderation.usage.unbanip");
        }
        String first = parsed.words().getFirst();
        UUID actor = PunishmentManager.actorId(sender);
        String actorName = PunishmentManager.actorName(sender);
        if (IpAddresses.isAddressOrRange(first)) {
            manager.revokeIp(first, actor, actorName, parsed.silent(), sender)
                    .thenAccept(result -> reportRevoke(sender, result, first, "moderation.not-ipbanned"))
                    .exceptionally(t -> failed(sender, "/unbanip", t));
            return Cmds.OK;
        }
        resolve(first).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", first));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.revokeIpOf(t.uuid(), actor, actorName, parsed.silent(), sender)
                    .thenAccept(result -> reportRevoke(sender, result, t.name(), "moderation.not-ipbanned"));
        }).exceptionally(t -> failed(sender, "/unbanip", t));
        return Cmds.OK;
    }

    private int delwarn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        long id = LongArgumentType.getLong(c, "id");
        ArgParser.Parsed parsed = flags(c, optional(c, "reason"), false);
        manager.revokeById(id, PunishmentType.WARN, PunishmentManager.actorId(sender), PunishmentManager.actorName(sender),
                        parsed.silent(), sender)
                .thenAccept(result -> {
                    if (result.status() == RevokeResult.Status.NOTHING_ACTIVE) {
                        messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                    } else {
                        reportRevoke(sender, result, "#" + id, "moderation.warning-not-found");
                    }
                })
                .exceptionally(t -> failed(sender, "/delwarn", t));
        return Cmds.OK;
    }

    // ---- queries ----

    private int warnings(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        CompletableFuture<Optional<Target>> target;
        if (Cmds.hasArgument(c, "player")) {
            String name = StringArgumentType.getString(c, "player");
            boolean self = sender instanceof Player p && p.getName().equalsIgnoreCase(name);
            if (!self && !sender.hasPermission(PERM + "warnings.others")) {
                throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", PERM + "warnings.others"));
            }
            target = resolve(name);
        } else {
            Player self = cmds.player(c.getSource());
            target = CompletableFuture.completedFuture(Optional.of(new Target(self.getUniqueId(), self.getName())));
        }
        target.thenCompose(found -> {
            if (found.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", StringArgumentType.getString(c, "player")));
                return CompletableFuture.completedFuture(null);
            }
            Target t = found.get();
            return manager.active(t.uuid()).thenAccept(active -> {
                List<Punishment> warnings = active.stream().filter(p -> p.type() == PunishmentType.WARN).toList();
                if (warnings.isEmpty()) {
                    messages.send(sender, "moderation.warnings.empty", Msg.unparsed("player", t.name()));
                    return;
                }
                messages.send(sender, "moderation.warnings.header", Msg.unparsed("player", t.name()),
                        Msg.number("count", warnings.size()));
                for (Punishment p : warnings) {
                    messages.send(sender, "moderation.warnings.entry", manager.details(p, sender, true));
                }
            });
        }).exceptionally(t -> failed(sender, "/warnings", t));
        return Cmds.OK;
    }

    private int history(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        int page = Cmds.hasArgument(c, "page") ? IntegerArgumentType.getInteger(c, "page") : 1;
        int size = Math.max(1, config.get().pageSize);
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.historyCount(t.uuid()).thenCompose(total -> {
                if (total == 0) {
                    messages.send(sender, "moderation.history.empty", Msg.unparsed("player", t.name()));
                    return CompletableFuture.completedFuture(null);
                }
                Pagination pagination = Pagination.of(total, page, size);
                return manager.history(t.uuid(), pagination.offset(), size).thenAccept(rows -> {
                    messages.send(sender, "moderation.history.header", Msg.unparsed("player", t.name()),
                            Msg.number("page", pagination.current()), Msg.number("pages", pagination.pages()),
                            Msg.number("total", total));
                    Instant now = Instant.now();
                    for (Punishment p : rows) {
                        Component status = messages.render(sender, HistoryView.state(p, now).messageKey(),
                                manager.details(p, sender, true),
                                Msg.component("type-label", typeLabel(sender, p.type())));
                        messages.send(sender, "moderation.history.entry",
                                manager.details(p, sender, true),
                                Msg.component("status", status),
                                Msg.component("type-label", typeLabel(sender, p.type())),
                                Msg.component("actions", historyActions(sender, t.name(), p, now)));
                    }
                    if (pagination.pages() > 1) {
                        messages.send(sender, "moderation.history.footer", Msg.unparsed("player", t.name()),
                                Msg.number("page", pagination.current()), Msg.number("pages", pagination.pages()),
                                Msg.component("prev", pageButton(sender, t.name(),
                                        Math.max(1, pagination.current() - 1), pagination.current() > 1,
                                        "moderation.history.page-prev")),
                                Msg.component("next", pageButton(sender, t.name(),
                                        Math.min(pagination.pages(), pagination.current() + 1),
                                        pagination.current() < pagination.pages(),
                                        "moderation.history.page-next")));
                    }
                });
            });
        }).exceptionally(t -> failed(sender, "/history", t));
        return Cmds.OK;
    }

    /** A clickable "revoke" button for every row the sender is allowed to lift. */
    private Component historyActions(CommandSender sender, String playerName, Punishment punishment, Instant now) {
        if (!HistoryView.revocable(punishment, now) || !mayRevoke(sender, punishment.type())) {
            return Component.empty();
        }
        return messages.render(sender, "moderation.history.action-revoke", Msg.number("id", punishment.id()))
                .clickEvent(ClickEvent.runCommand("/history " + playerName + " revoke " + punishment.id()))
                .hoverEvent(HoverEvent.showText(messages.render(sender, "moderation.history.action-revoke-hover",
                        manager.details(punishment, sender, true))));
    }

    private Component pageButton(CommandSender sender, String playerName, int page, boolean enabled, String key) {
        if (!enabled) {
            return Component.empty();
        }
        return messages.render(sender, key)
                .clickEvent(ClickEvent.runCommand("/history " + playerName + " " + page))
                .hoverEvent(HoverEvent.showText(messages.render(sender, "moderation.history.page-hover",
                        Msg.number("page", page))));
    }

    private int historyRevoke(CommandContext<CommandSourceStack> c, boolean confirm) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        if (!EditorInput.validId(id)) {
            messages.send(sender, "moderation.history.not-revocable", Msg.number("id", id));
            return Cmds.OK;
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.store().byId(id).thenCompose(found -> {
                Optional<Punishment> matching = found.filter(p -> t.uuid().equals(p.target()));
                if (matching.isEmpty() || !HistoryView.revocable(matching.get(), Instant.now())) {
                    messages.send(sender, "moderation.history.not-revocable", Msg.number("id", id));
                    return CompletableFuture.completedFuture(null);
                }
                Punishment p = matching.get();
                // Viewing the history is not permission to lift a punishment: check the same node the
                // dedicated /unban, /unmute, /unjail, /unbanip, /freeze and /delwarn commands use, before
                // the prompt and again on the confirming click.
                if (!mayRevoke(sender, p.type())) {
                    messages.send(sender, "core.no-permission-others",
                            Msg.unparsed("permission", HistoryView.revokePermission(p.type())));
                    return CompletableFuture.completedFuture(null);
                }
                if (!confirm) {
                    Component button = messages.render(sender, "moderation.history.confirm-button")
                            .clickEvent(ClickEvent.runCommand("/history " + t.name() + " revoke " + id + " confirm"))
                            .hoverEvent(HoverEvent.showText(messages.render(sender, "moderation.history.confirm-hover",
                                    Msg.number("id", id))));
                    messages.send(sender, "moderation.history.revoke-confirm",
                            Msg.number("id", id), Msg.unparsed("player", t.name()),
                            Msg.component("type-label", typeLabel(sender, p.type())),
                            Msg.component("confirm", button));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.revokeById(id, (PunishmentType) null, PunishmentManager.actorId(sender),
                                PunishmentManager.actorName(sender), false, sender)
                        .thenAccept(result -> {
                            if (result.success()) {
                                return; // the summary and broadcast already tell the issuer
                            }
                            if (result.status() == RevokeResult.Status.CANCELLED) {
                                messages.send(sender, "moderation.cancelled");
                            } else if (result.status() == RevokeResult.Status.FAILED) {
                                messages.send(sender, "core.error");
                            } else {
                                messages.send(sender, "moderation.history.not-revocable", Msg.number("id", id));
                            }
                        });
            });
        }).exceptionally(t -> failed(sender, "/history revoke", t));
        return Cmds.OK;
    }

    private Component typeLabel(Audience audience, PunishmentType type) {
        return messages.render(audience, HistoryView.typeKey(type));
    }

    /** Whether the sender may lift a record of this kind; the matching revoke command's node decides. */
    private static boolean mayRevoke(CommandSender sender, PunishmentType type) {
        String permission = HistoryView.revokePermission(type);
        return permission != null && sender.hasPermission(permission);
    }

    // ---- /editwarnings ----

    private int editWarningsUsage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "moderation.editwarnings.usage");
    }

    private int editWarnings(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.active(t.uuid()).thenAccept(active -> {
                List<Punishment> warnings = active.stream()
                        .filter(p -> p.type() == PunishmentType.WARN)
                        .toList();
                if (warnings.isEmpty()) {
                    messages.send(sender, "moderation.editwarnings.empty", Msg.unparsed("player", t.name()));
                    return;
                }
                Component clear = sender.hasPermission(PERM + "editwarnings.clear")
                        ? runButton(sender, "moderation.editwarnings.clear-button",
                                "moderation.editwarnings.clear-hover", clearCommand(t.name()))
                        : Component.empty();
                messages.send(sender, "moderation.editwarnings.header", Msg.unparsed("player", t.name()),
                        Msg.number("count", warnings.size()), Msg.component("clear", clear));
                for (Punishment warning : warnings) {
                    messages.send(sender, "moderation.editwarnings.entry", manager.details(warning, sender, true),
                            Msg.component("actions", warningActions(sender, t.name(), warning)));
                }
            });
        }).exceptionally(t -> failed(sender, "/editwarnings", t));
        return Cmds.OK;
    }

    private Component warningActions(CommandSender sender, String playerName, Punishment warning) {
        long id = warning.id();
        Component remove = runButton(sender, "moderation.editwarnings.remove-button",
                "moderation.editwarnings.remove-hover",
                "/editwarnings " + playerName + " remove " + id, Msg.number("id", id));
        Component reason = suggestButton(sender, "moderation.editwarnings.reason-button",
                "moderation.editwarnings.reason-hover",
                "/editwarnings " + playerName + " reason " + id + " ", Msg.number("id", id));
        Component expiry = suggestButton(sender, "moderation.editwarnings.expiry-button",
                "moderation.editwarnings.expiry-hover",
                "/editwarnings " + playerName + " expiry " + id + " ", Msg.number("id", id));
        return Component.text()
                .append(remove).append(Component.text(" "))
                .append(reason).append(Component.text(" "))
                .append(expiry)
                .build();
    }

    private int editWarningRemove(CommandContext<CommandSourceStack> c, boolean confirm) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return activeWarning(t.uuid(), id).thenCompose(warning -> {
                if (warning.isEmpty()) {
                    messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                    return CompletableFuture.completedFuture(null);
                }
                if (!confirm) {
                    Component button = runButton(sender, "moderation.editwarnings.confirm-button",
                            "moderation.editwarnings.remove-hover",
                            "/editwarnings " + t.name() + " remove " + id + " confirm", Msg.number("id", id));
                    messages.send(sender, "moderation.editwarnings.remove-confirm",
                            Msg.number("id", id), Msg.unparsed("player", t.name()),
                            Msg.component("confirm", button));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.revokeById(id, PunishmentType.WARN, PunishmentManager.actorId(sender),
                                PunishmentManager.actorName(sender), false, sender)
                        .thenAccept(result -> {
                            if (result.success()) {
                                messages.send(sender, "moderation.editwarnings.removed",
                                        Msg.number("id", id), Msg.unparsed("player", t.name()));
                            } else if (result.status() == RevokeResult.Status.CANCELLED) {
                                messages.send(sender, "moderation.cancelled");
                            } else if (result.status() == RevokeResult.Status.FAILED) {
                                messages.send(sender, "core.error");
                            } else {
                                messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                            }
                        });
            });
        }).exceptionally(t -> failed(sender, "/editwarnings remove", t));
        return Cmds.OK;
    }

    private int editWarningReason(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        String raw = StringArgumentType.getString(c, "text");
        Optional<String> reason = EditorInput.reason(raw, config.get().maxReasonLength);
        if (reason.isEmpty()) {
            messages.send(sender, "moderation.editwarnings.invalid-reason");
            return Cmds.OK;
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return activeWarning(t.uuid(), id).thenCompose(warning -> {
                if (warning.isEmpty()) {
                    messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.store().updateWarningReason(id, reason.get(), Instant.now()).thenAccept(updated -> {
                    if (updated) {
                        messages.send(sender, "moderation.editwarnings.reason-changed", Msg.number("id", id),
                                Msg.unparsed("player", t.name()), Msg.unparsed("reason", reason.get()));
                    } else {
                        messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                    }
                });
            });
        }).exceptionally(t -> failed(sender, "/editwarnings reason", t));
        return Cmds.OK;
    }

    private int editWarningExpiry(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        String raw = StringArgumentType.getString(c, "expiry");
        Instant now = Instant.now();
        Optional<EditorInput.Expiry> parsed = EditorInput.expiry(raw, now);
        if (parsed.isEmpty()) {
            messages.send(sender, "moderation.editwarnings.invalid-expiry", Msg.unparsed("input", raw));
            return Cmds.OK;
        }
        EditorInput.Expiry expiry = parsed.get();
        Component expires = expiry.permanent()
                ? messages.render(sender, "moderation.permanent")
                : Component.text(manager.formatDate(expiry.expires()));
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return activeWarning(t.uuid(), id).thenCompose(warning -> {
                if (warning.isEmpty()) {
                    messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.store().updateWarningExpiry(id, expiry.expires(), now).thenAccept(updated -> {
                    if (updated) {
                        messages.send(sender, "moderation.editwarnings.expiry-changed", Msg.number("id", id),
                                Msg.unparsed("player", t.name()), Msg.component("expires", expires));
                    } else {
                        messages.send(sender, "moderation.warning-not-found", Msg.number("id", id));
                    }
                });
            });
        }).exceptionally(t -> failed(sender, "/editwarnings expiry", t));
        return Cmds.OK;
    }

    private int editWarningClear(CommandContext<CommandSourceStack> c, boolean confirm) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        if (!sender.hasPermission(PERM + "editwarnings.clear")) {
            messages.send(sender, "core.no-permission-others",
                    Msg.unparsed("permission", PERM + "editwarnings.clear"));
            return Cmds.OK;
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.active(t.uuid()).thenCompose(active -> {
                long count = active.stream().filter(p -> p.type() == PunishmentType.WARN).count();
                if (count == 0) {
                    messages.send(sender, "moderation.no-warnings", Msg.unparsed("player", t.name()));
                    return CompletableFuture.completedFuture(null);
                }
                if (!confirm) {
                    Component button = runButton(sender, "moderation.editwarnings.confirm-button",
                            "moderation.editwarnings.clear-hover", clearCommand(t.name()) + " confirm",
                            Msg.number("count", count));
                    messages.send(sender, "moderation.editwarnings.clear-confirm",
                            Msg.number("count", count), Msg.unparsed("player", t.name()),
                            Msg.component("confirm", button));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.revoke(t.uuid(), EnumSet.of(PunishmentType.WARN), PunishmentManager.actorId(sender),
                                PunishmentManager.actorName(sender), false, sender)
                        .thenAccept(result -> {
                            if (result.success()) {
                                messages.send(sender, "moderation.editwarnings.cleared",
                                        Msg.number("count", result.revoked().size()),
                                        Msg.unparsed("player", t.name()));
                            } else {
                                reportRevoke(sender, result, t.name(), "moderation.no-warnings");
                            }
                        });
            });
        }).exceptionally(t -> failed(sender, "/editwarnings clear", t));
        return Cmds.OK;
    }

    private static String clearCommand(String playerName) {
        return "/editwarnings " + playerName + " clear";
    }

    private CompletableFuture<Optional<Punishment>> activeWarning(UUID target, long id) {
        Instant now = Instant.now();
        return manager.store().byId(id).thenApply(found -> found
                .filter(p -> p.type() == PunishmentType.WARN)
                .filter(p -> target.equals(p.target()))
                .filter(p -> p.isActive(now)));
    }

    // ---- bans: lists and lookup ----

    private int listBans(CommandContext<CommandSourceStack> c, Set<PunishmentType> types, String key) {
        CommandSender sender = c.getSource().getSender();
        int requested = Cmds.hasArgument(c, "page") ? IntegerArgumentType.getInteger(c, "page") : 1;
        int size = Math.max(1, config.get().pageSize);
        manager.store().countActive(types).thenCompose(total -> {
            Pagination page = Pagination.of(total, requested, size);
            if (page.empty()) {
                messages.send(sender, "moderation." + key + ".empty");
                return CompletableFuture.completedFuture(null);
            }
            return manager.store().activeByTypes(types, page.offset(), size).thenAccept(rows -> {
                messages.send(sender, "moderation." + key + ".header", Msg.number("page", page.current()),
                        Msg.number("pages", page.pages()), Msg.number("total", page.total()));
                for (Punishment p : rows) {
                    messages.send(sender, "moderation." + key + ".entry", manager.details(p, sender, true));
                }
            });
        }).exceptionally(t -> failed(sender, "/" + key, t));
        return Cmds.OK;
    }

    private int checkban(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String input = StringArgumentType.getString(c, "input");
        Optional<String> ip = IpAddresses.normalizeRange(input);
        if (ip.isPresent()) {
            checkBanIp(sender, ip.get());
            return Cmds.OK;
        }
        resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", input));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.active(t.uuid()).thenCompose(active -> manager.historyCount(t.uuid()).thenAccept(count -> {
                List<Punishment> bans = active.stream()
                        .filter(p -> PunishmentType.BANS.contains(p.type()) || p.type() == PunishmentType.IPBAN)
                        .toList();
                if (bans.isEmpty()) {
                    messages.send(sender, "moderation.checkban.none", Msg.unparsed("player", t.name()),
                            Msg.number("count", count));
                    return;
                }
                messages.send(sender, "moderation.checkban.header", Msg.unparsed("player", t.name()),
                        Msg.number("count", count));
                bans.forEach(p -> sendBanDetails(sender, p));
            }));
        }).exceptionally(t -> failed(sender, "/checkban", t));
        return Cmds.OK;
    }

    private void checkBanIp(CommandSender sender, String normalizedInput) {
        Instant now = Instant.now();
        manager.store().activeIpBans(now).thenCompose(bans -> {
            List<Punishment> matching = bans.stream()
                    .filter(p -> matchesIpInput(p.ip(), normalizedInput))
                    .toList();
            return manager.store().countIp(normalizedInput).thenAccept(count -> {
                if (matching.isEmpty()) {
                    messages.send(sender, "moderation.checkban.ip-none", Msg.unparsed("player", normalizedInput),
                            Msg.number("count", count));
                    return;
                }
                messages.send(sender, "moderation.checkban.ip-header", Msg.unparsed("player", normalizedInput),
                        Msg.number("count", count));
                matching.forEach(p -> sendBanDetails(sender, p));
            });
        }).exceptionally(t -> failed(sender, "/checkban", t));
    }

    private static boolean matchesIpInput(@Nullable String banned, String input) {
        if (banned == null) {
            return false;
        }
        if (banned.equals(input)) {
            return true;
        }
        String address = input.contains("/") ? null : input;
        if (address != null) {
            return IpAddresses.matches(banned, address);
        }
        return !banned.contains("/") && IpAddresses.matches(input, banned);
    }

    private void sendBanDetails(CommandSender sender, Punishment p) {
        messages.send(sender, "moderation.checkban.entry", manager.details(p, sender));
        messages.send(sender, "moderation.checkban.reason", manager.details(p, sender));
        messages.send(sender, p.expires() == null ? "moderation.checkban.permanent" : "moderation.checkban.expiry",
                manager.details(p, sender));
        if (sender.hasPermission(PERM + "checkban.modify")) {
            messages.send(sender, "moderation.checkban.actions", Msg.component("actions", banActions(sender, p)));
        }
    }

    /** Clickable edit buttons shown to holders of {@code atrium.moderation.checkban.modify}. */
    private Component banActions(CommandSender sender, Punishment p) {
        String subject = p.targetName() != null ? p.targetName() : p.ip() != null ? p.ip() : "?";
        Component reason = suggestButton(sender, "moderation.checkban.action-reason",
                "moderation.checkban.action-reason-hover", "/checkban " + subject + " edit " + p.id() + " reason ",
                Msg.number("id", p.id()));
        Component duration = suggestButton(sender, "moderation.checkban.action-duration",
                "moderation.checkban.action-duration-hover", "/checkban " + subject + " edit " + p.id() + " duration ",
                Msg.number("id", p.id()));
        return Component.text().append(reason).append(Component.text(" ")).append(duration).build();
    }

    // ---- /checkban edit ----

    private int checkbanReason(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String input = StringArgumentType.getString(c, "input");
        long id = LongArgumentType.getLong(c, "id");
        Optional<String> reason = EditorInput.reason(StringArgumentType.getString(c, "text"),
                config.get().maxReasonLength);
        if (reason.isEmpty()) {
            messages.send(sender, "moderation.editwarnings.invalid-reason");
            return Cmds.OK;
        }
        Instant now = Instant.now();
        banRecord(input, id, now).thenCompose(found -> {
            if (found.isEmpty()) {
                messages.send(sender, "moderation.checkban.not-active", Msg.number("id", id));
                return CompletableFuture.completedFuture(null);
            }
            Punishment ban = found.get();
            return manager.store().updateActiveReason(id, reason.get(), now).thenAccept(updated -> {
                if (!updated) {
                    messages.send(sender, "moderation.checkban.not-active", Msg.number("id", id));
                    return;
                }
                refreshBan(ban);
                messages.send(sender, "moderation.checkban.reason-changed", Msg.number("id", id),
                        Msg.unparsed("reason", reason.get()));
            });
        }).exceptionally(t -> failed(sender, "/checkban edit reason", t));
        return Cmds.OK;
    }

    private int checkbanDuration(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String input = StringArgumentType.getString(c, "input");
        long id = LongArgumentType.getLong(c, "id");
        String raw = StringArgumentType.getString(c, "value");
        Instant now = Instant.now();
        Optional<EditorInput.Expiry> parsed = EditorInput.expiry(raw, now);
        if (parsed.isEmpty()) {
            messages.send(sender, "moderation.checkban.invalid-duration", Msg.unparsed("input", raw));
            return Cmds.OK;
        }
        EditorInput.Expiry expiry = parsed.get();
        Component expires = expiry.permanent()
                ? messages.render(sender, "moderation.permanent")
                : Component.text(manager.formatDate(expiry.expires()));
        banRecord(input, id, now).thenCompose(found -> {
            if (found.isEmpty()) {
                messages.send(sender, "moderation.checkban.not-active", Msg.number("id", id));
                return CompletableFuture.completedFuture(null);
            }
            Punishment ban = found.get();
            return manager.store().updateActiveExpiry(id, expiry.expires(), now).thenAccept(updated -> {
                if (!updated) {
                    messages.send(sender, "moderation.checkban.not-active", Msg.number("id", id));
                    return;
                }
                refreshBan(ban);
                messages.send(sender, "moderation.checkban.duration-changed", Msg.number("id", id),
                        Msg.component("expires", expires));
            });
        }).exceptionally(t -> failed(sender, "/checkban edit duration", t));
        return Cmds.OK;
    }

    /** Re-reads the punished account so an edited ban takes effect without a relog. */
    private void refreshBan(Punishment ban) {
        if (ban.target() != null) {
            manager.reloadRemote(ban.target());
        }
    }

    /**
     * The active ban record {@code id} that belongs to {@code input} (an account name or an address/range).
     * Empty when the record does not exist, was lifted or belongs to someone else.
     */
    private CompletableFuture<Optional<Punishment>> banRecord(String input, long id, Instant now) {
        Optional<String> range = IpAddresses.normalizeRange(input);
        if (range.isPresent()) {
            return manager.store().byId(id).thenApply(found -> found
                    .filter(p -> p.type() == PunishmentType.IPBAN)
                    .filter(p -> p.isActive(now))
                    .filter(p -> matchesIpInput(p.ip(), range.get())));
        }
        return resolve(input).thenCompose(target -> {
            if (target.isEmpty()) {
                return CompletableFuture.completedFuture(Optional.<Punishment>empty());
            }
            UUID uuid = target.get().uuid();
            return manager.store().byId(id).thenApply(found -> found
                    .filter(p -> uuid.equals(p.target()))
                    .filter(p -> p.isActive(now))
                    .filter(p -> PunishmentType.BANS.contains(p.type()) || p.type() == PunishmentType.IPBAN));
        });
    }

    // ---- /lockip ----

    private int lockipUsage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "moderation.usage.lockip");
    }

    private int lockAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        @Nullable String rawIp = Cmds.hasArgument(c, "ip") ? StringArgumentType.getString(c, "ip") : null;
        Optional<String> requested = rawIp == null ? Optional.empty() : IpAddresses.normalize(rawIp);
        if (rawIp != null && requested.isEmpty()) {
            throw cmds.fail(c.getSource(), "moderation.lockip.invalid-address", Msg.unparsed("ip", rawIp));
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            if (isSelf(sender, t)) {
                messages.send(sender, "moderation.self");
                return CompletableFuture.completedFuture(null);
            }
            Player online = Bukkit.getPlayer(t.uuid());
            // The bypass node can only be evaluated while the account is online; pre-login checks cannot.
            if (online != null && online.hasPermission(PunishmentManager.LOCKIP_BYPASS)) {
                messages.send(sender, "moderation.lockip.bypass", Msg.unparsed("player", t.name()));
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Optional<String>> address;
            if (requested.isPresent()) {
                address = CompletableFuture.completedFuture(requested);
            } else if (online != null) {
                address = CompletableFuture.completedFuture(PunishmentManager.address(online));
            } else {
                address = manager.store().playerInfo(t.uuid())
                        .thenApply(info -> info.map(PunishmentStore.PlayerInfo::lastIp).flatMap(IpAddresses::normalize));
            }
            return address.thenCompose(found -> {
                if (found.isEmpty()) {
                    messages.send(sender, "moderation.no-known-ip", Msg.unparsed("player", t.name()));
                    return CompletableFuture.completedFuture(null);
                }
                String ip = found.get();
                return manager.store().lockAddress(t.uuid(), ip, PunishmentManager.actorName(sender),
                                Instant.now())
                        .thenRun(() -> messages.send(sender, "moderation.lockip.added",
                                Msg.unparsed("player", t.name()), Msg.unparsed("ip", ip)));
            });
        }).exceptionally(t -> failed(sender, "/lockip add", t));
        return Cmds.OK;
    }

    private int lockRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String rawIp = StringArgumentType.getString(c, "ip");
        Optional<String> ip = IpAddresses.normalize(rawIp);
        if (ip.isEmpty()) {
            throw cmds.fail(c.getSource(), "moderation.lockip.invalid-address", Msg.unparsed("ip", rawIp));
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.store().unlockAddress(t.uuid(), ip.get()).thenAccept(removed ->
                    messages.send(sender, removed ? "moderation.lockip.removed" : "moderation.lockip.not-locked",
                            Msg.unparsed("player", t.name()), Msg.unparsed("ip", ip.get())));
        }).exceptionally(t -> failed(sender, "/lockip remove", t));
        return Cmds.OK;
    }

    private int lockList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.store().lockedAddresses(t.uuid()).thenAccept(locks -> {
                if (locks.isEmpty()) {
                    messages.send(sender, "moderation.lockip.list-empty", Msg.unparsed("player", t.name()));
                    return;
                }
                messages.send(sender, "moderation.lockip.list-header", Msg.unparsed("player", t.name()),
                        Msg.number("count", locks.size()));
                for (PunishmentStore.LockedAddress lock : locks) {
                    messages.send(sender, "moderation.lockip.list-entry", Msg.unparsed("ip", lock.address()),
                            Msg.unparsed("actor", lock.actorName()),
                            Msg.unparsed("date", manager.formatDate(lock.created())));
                }
            });
        }).exceptionally(t -> failed(sender, "/lockip list", t));
        return Cmds.OK;
    }

    private int lockClear(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.store().unlockAllAddresses(t.uuid()).thenAccept(removed ->
                    messages.send(sender, "moderation.lockip.cleared",
                            Msg.unparsed("player", t.name()), Msg.number("count", removed)));
        }).exceptionally(t -> failed(sender, "/lockip clear", t));
        return Cmds.OK;
    }

    // ---- /mutechat ----

    private int muteChat(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ArgParser.Parsed parsed = ArgParser.parse(optional(c, "args"));
        ModerationConfig.ChatMute cfg = config.get().chatMute;
        Instant now = Instant.now();
        MuteChatState current = manager.muteChat();
        if (current.muted(now)) {
            messages.send(sender, "moderation.mutechat.already",
                    Msg.duration("remaining", current.remaining(now).orElse(Duration.ZERO)),
                    Msg.unparsed("actor", current.actorName()));
            return Cmds.OK;
        }
        List<String> words = new ArrayList<>(parsed.words());
        long maximum = Math.max(1, cfg.maxMinutes) * 60L;
        long seconds = Math.max(1, cfg.defaultMinutes) * 60L;
        if (!words.isEmpty()) {
            Optional<Duration> parsedDuration = Durations.parse(words.getFirst());
            if (parsedDuration.isPresent() && !parsedDuration.get().isNegative()) {
                seconds = Math.min(maximum, Math.max(1, parsedDuration.get().toSeconds()));
                words.removeFirst();
            }
        }
        String reason = MuteChatState.normaliseReason(String.join(" ", words), cfg.reason);
        Instant until = now.plusSeconds(seconds);
        Duration length = Duration.ofSeconds(seconds);
        MuteChatState next = current.until(until, reason, PunishmentManager.actorName(sender));
        manager.setMuteChat(next).thenRun(() -> {
            TagResolver tags = TagResolver.resolver(
                    Msg.unparsed("reason", reason),
                    Msg.unparsed("actor", next.actorName()),
                    Msg.duration("duration", length),
                    Msg.duration("remaining", length));
            manager.announce("moderation.broadcast.mutechat", parsed.silent(), sender, viewer -> tags, viewer -> tags);
        }).exceptionally(t -> failed(sender, "/mutechat", t));
        return Cmds.OK;
    }

    private int unmuteChat(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ArgParser.Parsed parsed = ArgParser.parse(optional(c, "args"));
        Instant now = Instant.now();
        MuteChatState current = manager.muteChat();
        if (!current.muted(now)) {
            messages.send(sender, "moderation.mutechat.not-muted");
            return Cmds.OK;
        }
        Duration remaining = current.remaining(now).orElse(Duration.ZERO);
        TagResolver tags = TagResolver.resolver(
                Msg.unparsed("actor", PunishmentManager.actorName(sender)),
                Msg.unparsed("previous", current.actorName()),
                Msg.duration("remaining", remaining),
                Msg.unparsed("reason", current.reasonOr(config.get().chatMute.reason)));
        manager.setMuteChat(MuteChatState.NONE).thenRun(() ->
                manager.announce("moderation.broadcast.unmutechat", parsed.silent(), sender, viewer -> tags, viewer -> tags)
        ).exceptionally(t -> failed(sender, "/unmutechat", t));
        return Cmds.OK;
    }

    // ---- /note ----

    private int noteAdd(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String raw = StringArgumentType.getString(c, "text");
        Optional<String> body = EditorInput.reason(raw, config.get().notes.maxLength);
        if (body.isEmpty()) {
            messages.send(sender, "moderation.note.blank");
            return Cmds.OK;
        }
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.notes().add(t.uuid(), t.name(), PunishmentManager.actorId(sender),
                    PunishmentManager.actorName(sender), body.get(), Instant.now())
                    .thenAccept(id -> messages.send(sender, "moderation.note.added",
                            Msg.number("id", id), Msg.unparsed("player", t.name())));
        }).exceptionally(t -> failed(sender, "/note add", t));
        return Cmds.OK;
    }

    private int noteList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        int requested = Cmds.hasArgument(c, "page") ? IntegerArgumentType.getInteger(c, "page") : 1;
        int size = Math.max(1, config.get().notes.pageSize);
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.notes().count(t.uuid()).thenCompose(total -> {
                Pagination page = Pagination.of(total, requested, size);
                if (page.empty()) {
                    messages.send(sender, "moderation.note.empty", Msg.unparsed("player", t.name()));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.notes().page(t.uuid(), page.offset(), size).thenAccept(notes -> {
                    messages.send(sender, "moderation.note.header", Msg.unparsed("player", t.name()),
                            Msg.number("page", page.current()), Msg.number("pages", page.pages()),
                            Msg.number("total", total));
                    for (NoteStore.Note note : notes) {
                        Component remove = runButton(sender, "moderation.note.remove-button",
                                "moderation.note.remove-hover", "/note " + t.name() + " remove " + note.id(),
                                Msg.number("id", note.id()));
                        messages.send(sender, "moderation.note.entry", Msg.number("id", note.id()),
                                Msg.unparsed("date", manager.formatDate(note.created())),
                                Msg.unparsed("actor", note.actorName()),
                                Msg.unparsed("body", note.body()),
                                Msg.component("remove", remove));
                    }
                });
            });
        }).exceptionally(t -> failed(sender, "/note list", t));
        return Cmds.OK;
    }

    private int noteRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        long id = LongArgumentType.getLong(c, "id");
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.notes().remove(t.uuid(), id).thenAccept(removed ->
                    messages.send(sender, removed ? "moderation.note.removed" : "moderation.note.not-found",
                            Msg.number("id", id), Msg.unparsed("player", t.name())));
        }).exceptionally(t -> failed(sender, "/note remove", t));
        return Cmds.OK;
    }

    private int noteClear(CommandContext<CommandSourceStack> c, boolean confirm) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        resolve(name).thenCompose(target -> {
            if (target.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            Target t = target.get();
            return manager.notes().count(t.uuid()).thenCompose(total -> {
                if (total == 0) {
                    messages.send(sender, "moderation.note.empty", Msg.unparsed("player", t.name()));
                    return CompletableFuture.completedFuture(null);
                }
                if (!confirm) {
                    Component button = runButton(sender, "moderation.note.confirm-button",
                            "moderation.note.clear-hover", "/note " + t.name() + " clear confirm",
                            Msg.number("count", total));
                    messages.send(sender, "moderation.note.clear-confirm", Msg.number("count", total),
                            Msg.unparsed("player", t.name()), Msg.component("confirm", button));
                    return CompletableFuture.completedFuture(null);
                }
                return manager.notes().clear(t.uuid()).thenAccept(removed ->
                        messages.send(sender, "moderation.note.cleared", Msg.number("count", removed),
                                Msg.unparsed("player", t.name())));
            });
        }).exceptionally(t -> failed(sender, "/note clear", t));
        return Cmds.OK;
    }

    // ---- jails ----

    private int setjail(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        if (!JAIL_NAME.matcher(name).matches()) {
            throw cmds.fail(c.getSource(), "moderation.jail.invalid-name", Msg.unparsed("name", name));
        }
        double radius = Cmds.hasArgument(c, "radius") ? DoubleArgumentType.getDouble(c, "radius") : config.get().jail.defaultRadius;
        // Commands run on the sender's own thread, so reading its location here is safe.
        Location location = player.getLocation();
        double safeRadius = Math.max(1, radius);
        String key = JailStore.key(name);
        Jail fresh = Jail.at(key, location, safeRadius);
        // Moving an existing jail keeps its editor options (release point, allowed commands, ...); the
        // update runs under the per-jail lock so a concurrent /jailedit is not overwritten.
        manager.jails().update(key, existing -> existing.movedTo(location).withRadius(safeRadius))
                .thenCompose(existing -> existing
                        .map(CompletableFuture::completedFuture)
                        .orElseGet(() -> manager.jails().save(fresh).thenApply(ignored -> fresh)))
                .thenAccept(saved -> messages.send(player, "moderation.jail.set",
                        Msg.unparsed("name", saved.name()), Msg.number("radius", saved.radius())))
                .exceptionally(t -> failed(player, "/setjail", t));
        return Cmds.OK;
    }

    private int deljail(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        manager.jails().delete(name).thenAccept(deleted -> messages.send(sender,
                        deleted ? "moderation.jail.deleted" : "moderation.jail.not-found", Msg.unparsed("name", name)))
                .exceptionally(t -> failed(sender, "/deljail", t));
        return Cmds.OK;
    }

    private int jails(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        var all = manager.jails().all().stream().sorted(java.util.Comparator.comparing(Jail::name)).toList();
        if (all.isEmpty()) {
            messages.send(sender, "moderation.jail.none");
            return Cmds.OK;
        }
        messages.send(sender, "moderation.jail.list-header", Msg.number("count", all.size()));
        for (Jail jail : all) {
            TagResolver resolvers = TagResolver.resolver(
                    Msg.unparsed("name", jail.name()),
                    Msg.unparsed("world", jail.world()),
                    Msg.number("x", Math.round(jail.x())),
                    Msg.number("y", Math.round(jail.y())),
                    Msg.number("z", Math.round(jail.z())),
                    Msg.number("radius", jail.radius()));
            Component line = messages.render(sender, "moderation.jail.list-entry", resolvers)
                    .clickEvent(ClickEvent.runCommand("/jailedit " + jail.name()))
                    .hoverEvent(HoverEvent.showText(messages.render(sender, "moderation.jail.list-entry-hover",
                            resolvers)));
            sender.sendMessage(line);
        }
        return Cmds.OK;
    }

    // ---- /jailedit ----

    private int jaileditUsage(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "moderation.jailedit.usage");
    }

    private int jailInfo(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        Optional<Jail> found = jailOrMessage(sender, name);
        if (found.isEmpty()) {
            return Cmds.OK;
        }
        Jail jail = found.get();
        manager.store().countActiveJail(jail.name(), Instant.now()).thenAccept(prisoners -> {
            messages.send(sender, "moderation.jailedit.header", Msg.unparsed("name", jail.name()));
            messages.send(sender, "moderation.jailedit.location",
                    Msg.unparsed("world", jail.world()),
                    Msg.number("x", Math.round(jail.x())),
                    Msg.number("y", Math.round(jail.y())),
                    Msg.number("z", Math.round(jail.z())),
                    Msg.number("radius", jail.radius()));
            messages.send(sender, "moderation.jailedit.release", Msg.component("release", releaseLabel(sender, jail)));
            messages.send(sender, "moderation.jailedit.allowed", Msg.component("allowed", allowedLabel(sender, jail)));
            messages.send(sender, "moderation.jailedit.max-sentence", Msg.component("max", maxSentenceLabel(sender, jail)));
            messages.send(sender, "moderation.jailedit.chat", Msg.component("state",
                    messages.render(sender, jail.chatAllowed() ? "moderation.jailedit.on" : "moderation.jailedit.off")));
            messages.send(sender, "moderation.jailedit.prisoners", Msg.number("count", prisoners));
            messages.send(sender, "moderation.jailedit.cells",
                    Msg.number("count", manager.jails().cellCount(jail.name())));
            messages.send(sender, "moderation.jailedit.buttons",
                    Msg.component("buttons", jailButtons(sender, jail)));
        }).exceptionally(t -> failed(sender, "/jailedit info", t));
        return Cmds.OK;
    }

    private Component releaseLabel(CommandSender sender, Jail jail) {
        ReleaseLocation release = jail.release();
        if (release == null) {
            return messages.render(sender, "moderation.jailedit.release-default");
        }
        return Component.text(release.world() + " " + Math.round(release.x()) + ", " + Math.round(release.y())
                + ", " + Math.round(release.z()));
    }

    private Component allowedLabel(CommandSender sender, Jail jail) {
        if (jail.allowedCommands().isEmpty()) {
            return messages.render(sender, "moderation.jailedit.allowed-default");
        }
        return Component.text(String.join(", ", jail.allowedCommands()));
    }

    private Component maxSentenceLabel(CommandSender sender, Jail jail) {
        if (jail.maxSentence() == null) {
            return messages.render(sender, "moderation.jailedit.unlimited");
        }
        return Component.text(Durations.format(jail.maxSentence(), 3));
    }

    private Component jailButtons(CommandSender sender, Jail jail) {
        String base = "/jailedit " + jail.name();
        Component move = runButton(sender, "moderation.jailedit.button.move", "moderation.jailedit.button.move-hover",
                base + " move");
        Component radius = suggestButton(sender, "moderation.jailedit.button.radius",
                "moderation.jailedit.button.radius-hover", base + " radius ");
        Component releaseHere = runButton(sender, "moderation.jailedit.button.release-here",
                "moderation.jailedit.button.release-here-hover", base + " release here");
        Component releaseClear = runButton(sender, "moderation.jailedit.button.release-clear",
                "moderation.jailedit.button.release-clear-hover", base + " release clear");
        Component allowed = runButton(sender, "moderation.jailedit.button.allowed",
                "moderation.jailedit.button.allowed-hover", base + " allowed");
        Component max = suggestButton(sender, "moderation.jailedit.button.max-sentence",
                "moderation.jailedit.button.max-sentence-hover", base + " maxsentence ");
        Component chat = jail.chatAllowed()
                ? runButton(sender, "moderation.jailedit.button.chat-off",
                        "moderation.jailedit.button.chat-hover", base + " chat false")
                : runButton(sender, "moderation.jailedit.button.chat-on",
                        "moderation.jailedit.button.chat-hover", base + " chat true");
        Component cells = runButton(sender, "moderation.jailedit.button.cells",
                "moderation.jailedit.button.cells-hover", base + " cell list");
        return Component.text()
                .append(move).append(Component.text(" "))
                .append(radius).append(Component.text(" "))
                .append(releaseHere).append(Component.text(" "))
                .append(releaseClear).append(Component.text(" "))
                .append(allowed).append(Component.text(" "))
                .append(max).append(Component.text(" "))
                .append(cells).append(Component.text(" "))
                .append(chat)
                .build();
    }

    private int jailMove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "jail");
        // Read the position on the player's own thread; the edit itself runs on the I/O thread.
        Location location = player.getLocation();
        editJail(player, name, jail -> jail.movedTo(location), moved ->
                messages.send(player, "moderation.jailedit.moved",
                        Msg.unparsed("name", moved.name()),
                        Msg.unparsed("world", moved.world()),
                        Msg.number("x", Math.round(moved.x())),
                        Msg.number("y", Math.round(moved.y())),
                        Msg.number("z", Math.round(moved.z())),
                        Msg.number("radius", moved.radius())));
        return Cmds.OK;
    }

    private int jailRadius(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        double radius = DoubleArgumentType.getDouble(c, "radius");
        editJail(sender, name, jail -> jail.withRadius(radius), updated ->
                messages.send(sender, "moderation.jailedit.radius-set",
                        Msg.unparsed("name", updated.name()), Msg.number("radius", updated.radius())));
        return Cmds.OK;
    }

    private int jailReleaseHere(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "jail");
        ReleaseLocation release = ReleaseLocation.of(player.getLocation());
        editJail(player, name, jail -> jail.withRelease(release), updated ->
                messages.send(player, "moderation.jailedit.release-set",
                        Msg.unparsed("name", updated.name()),
                        Msg.component("release", releaseLabel(player, updated))));
        return Cmds.OK;
    }

    private int jailReleaseClear(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        editJail(sender, name, jail -> jail.withRelease(null), updated ->
                messages.send(sender, "moderation.jailedit.release-cleared", Msg.unparsed("name", updated.name())));
        return Cmds.OK;
    }

    private int jailAllowedList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        Optional<Jail> found = jailOrMessage(sender, name);
        if (found.isEmpty()) {
            return Cmds.OK;
        }
        Jail jail = found.get();
        if (jail.allowedCommands().isEmpty()) {
            messages.send(sender, "moderation.jailedit.allowed-empty", Msg.unparsed("name", jail.name()));
            return Cmds.OK;
        }
        messages.send(sender, "moderation.jailedit.allowed-header", Msg.unparsed("name", jail.name()),
                Msg.number("count", jail.allowedCommands().size()));
        for (String command : jail.allowedCommands()) {
            Component remove = runButton(sender, "moderation.jailedit.allowed-remove",
                    "moderation.jailedit.allowed-remove-hover",
                    "/jailedit " + jail.name() + " allowed remove " + command, Msg.unparsed("command", command));
            messages.send(sender, "moderation.jailedit.allowed-entry", Msg.unparsed("command", command),
                    Msg.component("remove", remove));
        }
        return Cmds.OK;
    }

    private int jailAllowedAdd(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        String raw = StringArgumentType.getString(c, "command");
        Optional<String> command = EditorInput.allowedCommand(raw);
        if (command.isEmpty()) {
            messages.send(sender, "moderation.jailedit.allowed-invalid", Msg.unparsed("input", raw));
            return Cmds.OK;
        }
        String value = command.get();
        editJail(sender, name, jail -> {
            if (jail.allowedCommands().stream().anyMatch(entry -> entry.equalsIgnoreCase(value))) {
                throw new JailRejected("moderation.jailedit.allowed-present",
                        Msg.unparsed("command", value), Msg.unparsed("name", jail.name()));
            }
            List<String> commands = new ArrayList<>(jail.allowedCommands());
            commands.add(value);
            return jail.withAllowedCommands(commands);
        }, updated -> messages.send(sender, "moderation.jailedit.allowed-added",
                Msg.unparsed("command", value), Msg.unparsed("name", updated.name())));
        return Cmds.OK;
    }

    private int jailAllowedRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        String raw = StringArgumentType.getString(c, "command");
        Optional<String> command = EditorInput.allowedCommand(raw);
        if (command.isEmpty()) {
            messages.send(sender, "moderation.jailedit.allowed-invalid", Msg.unparsed("input", raw));
            return Cmds.OK;
        }
        String value = command.get();
        editJail(sender, name, jail -> {
            List<String> commands = jail.allowedCommands().stream()
                    .filter(entry -> !entry.equalsIgnoreCase(value))
                    .toList();
            if (commands.size() == jail.allowedCommands().size()) {
                throw new JailRejected("moderation.jailedit.allowed-absent",
                        Msg.unparsed("command", value), Msg.unparsed("name", jail.name()));
            }
            return jail.withAllowedCommands(commands);
        }, updated -> messages.send(sender, "moderation.jailedit.allowed-removed",
                Msg.unparsed("command", value), Msg.unparsed("name", updated.name())));
        return Cmds.OK;
    }

    private int jailAllowedClear(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        editJail(sender, name, jail -> jail.withAllowedCommands(List.of()), updated ->
                messages.send(sender, "moderation.jailedit.allowed-cleared",
                        Msg.unparsed("name", updated.name())));
        return Cmds.OK;
    }

    private int jailMaxSentence(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        String raw = StringArgumentType.getString(c, "duration");
        Optional<EditorInput.MaxSentence> parsed = EditorInput.maxSentence(raw);
        if (parsed.isEmpty()) {
            messages.send(sender, "moderation.jailedit.invalid-max", Msg.unparsed("input", raw));
            return Cmds.OK;
        }
        editJail(sender, name, jail -> jail.withMaxSentence(parsed.get().value()), updated ->
                messages.send(sender, "moderation.jailedit.max-set",
                        Msg.unparsed("name", updated.name()),
                        Msg.component("max", maxSentenceLabel(sender, updated))));
        return Cmds.OK;
    }

    private int jailChat(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        String raw = StringArgumentType.getString(c, "value");
        Optional<Boolean> value = EditorInput.bool(raw);
        if (value.isEmpty()) {
            messages.send(sender, "moderation.jailedit.invalid-bool", Msg.unparsed("input", raw));
            return Cmds.OK;
        }
        editJail(sender, name, jail -> jail.withChatAllowed(value.get()), updated ->
                messages.send(sender, "moderation.jailedit.chat-set", Msg.unparsed("name", updated.name()),
                        Msg.component("state", messages.render(sender, value.get()
                                ? "moderation.jailedit.on" : "moderation.jailedit.off"))));
        return Cmds.OK;
    }

    // ---- /jailedit <jail> cell ----

    private int jailCellList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        Optional<Jail> found = jailOrMessage(sender, name);
        if (found.isEmpty()) {
            return Cmds.OK;
        }
        Jail jail = found.get();
        List<JailCells.Cell> cells = manager.jails().cells(jail.name());
        if (cells.isEmpty()) {
            messages.send(sender, "moderation.jailedit.cells-empty", Msg.unparsed("name", jail.name()));
            return Cmds.OK;
        }
        messages.send(sender, "moderation.jailedit.cells-header", Msg.unparsed("name", jail.name()),
                Msg.number("count", cells.size()));
        for (int i = 0; i < cells.size(); i++) {
            JailCells.Cell cell = cells.get(i);
            Component remove = runButton(sender, "moderation.jailedit.cell-remove",
                    "moderation.jailedit.cell-remove-hover",
                    "/jailedit " + jail.name() + " cell remove " + i, Msg.number("index", i));
            messages.send(sender, "moderation.jailedit.cell-entry", Msg.number("index", i),
                    Msg.unparsed("world", cell.world()),
                    Msg.number("x", Math.round(cell.x())),
                    Msg.number("y", Math.round(cell.y())),
                    Msg.number("z", Math.round(cell.z())),
                    Msg.component("remove", remove));
        }
        return Cmds.OK;
    }

    private int jailCellAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "jail");
        Optional<Jail> found = jailOrMessage(player, name);
        if (found.isEmpty()) {
            return Cmds.OK;
        }
        Jail jail = found.get();
        // Commands run on the sender's own thread, so reading the position here is safe.
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            messages.send(player, "core.error");
            return Cmds.OK;
        }
        JailCells.Cell cell = new JailCells.Cell(location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch());
        manager.jails().addCell(jail.name(), cell).thenAccept(index ->
                messages.send(player, "moderation.jailedit.cell-added", Msg.unparsed("name", jail.name()),
                        Msg.number("index", index), Msg.unparsed("world", cell.world()),
                        Msg.number("x", Math.round(cell.x())),
                        Msg.number("y", Math.round(cell.y())),
                        Msg.number("z", Math.round(cell.z()))))
                .exceptionally(t -> failed(player, "/jailedit cell add", t));
        return Cmds.OK;
    }

    private int jailCellRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        int index = IntegerArgumentType.getInteger(c, "index");
        Optional<Jail> found = jailOrMessage(sender, name);
        if (found.isEmpty()) {
            return Cmds.OK;
        }
        Jail jail = found.get();
        manager.jails().removeCell(jail.name(), index).thenAccept(removed ->
                messages.send(sender, removed ? "moderation.jailedit.cell-removed" : "moderation.jailedit.cell-absent",
                        Msg.unparsed("name", jail.name()), Msg.number("index", index)))
                .exceptionally(t -> failed(sender, "/jailedit cell remove", t));
        return Cmds.OK;
    }

    private int jailCellClear(CommandContext<CommandSourceStack> c, boolean confirm) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "jail");
        Optional<Jail> found = jailOrMessage(sender, name);
        if (found.isEmpty()) {
            return Cmds.OK;
        }
        Jail jail = found.get();
        int count = manager.jails().cellCount(jail.name());
        if (count == 0) {
            messages.send(sender, "moderation.jailedit.cells-empty", Msg.unparsed("name", jail.name()));
            return Cmds.OK;
        }
        if (!confirm) {
            Component button = runButton(sender, "moderation.note.confirm-button",
                    "moderation.jailedit.cell-clear-hover",
                    "/jailedit " + jail.name() + " cell clear confirm", Msg.number("count", count));
            messages.send(sender, "moderation.jailedit.cell-clear-confirm", Msg.number("count", count),
                    Msg.unparsed("name", jail.name()), Msg.component("confirm", button));
            return Cmds.OK;
        }
        manager.jails().clearCells(jail.name()).thenRun(() ->
                messages.send(sender, "moderation.jailedit.cell-cleared", Msg.unparsed("name", jail.name())))
                .exceptionally(t -> failed(sender, "/jailedit cell clear", t));
        return Cmds.OK;
    }

    /** Looks a jail up, sending the not-found line instead of failing silently. */
    private Optional<Jail> jailOrMessage(CommandSender sender, String name) {
        Optional<Jail> jail = manager.jails().get(name);
        if (jail.isEmpty()) {
            messages.send(sender, "moderation.jail.not-found", Msg.unparsed("name", name));
        }
        return jail;
    }

    /**
     * Applies an edit to the current jail under the per-jail lock (see {@link JailStore#update}). The editor
     * sees the freshest definition, so two quick {@code /jailedit} changes cannot overwrite each other;
     * validation refusals raise {@link JailRejected} and nothing is written.
     */
    private void editJail(CommandSender sender, String name, UnaryOperator<Jail> editor, Consumer<Jail> success) {
        manager.jails().update(name, editor).whenComplete((updated, error) -> {
            if (error != null) {
                Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                        ? error.getCause() : error;
                if (cause instanceof JailRejected rejected) {
                    messages.send(sender, rejected.key(), rejected.tags());
                } else {
                    failed(sender, "/jailedit", cause);
                }
                return;
            }
            if (updated.isEmpty()) {
                messages.send(sender, "moderation.jail.not-found", Msg.unparsed("name", name));
                return;
            }
            success.accept(updated.get());
        });
    }

    /** A validation refusal raised inside a {@link JailStore#update} editor; reported instead of written. */
    private static final class JailRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String key;
        private final transient TagResolver[] tags;

        JailRejected(String key, TagResolver... tags) {
            super(key, null, false, false);
            this.key = key;
            this.tags = tags.clone();
        }

        String key() {
            return key;
        }

        TagResolver[] tags() {
            return tags.clone();
        }
    }

    // ---- helpers ----

    /** Online player or a known account; unknown names get a profile lookup when the config allows it. */
    private CompletableFuture<Optional<Target>> resolve(String name) {
        return ctx.users().resolveAccount(name, config.get().resolveUnknownNames)
                .thenApply(found -> found.map(ref -> new Target(ref.uuid(), ref.name())));
    }

    private static boolean isSelf(CommandSender sender, Target target) {
        return sender instanceof Player player && player.getUniqueId().equals(target.uuid());
    }

    private static @Nullable String optional(CommandContext<CommandSourceStack> c, String argument) {
        return Cmds.hasArgument(c, argument) ? StringArgumentType.getString(c, argument) : null;
    }

    /** A clickable label that runs a command; the command text is built from validated names and ids only. */
    private Component runButton(CommandSender sender, String key, String hoverKey, String command,
            TagResolver... resolvers) {
        return messages.render(sender, key, resolvers)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(messages.render(sender, hoverKey, resolvers)));
    }

    /** A clickable label that puts a command in the chat bar so the value can be completed. */
    private Component suggestButton(CommandSender sender, String key, String hoverKey, String command,
            TagResolver... resolvers) {
        return messages.render(sender, key, resolvers)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(messages.render(sender, hoverKey, resolvers)));
    }

    /** Parses flags and checks the permission for each one used. */
    private ArgParser.Parsed flags(CommandContext<CommandSourceStack> c, @Nullable String tail, boolean shadowAllowed)
            throws CommandSyntaxException {
        ArgParser.Parsed parsed = ArgParser.parse(tail);
        CommandSender sender = c.getSource().getSender();
        if (parsed.silent() && !sender.hasPermission(PERM + "silent")) {
            throw cmds.fail(c.getSource(), "moderation.no-permission-flag", Msg.unparsed("flag", "-s"),
                    Msg.unparsed("permission", PERM + "silent"));
        }
        if (parsed.shadow()) {
            if (!shadowAllowed) {
                throw cmds.fail(c.getSource(), "moderation.flag-not-here", Msg.unparsed("flag", ArgParser.SHADOW_FLAG));
            }
            if (!sender.hasPermission(PERM + "shadowmute")) {
                throw cmds.fail(c.getSource(), "moderation.no-permission-flag", Msg.unparsed("flag", ArgParser.SHADOW_FLAG),
                        Msg.unparsed("permission", PERM + "shadowmute"));
            }
        }
        return parsed;
    }

    private String reason(String raw) {
        ModerationConfig cfg = config.get();
        // Blank stays blank here: the manager substitutes the configured default.
        return raw == null || raw.isBlank() ? "" : ArgParser.reason(raw, cfg.reasonPresets, cfg.defaultReason, cfg.maxReasonLength);
    }

    private @Nullable Duration warningLifetime() {
        return Durations.parse(config.get().warnings.expireAfter).filter(d -> !d.isZero()).orElse(null);
    }

    private void report(CommandSender sender, PunishResult result, String targetName) {
        String key = switch (result.status()) {
            case APPLIED -> null;
            case CANCELLED -> "moderation.cancelled";
            case EXEMPT -> "moderation.exempt";
            case NO_KNOWN_IP -> "moderation.no-known-ip";
            case NO_JAIL -> "moderation.jail.none";
            case INVALID -> "moderation.invalid";
            case FAILED -> "core.error";
        };
        if (key != null) {
            messages.send(sender, key, Msg.unparsed("player", targetName));
        }
    }

    private void reportRevoke(CommandSender sender, RevokeResult result, String targetName, String notActiveKey) {
        String key = switch (result.status()) {
            case REVOKED -> null;
            case NOTHING_ACTIVE -> notActiveKey;
            case CANCELLED -> "moderation.cancelled";
            case FAILED -> "core.error";
        };
        if (key != null) {
            messages.send(sender, key, Msg.unparsed("player", targetName));
        }
    }

    private Void failed(CommandSender sender, String what, Throwable t) {
        ctx.logger().error("{} by {} failed", what, sender.getName(), t);
        messages.send(sender, "core.error");
        return null;
    }
}
