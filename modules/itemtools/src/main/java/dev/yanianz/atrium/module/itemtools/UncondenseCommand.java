package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /uncondense [item]}: the exact reverse of {@code /condense}, limited to the same whitelist. Blocks
 * are removed first and only the amount that was really removed is turned into loose materials, so a
 * changed inventory can never create items. Customised stacks are skipped by the same config option as
 * {@code /condense}.
 */
final class UncondenseCommand {
    private record Result(int used, int produced, int dropped) { }

    private final ItemSupport support;
    private final ConfigHandle<ItemToolsConfig> config;

    UncondenseCommand(ItemSupport support, ConfigHandle<ItemToolsConfig> config) {
        this.support = support;
        this.config = config;
    }

    void register() {
        support.register(Cmds.literal("uncondense", "atrium.uncondense")
                        .executes(c -> run(c, null))
                        .then(Commands.argument("item", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> CondenseTable.outputs().stream()
                                        .map(material -> material.getKey().getKey()).toList()))
                                .executes(c -> run(c, StringArgumentType.getString(c, "item")))),
                "Split blocks back into the loose materials they were made from");
    }

    private int run(CommandContext<CommandSourceStack> c, String itemName) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        Material selected = null;
        if (itemName != null) {
            selected = Material.matchMaterial(itemName);
            if (selected == null || CondenseTable.reverseRecipeFor(selected).isEmpty()) {
                throw support.cmds().fail(c.getSource(), "itemtools.uncondense-invalid",
                        Msg.unparsed("item", itemName));
            }
        }
        Material chosen = selected;
        support.onPlayer(player, () -> uncondense(player, chosen));
        return Cmds.OK;
    }

    private void uncondense(Player player, Material selected) {
        int used = 0;
        int produced = 0;
        int dropped = 0;
        if (selected != null) {
            var recipe = CondenseTable.reverseRecipeFor(selected).orElse(null);
            if (recipe == null) {
                support.replyNow(player, "uncondense-invalid", Msg.unparsed("item", selected.getKey().getKey()));
                return;
            }
            Result result = splitOnce(player, recipe);
            used += result.used();
            produced += result.produced();
            dropped += result.dropped();
        } else {
            for (Material output : CondenseTable.outputs()) {
                var recipe = CondenseTable.reverseRecipeFor(output).orElse(null);
                if (recipe == null) {
                    continue;
                }
                Result result = splitOnce(player, recipe);
                used += result.used();
                produced += result.produced();
                dropped += result.dropped();
            }
        }
        if (used == 0) {
            support.replyNow(player, "uncondense-none");
            return;
        }
        support.replyNow(player, "uncondense-done", Msg.number("used", used), Msg.number("produced", produced));
        if (dropped > 0) {
            support.replyNow(player, "uncondense-dropped", Msg.number("dropped", dropped));
        }
    }

    private Result splitOnce(Player player, CondenseTable.Recipe recipe) {
        Inventory inventory = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        int available = 0;
        boolean plainOnly = config.get().condenseSkipCustomised;
        int storage = inventory.getStorageContents().length;
        for (int slot = 0; slot < storage; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getType() != recipe.to()) {
                continue;
            }
            if (plainOnly && !ItemValues.plain(stack)) {
                continue;
            }
            slots.add(slot);
            available += stack.getAmount();
        }
        CondenseTable.Plan plan = CondenseTable.reversePlan(available, recipe.ratio());
        if (plan.consumed() == 0) {
            return new Result(0, 0, 0);
        }
        int remaining = plan.consumed();
        for (int slot : slots) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getType() != recipe.to()) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(slot, stack.getAmount() <= 0 ? null : stack);
            remaining -= take;
        }
        int removed = plan.consumed() - remaining;
        if (remaining > 0) {
            support.ctx().logger().warn(
                    "Uncondense removed {} fewer {} than planned for {}; the inventory changed unexpectedly",
                    remaining, recipe.to(), player.getUniqueId());
        }
        // Only blocks that were really removed produce output, so nothing is created out of a race.
        int output = removed * recipe.ratio();
        if (output <= 0) {
            return new Result(removed, 0, 0);
        }
        int dropped = ItemValues.giveOrDrop(player, ItemValues.stacks(recipe.from(), output));
        return new Result(removed, output, dropped);
    }
}
