package dev.yanianz.atrium.module.skins;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /skin <name> [player]} and {@code /skin reset [player]}. The choice is saved before it is applied and
 * confirmed. A Minecraft account literally named "reset" cannot be used as a skin source.
 */
final class SkinCommands implements TaskHandle {
    private final SkinsModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;
    private volatile boolean cancelled;

    SkinCommands(SkinsModule module) {
        this.module = module;
        this.ctx = module.context();
        this.cmds = ctx.commands();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("skin", SkinsModule.PERM_SKIN)
                .executes(this::usage)
                .then(Cmds.literal("reset")
                        .executes(this::reset)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::reset)))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()))
                        .executes(this::set)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::set)))
                .build(), "Use another account's skin or reset yours"));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "skins.usage-skin");
        return Cmds.OK;
    }

    private int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        if (!SkinNames.valid(name)) {
            throw cmds.fail(c.getSource(), "skins.invalid-name");
        }
        Player target = cmds.selfOrOther(c, "target", SkinsModule.PERM_OTHERS);
        acquireCooldown(c);
        reply(sender, "fetching", Msg.unparsed("name", name));
        module.resolve(name).whenComplete((result, error) -> {
            if (cancelled || module.stopping()) {
                return;
            }
            if (error != null) {
                ctx.logger().warn("Skin lookup for '{}' failed: {}", name, error.toString());
                reply(sender, "fetch-failed");
                return;
            }
            if (result.isEmpty()) {
                reply(sender, "not-found", Msg.unparsed("name", name));
                return;
            }
            SkinsModule.Resolved resolved = result.get();
            SkinChoice choice = new SkinChoice(resolved.name(), resolved.texture(), System.currentTimeMillis());
            store(sender, target, choice, () -> {
                ctx.messages().send(target, "skins.changed", Msg.unparsed("name", resolved.name()));
                if (!sender.equals(target)) {
                    reply(sender, "changed-other", Msg.unparsed("player", target.getName()),
                            Msg.unparsed("name", resolved.name()));
                }
            });
        });
        return Cmds.OK;
    }

    private int reset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player target = cmds.selfOrOther(c, "target", SkinsModule.PERM_OTHERS);
        ctx.scheduler().entity(target, () -> {
            if (cancelled) {
                return;
            }
            SkinChoice current = module.choiceOrNull(target);
            if (current == null) {
                reply(sender, "failed");
                return;
            }
            if (!current.custom()) {
                reply(sender, "no-custom", Msg.unparsed("player", target.getName()));
                return;
            }
            originalTexture(target).whenComplete((original, error) -> {
                if (cancelled || module.stopping()) {
                    return;
                }
                if (error != null) {
                    // The account skin could not be looked up: fall back to the default skin.
                    ctx.logger().warn("Looking up the own skin of {} failed: {}", target.getName(), error.toString());
                }
                SkinTexture texture = error == null ? original.orElse(null) : null;
                store(sender, target, SkinChoice.NONE, texture, () -> {
                    ctx.messages().send(target, "skins.reset");
                    if (!sender.equals(target)) {
                        reply(sender, "reset-other", Msg.unparsed("player", target.getName()));
                    }
                });
            });
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    /** The login texture captured on join, or the account's own skin looked up by name. */
    private CompletableFuture<Optional<SkinTexture>> originalTexture(Player target) {
        Optional<Optional<SkinTexture>> captured = module.original(target.getUniqueId());
        if (captured.isPresent()) {
            return CompletableFuture.completedFuture(captured.get());
        }
        if (!SkinNames.valid(target.getName())) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return module.resolve(target.getName()).thenApply(found -> found.map(SkinsModule.Resolved::texture));
    }

    private void store(CommandSender sender, Player target, SkinChoice choice, Runnable confirm) {
        store(sender, target, choice, choice.texture(), confirm);
    }

    /** Saves the choice, then applies {@code texture} on the target's thread and runs {@code confirm} there. */
    private void store(CommandSender sender, Player target, SkinChoice choice, @Nullable SkinTexture texture,
            Runnable confirm) {
        ctx.scheduler().entity(target, () -> {
            if (cancelled) {
                return;
            }
            AtriumUser user;
            try {
                user = ctx.users().user(target);
                user.update(module.store().component, current -> choice);
            } catch (RuntimeException e) {
                ctx.logger().error("Storing the skin of {} failed", target.getName(), e);
                reply(sender, "failed");
                return;
            }
            ctx.users().saveNow(target.getUniqueId()).whenComplete((unused, error) -> {
                if (cancelled) {
                    return;
                }
                if (error != null) {
                    ctx.logger().error("Saving the skin of {} failed", target.getName(), error);
                    reply(sender, "failed");
                    return;
                }
                ctx.scheduler().entity(target, () -> {
                    if (cancelled) {
                        return;
                    }
                    try {
                        module.apply(target, texture);
                    } catch (RuntimeException e) {
                        ctx.logger().error("Applying the skin of {} failed", target.getName(), e);
                        reply(sender, "failed");
                        return;
                    }
                    confirm.run();
                }, () -> reply(sender, "target-left"));
            });
        }, () -> reply(sender, "target-left"));
    }

    /** Per-player rate limit on lookups; the sender is charged (console and bypass holders are not). */
    private void acquireCooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int seconds = module.config().cooldownSeconds;
        if (seconds <= 0 || !(c.getSource().getSender() instanceof Player player)
                || player.hasPermission(SkinsModule.PERM_BYPASS_COOLDOWN)) {
            return;
        }
        Duration left;
        try {
            left = ctx.cooldowns().tryAcquire(ctx.users().user(player), SkinsModule.COOLDOWN_KEY,
                    Duration.ofSeconds(seconds));
        } catch (IllegalStateException e) {
            throw cmds.fail(c.getSource(), "skins.failed");
        }
        if (!left.isZero()) {
            throw cmds.fail(c.getSource(), "core.cooldown", Msg.duration("time", left));
        }
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    ctx.messages().send(player, "skins." + key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(recipient, "skins." + key, tags);
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
