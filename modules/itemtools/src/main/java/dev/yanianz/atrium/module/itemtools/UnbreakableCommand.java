package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.message.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * {@code /unbreakable [offhand] [player]}: toggles the unbreakable component of the held item. Toggling
 * instead of only setting means a wrongly marked item can be repaired with the same command, and the reply
 * always states the resulting state.
 */
final class UnbreakableCommand {
    private final ItemSupport support;

    UnbreakableCommand(ItemSupport support) {
        this.support = support;
    }

    void register() {
        support.register(Cmds.literal("unbreakable", "atrium.unbreakable")
                        .executes(c -> apply(c, EquipmentSlot.HAND))
                        .then(Cmds.literal("offhand", "atrium.unbreakable")
                                .executes(c -> apply(c, EquipmentSlot.OFF_HAND))
                                .then(Commands.argument("target", ArgumentTypes.player())
                                        .executes(c -> apply(c, EquipmentSlot.OFF_HAND))))
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(c -> apply(c, EquipmentSlot.HAND))),
                "Make the held item unbreakable, or breakable again");
    }

    private int apply(CommandContext<CommandSourceStack> c, EquipmentSlot slot) throws CommandSyntaxException {
        Player target = support.cmds().selfOrOther(c, "target", "atrium.unbreakable.others");
        CommandSender sender = c.getSource().getSender();
        support.onPlayer(target, () -> toggle(target, sender, slot));
        return Cmds.OK;
    }

    private void toggle(Player target, CommandSender sender, EquipmentSlot slot) {
        ItemStack item = slot == EquipmentSlot.OFF_HAND
                ? target.getInventory().getItemInOffHand()
                : target.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            support.replyNow(target, "empty-hand");
            if (!sender.equals(target)) {
                support.reply(sender, "empty-hand");
            }
            return;
        }
        ItemStack updated = item.clone();
        ItemMeta meta = updated.getItemMeta();
        boolean next = !meta.isUnbreakable();
        meta.setUnbreakable(next);
        if (!updated.setItemMeta(meta)) {
            support.replyNow(target, "failed");
            return;
        }
        if (slot == EquipmentSlot.OFF_HAND) {
            target.getInventory().setItemInOffHand(updated);
        } else {
            target.getInventory().setItemInMainHand(updated);
        }
        support.replyNow(target, next ? "unbreakable-on" : "unbreakable-off");
        if (!sender.equals(target)) {
            support.reply(sender, "unbreakable-other", Msg.unparsed("player", target.getName()),
                    Msg.component("state", support.ctx().messages().render(sender,
                            next ? "itemtools.unbreakable-state-on" : "itemtools.unbreakable-state-off")));
        }
    }
}
