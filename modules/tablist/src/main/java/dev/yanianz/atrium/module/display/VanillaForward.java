package dev.yanianz.atrium.module.display;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.yanianz.atrium.core.command.Cmds;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/**
 * Paper replaces a vanilla root command when a plugin registers the same name. The display modules own
 * {@code /scoreboard} and {@code /bossbar} (for their toggle sub-command), so every other argument is handed
 * to the namespaced vanilla command ({@code minecraft:scoreboard ...}) with the sender's own permissions.
 * The namespaced form keeps working with tab completion.
 */
public final class VanillaForward {
    private VanillaForward() {}

    public static String permission(String command) {
        return "minecraft.command." + command;
    }

    public static boolean canUseVanilla(CommandSender sender, String command) {
        return sender.hasPermission(permission(command));
    }

    /** Greedy child that forwards {@code /<command> <anything>} to {@code /minecraft:<command> <anything>}. */
    public static RequiredArgumentBuilder<CommandSourceStack, String> child(String command) {
        return Commands.argument("vanilla-arguments", StringArgumentType.greedyString())
                .requires(source -> canUseVanilla(source.getSender(), command))
                .executes(c -> {
                    String arguments = StringArgumentType.getString(c, "vanilla-arguments");
                    boolean handled = Bukkit.dispatchCommand(c.getSource().getSender(), "minecraft:" + command + " " + arguments);
                    return handled ? Cmds.OK : 0;
                });
    }
}
