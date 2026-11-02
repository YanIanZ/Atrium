package dev.yanianz.atrium.module.placeholders;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /usermeta} manages custom player metadata. Values are plain text; they are inserted as unparsed
 * placeholders so an operator-typed value can never become MiniMessage or a nested placeholder. Resolution
 * and storage are asynchronous, so a region thread is never blocked.
 */
final class UserMetaCommand {
    static final String PERM_USE = "atrium.usermeta";
    static final String PERM_OTHERS = "atrium.usermeta.others";

    /** Who the command acts on, plus the sender that must receive the result. */
    private record Target(CommandSender sender, UUID uuid, String name) {}

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final UserMetaStore store;
    private final Consumer<UUID> onChanged;

    UserMetaCommand(ModuleContext ctx, UserMetaStore store, Consumer<UUID> onChanged) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.store = store;
        this.onChanged = onChanged;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("usermeta", PERM_USE)
                .executes(this::usage)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::list)
                        .then(Cmds.literal("list").executes(this::list))
                        .then(add("add"))
                        .then(add("set"))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(this::remove)))
                        .then(Cmds.literal("clear").executes(this::clear))
                        .then(Commands.literal("increment")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(c -> increment(c, "1"))
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(c -> increment(c,
                                                        StringArgumentType.getString(c, "amount")))))))
                .build(), "Add, remove or list custom player metadata"));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> add(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("key", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                .executes(c -> set(c, literal))));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "placeholders.usermeta-usage");
        return Cmds.OK;
    }

    // ---- operations ------------------------------------------------------------------------------

    private int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        target(c).thenCompose(target -> store.load(target.uuid())
                .thenAccept(values -> onSender(target.sender(), () -> sendList(target, values))))
                .exceptionally(error -> {
                    failed(c.getSource().getSender(), "Reading player metadata", error);
                    return null;
                });
        return Cmds.OK;
    }

    private void sendList(Target target, Map<String, String> values) {
        ctx.messages().send(target.sender(), "placeholders.usermeta-header",
                Msg.unparsed("player", target.name()), Msg.number("count", values.size()));
        if (values.isEmpty()) {
            ctx.messages().send(target.sender(), "placeholders.usermeta-empty");
            return;
        }
        values.forEach((key, value) -> ctx.messages().send(target.sender(), "placeholders.usermeta-entry",
                Msg.unparsed("key", key), Msg.unparsed("value", value)));
    }

    private int set(CommandContext<CommandSourceStack> c, String literal) throws CommandSyntaxException {
        String key = UserMetaValues.normalizeKey(StringArgumentType.getString(c, "key"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "placeholders.usermeta-invalid-key"));
        String value = UserMetaValues.normalizeValue(StringArgumentType.getString(c, "value"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "placeholders.usermeta-invalid-value"));
        target(c).thenCompose(target -> store.set(target.uuid(), key, value)
                .thenRun(() -> {
                    changed(target.uuid());
                    onSender(target.sender(), () -> ctx.messages().send(target.sender(),
                            "placeholders.usermeta-set", Msg.unparsed("player", target.name()),
                            Msg.unparsed("key", key), Msg.unparsed("value", value),
                            Msg.unparsed("mode", literal)));
                }))
                .exceptionally(error -> {
                    failed(c.getSource().getSender(), "Writing metadata " + key, error);
                    return null;
                });
        return Cmds.OK;
    }

    private int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String key = UserMetaValues.normalizeKey(StringArgumentType.getString(c, "key"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "placeholders.usermeta-invalid-key"));
        target(c).thenCompose(target -> store.remove(target.uuid(), key)
                .thenAccept(removed -> {
                    changed(target.uuid());
                    onSender(target.sender(), () -> ctx.messages().send(target.sender(),
                            Boolean.TRUE.equals(removed)
                                    ? "placeholders.usermeta-removed" : "placeholders.usermeta-missing",
                            Msg.unparsed("player", target.name()), Msg.unparsed("key", key)));
                }))
                .exceptionally(error -> {
                    failed(c.getSource().getSender(), "Removing metadata " + key, error);
                    return null;
                });
        return Cmds.OK;
    }

    private int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        target(c).thenCompose(target -> store.clear(target.uuid())
                .thenAccept(count -> {
                    changed(target.uuid());
                    onSender(target.sender(), () -> ctx.messages().send(target.sender(),
                            "placeholders.usermeta-cleared", Msg.unparsed("player", target.name()),
                            Msg.number("count", count == null ? 0 : count)));
                }))
                .exceptionally(error -> {
                    failed(c.getSource().getSender(), "Clearing player metadata", error);
                    return null;
                });
        return Cmds.OK;
    }

    private int increment(CommandContext<CommandSourceStack> c, String amount) throws CommandSyntaxException {
        String key = UserMetaValues.normalizeKey(StringArgumentType.getString(c, "key"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "placeholders.usermeta-invalid-key"));
        target(c).thenCompose(target -> store.increment(target.uuid(), key, amount)
                .thenAccept(value -> {
                    if (value.isEmpty()) {
                        onSender(target.sender(), () -> ctx.messages().send(target.sender(),
                                "placeholders.usermeta-not-number", Msg.unparsed("player", target.name()),
                                Msg.unparsed("key", key)));
                        return;
                    }
                    changed(target.uuid());
                    onSender(target.sender(), () -> ctx.messages().send(target.sender(),
                            "placeholders.usermeta-set", Msg.unparsed("player", target.name()),
                            Msg.unparsed("key", key), Msg.unparsed("value", value.get()),
                            Msg.unparsed("mode", "increment")));
                }))
                .exceptionally(error -> {
                    failed(c.getSource().getSender(), "Incrementing metadata " + key, error);
                    return null;
                });
        return Cmds.OK;
    }

    // ---- target ----------------------------------------------------------------------------------

    private CompletableFuture<Target> target(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(name);
        if (!self && !sender.hasPermission(PERM_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", PERM_OTHERS));
        }
        return store.account(name).thenApply(found -> {
            if (found.isEmpty()) {
                throw new MissingUser(name);
            }
            return new Target(sender, found.get().uuid(), found.get().name());
        });
    }

    private static final class MissingUser extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String name;

        MissingUser(String name) {
            super(name);
            this.name = name;
        }
    }

    private void changed(UUID uuid) {
        if (onChanged != null) {
            onChanged.accept(uuid);
        }
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void failed(CommandSender sender, String action, Throwable error) {
        Throwable cause = unwrapped(error);
        if (cause instanceof MissingUser missing) {
            onSender(sender, () -> ctx.messages().send(sender, "core.user-not-found",
                    Msg.unparsed("name", missing.name)));
            return;
        }
        ctx.logger().warn("{} failed for {}", action, sender.getName(), cause);
        onSender(sender, () -> ctx.messages().send(sender, "core.error"));
    }

    private static Throwable unwrapped(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
