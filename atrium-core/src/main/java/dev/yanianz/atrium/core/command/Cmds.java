package dev.yanianz.atrium.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.MessageComponentSerializer;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Helpers for building Brigadier commands. Failures are reported by throwing
 * {@link CommandSyntaxException} built with {@link #fail}, which Brigadier shows to the sender.
 */
public final class Cmds {
    public static final int OK = Command.SINGLE_SUCCESS;

    private final Messages messages;

    public Cmds(Messages messages) {
        this.messages = messages;
    }

    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name) {
        return Commands.literal(name);
    }

    /** A literal that is only visible and usable with the permission. */
    public static LiteralArgumentBuilder<CommandSourceStack> literal(String name, String permission) {
        return Commands.literal(name).requires(permission(permission));
    }

    public static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }

    /** Fails with a translated message for the sender. */
    public CommandSyntaxException fail(CommandSourceStack source, String key, TagResolver... resolvers) {
        Component message = messages.render(source.getSender(), key, resolvers);
        return new SimpleCommandExceptionType(MessageComponentSerializer.message().serialize(message)).create();
    }

    public Player player(CommandSourceStack source) throws CommandSyntaxException {
        if (source.getExecutor() instanceof Player player) {
            return player;
        }
        if (source.getSender() instanceof Player player) {
            return player;
        }
        throw fail(source, "core.player-only");
    }

    /** Resolves a single-player selector argument. */
    public Player argPlayer(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        List<Player> players = ctx.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (players.isEmpty()) {
            throw fail(ctx.getSource(), "core.player-not-found");
        }
        return players.getFirst();
    }

    /** Resolves a multi-player selector argument (e.g. {@code @a}). */
    public List<Player> argPlayers(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        List<Player> players = ctx.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(ctx.getSource());
        if (players.isEmpty()) {
            throw fail(ctx.getSource(), "core.player-not-found");
        }
        return players;
    }

    /**
     * Target of a "self or other" command. When the argument is present the sender needs
     * {@code othersPermission}, unless the target is the sender. Every command that can act on
     * another player must go through this check.
     */
    public Player selfOrOther(CommandContext<CommandSourceStack> ctx, String argument, String othersPermission)
            throws CommandSyntaxException {
        if (!hasArgument(ctx, argument)) {
            return player(ctx.getSource());
        }
        Player target = argPlayer(ctx, argument);
        CommandSender sender = ctx.getSource().getSender();
        if (!target.equals(sender) && !sender.hasPermission(othersPermission)) {
            throw fail(ctx.getSource(), "core.no-permission-others", Msg.unparsed("permission", othersPermission));
        }
        return target;
    }

    public static boolean hasArgument(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            ctx.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Suggests online players plus known (offline) names from the user service. */
    public static SuggestionProvider<CommandSourceStack> knownPlayers(dev.yanianz.atrium.api.user.UserService users) {
        return (ctx, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(remaining) && seen.add(online.getName())) {
                    builder.suggest(online.getName());
                }
            }
            for (String name : users.knownNames(remaining, 50)) {
                if (seen.add(name)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    /** Suggests from a dynamic list of names, filtered by what the sender typed. */
    public static SuggestionProvider<CommandSourceStack> suggest(Supplier<? extends Collection<String>> names) {
        return (ctx, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String name : names.get()) {
                if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    public Messages messages() {
        return messages;
    }
}
