package dev.yanianz.atrium.core.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;

/** A root command to register with Paper's Brigadier command system. */
public record CommandSpec(LiteralCommandNode<CommandSourceStack> node, String description, List<String> aliases) {
    public CommandSpec {
        aliases = List.copyOf(aliases);
    }

    public static CommandSpec of(LiteralCommandNode<CommandSourceStack> node, String description, String... aliases) {
        return new CommandSpec(node, description, List.of(aliases));
    }

    public String name() {
        return node.getLiteral();
    }
}
