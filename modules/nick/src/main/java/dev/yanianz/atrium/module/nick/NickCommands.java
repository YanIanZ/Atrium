package dev.yanianz.atrium.module.nick;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /nick} and {@code /realname}. All nickname changes are persisted before they are shown. */
final class NickCommands implements TaskHandle {
    private final NickModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<NickConfig> config;
    private final NickStore store;
    private final NickRenderer renderer;
    private volatile boolean cancelled;

    NickCommands(NickModule module) {
        this.module = module;
        this.ctx = module.context();
        this.cmds = ctx.commands();
        this.config = module.configHandle();
        this.store = module.store();
        this.renderer = module.renderer();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("nick", NickModule.PERM_NICK)
                .executes(this::show)
                .then(Commands.argument("nickname", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("off")))
                        .executes(this::set)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::set)))
                .build(), "Set, clear or display a nickname"));
        ctx.command(CommandSpec.of(Cmds.literal("realname", NickModule.PERM_REALNAME)
                .executes(context -> usage(context, "nick.usage-realname"))
                .then(Commands.argument("nickname", StringArgumentType.word())
                        .suggests(Cmds.suggest(this::realnameSuggestions))
                        .executes(this::realname))
                .build(), "Find a player by nickname"));
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    private int show(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> {
            if (cancelled) {
                return;
            }
            try {
                NickState state = module.state(player);
                if (state.hasNick()) {
                    ctx.messages().send(player, "nick.current", Msg.component("nick", module.display(player, state)));
                } else {
                    ctx.messages().send(player, "nick.current-none");
                }
            } catch (RuntimeException e) {
                failed(player, "Read nickname", e);
            }
        }, null);
        return Cmds.OK;
    }

    private int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", NickModule.PERM_OTHERS);
        CommandSender sender = c.getSource().getSender();
        NickConfig cfg = config.get();
        String input = NickLogic.stripPrefix(StringArgumentType.getString(c, "nickname"), cfg.prefix);
        if (input.equalsIgnoreCase("off")) {
            return clear(target, sender);
        }
        NickRules rules = module.rules();
        if (input.length() > rules.maxRawLength()) {
            throw cmds.fail(c.getSource(), "nick.too-long", Msg.number("max", rules.maxRawLength()));
        }
        boolean color = target.hasPermission(NickModule.PERM_COLOR);
        boolean format = target.hasPermission(NickModule.PERM_FORMAT);
        boolean gradient = target.hasPermission(NickModule.PERM_GRADIENT);
        Component parsed;
        try {
            parsed = renderer.typed(input, color, format, gradient);
        } catch (RuntimeException e) {
            throw cmds.fail(c.getSource(), "nick.invalid-format");
        }
        String plain = renderer.plain(parsed);
        NickLogic.Violation violation = NickLogic.validate(plain, rules);
        if (violation != null) {
            throw cmds.fail(c.getSource(), "nick." + violationKey(violation), violationTags(violation, rules));
        }
        if (!target.isOnline()) {
            throw cmds.fail(c.getSource(), "nick.target-left");
        }
        String lower = NickLogic.canonical(plain);

        // A real name is checked before storage so the failure explains who already owns it.
        CompletableFuture<Optional<PlayerRef>> realName = cfg.blockRealNames
                ? ctx.users().resolveAccount(plain, false)
                : CompletableFuture.completedFuture(Optional.empty());
        realName.whenComplete((found, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                failed(sender, "Resolve real name of nickname " + plain, error);
                return;
            }
            Optional<PlayerRef> existing = found == null ? Optional.empty() : found;
            if (existing.isPresent()) {
                PlayerRef ref = existing.get();
                if (!ref.uuid().equals(target.getUniqueId()) || !cfg.allowOwnName) {
                    reply(sender, "in-use-real", Msg.unparsed("player", ref.name()));
                    return;
                }
            }
            claim(target, sender, input, plain, lower);
        });
        return Cmds.OK;
    }

    private void claim(Player target, CommandSender sender, String input, String plain, String lower) {
        if (!target.isOnline()) {
            reply(sender, "target-left");
            return;
        }
        store.findOwner(lower).whenComplete((owner, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                failed(sender, "Look up nickname owner", error);
                return;
            }
            Optional<UUID> ownerId = owner == null ? Optional.empty() : owner;
            if (ownerId.isPresent() && !ownerId.get().equals(target.getUniqueId())) {
                ownerName(ownerId.get()).whenComplete((name, ignored) -> {
                    if (name == null) {
                        reply(sender, "in-use-generic");
                    } else {
                        reply(sender, "in-use", Msg.unparsed("player", name));
                    }
                });
                return;
            }
            NickState state = NickState.of(input, plain);
            store.claim(target.getUniqueId(), state).whenComplete((result, claimError) -> {
                if (cancelled) {
                    return;
                }
                if (claimError != null) {
                    failed(sender, "Claim nickname", claimError);
                    return;
                }
                if (result == NickStore.ClaimResult.TAKEN) {
                    reply(sender, "in-use-generic");
                    return;
                }
                ctx.scheduler().entity(target, () -> {
                    if (cancelled) {
                        return;
                    }
                    try {
                        module.saveState(target, state);
                        Component display = module.display(target, state);
                        module.apply(target, display);
                        ctx.messages().send(target, "nick.changed", Msg.component("nick", display));
                        if (!sender.equals(target)) {
                            reply(sender, "changed-other", Msg.unparsed("player", target.getName()),
                                    Msg.component("nick", display));
                        }
                    } catch (RuntimeException e) {
                        failed(sender, "Apply nickname", e);
                    }
                }, () -> reply(sender, "target-left"));
            });
        });
    }

    private int clear(Player target, CommandSender sender) {
        store.release(target.getUniqueId()).whenComplete((unused, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                failed(sender, "Clear nickname", error);
                return;
            }
            ctx.scheduler().entity(target, () -> {
                if (cancelled) {
                    return;
                }
                try {
                    module.clearState(target);
                    module.reset(target);
                    ctx.messages().send(target, "nick.cleared");
                    if (!sender.equals(target)) {
                        reply(sender, "cleared-other", Msg.unparsed("player", target.getName()));
                    }
                } catch (RuntimeException e) {
                    failed(sender, "Clear nickname", e);
                }
            }, () -> reply(sender, "target-left"));
        });
        return Cmds.OK;
    }

    private int realname(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String input = renderer.lookupText(StringArgumentType.getString(c, "nickname"));
        String wanted = NickLogic.canonical(NickLogic.stripPrefix(input, config.get().prefix));
        if (wanted.isEmpty()) {
            throw cmds.fail(c.getSource(), "nick.realname-none");
        }
        boolean console = !(sender instanceof Player);
        boolean color = console || sender.hasPermission(NickModule.PERM_COLOR);
        boolean format = console || sender.hasPermission(NickModule.PERM_FORMAT);
        boolean gradient = console || sender.hasPermission(NickModule.PERM_GRADIENT);

        // Online nicknames are already in memory and give an immediate answer.
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            NickState state = module.stateOrNull(player);
            if (state != null && state.hasNick() && NickLogic.canonical(state.plain()).equals(wanted)) {
                sendResult(sender, player.getName(), state, color, format, gradient);
                return Cmds.OK;
            }
        }
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (NickLogic.canonical(player.getName()).equals(wanted)) {
                NickState state = module.stateOrNull(player);
                if (state != null && state.hasNick()) {
                    sendResult(sender, player.getName(), state, color, format, gradient);
                } else {
                    reply(sender, "realname-no-nick", Msg.unparsed("player", player.getName()));
                }
                return Cmds.OK;
            }
        }

        store.findOwner(wanted).whenComplete((owner, error) -> {
            if (cancelled) {
                return;
            }
            if (error != null) {
                failed(sender, "Look up nickname", error);
                return;
            }
            Optional<UUID> ownerId = owner == null ? Optional.empty() : owner;
            if (ownerId.isPresent()) {
                storedResult(sender, ownerId.get(), color, format, gradient);
                return;
            }
            ctx.users().resolveAccount(wanted, false).whenComplete((ref, refError) -> {
                if (cancelled) {
                    return;
                }
                if (refError != null) {
                    failed(sender, "Resolve real name", refError);
                    return;
                }
                Optional<PlayerRef> found = ref == null ? Optional.empty() : ref;
                if (found.isEmpty()) {
                    reply(sender, "realname-none");
                    return;
                }
                storedResult(sender, found.get().uuid(), color, format, gradient, found.get().name());
            });
        });
        return Cmds.OK;
    }

    private void storedResult(CommandSender sender, UUID uuid, boolean color, boolean format, boolean gradient) {
        storedResult(sender, uuid, color, format, gradient, null);
    }

    private void storedResult(CommandSender sender, UUID uuid, boolean color, boolean format, boolean gradient,
            String knownName) {
        CompletableFuture<Optional<String>> name = knownName != null
                ? CompletableFuture.completedFuture(Optional.of(knownName))
                : ctx.users().load(uuid).thenApply(user -> user.map(AtriumUser::name));
        name.whenComplete((resolved, nameError) -> {
            if (cancelled) {
                return;
            }
            if (nameError != null) {
                failed(sender, "Load nickname owner", nameError);
                return;
            }
            if (resolved == null || resolved.isEmpty()) {
                reply(sender, "realname-none");
                return;
            }
            String realName = resolved.get();
            store.component.loader().load(uuid).whenComplete((state, stateError) -> {
                if (cancelled) {
                    return;
                }
                if (stateError != null) {
                    failed(sender, "Read nickname", stateError);
                    return;
                }
                if (state == null || !state.hasNick()) {
                    reply(sender, "realname-no-nick", Msg.unparsed("player", realName));
                    return;
                }
                sendResult(sender, realName, state, color, format, gradient);
            });
        });
    }

    private void sendResult(CommandSender sender, String realName, NickState state, boolean color, boolean format,
            boolean gradient) {
        Component nick = module.render(state, color, format, gradient);
        reply(sender, "realname-result", Msg.component("nick", nick), Msg.unparsed("player", realName));
    }

    private CompletableFuture<String> ownerName(UUID uuid) {
        return ctx.users().load(uuid)
                .thenApply(user -> user.map(AtriumUser::name).orElse(null))
                .exceptionally(error -> null);
    }

    private Collection<String> realnameSuggestions() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        String prefix = config.get().prefix == null ? "" : config.get().prefix;
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            NickState state = module.stateOrNull(player);
            if (state != null && state.hasNick()) {
                names.add(prefix + state.plain());
                names.add(state.plain());
            }
            names.add(player.getName());
        }
        names.addAll(ctx.users().knownNames("", 100));
        return new ArrayList<>(names);
    }

    private static String violationKey(NickLogic.Violation violation) {
        return switch (violation) {
            case LENGTH -> "invalid-length";
            case CHARACTERS -> "invalid-characters";
            case BLOCKED -> "blocked";
        };
    }

    private static TagResolver[] violationTags(NickLogic.Violation violation, NickRules rules) {
        return switch (violation) {
            case LENGTH -> new TagResolver[]{
                    Msg.number("min", rules.minLength()), Msg.number("max", rules.maxLength())};
            case CHARACTERS -> new TagResolver[]{Msg.unparsed("pattern", rules.allowedPattern())};
            case BLOCKED -> new TagResolver[0];
        };
    }

    private void failed(CommandSender sender, String operation, Throwable error) {
        ctx.logger().error("{} failed", operation, error);
        reply(sender, "failed");
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    ctx.messages().send(player, "nick." + key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(recipient, "nick." + key, tags);
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
