package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/**
 * Bukkit bridge for {@link StackDelta}: item identity, the contributed-stack diff and its storage form.
 *
 * <p>Item identity is the serialized stack with a size of one, so stacks of the same item and metadata are
 * treated as interchangeable no matter which slot they sit in or how they were split. Serialization happens
 * on the I/O executor, never on a region thread.
 */
final class ItemStackDelta {
    private static final StackDelta.SlotView<ItemStack> VIEW = new StackDelta.SlotView<>() {
        @Override
        public boolean empty(ItemStack slot) {
            return slot == null || slot.isEmpty() || slot.getType().isAir();
        }

        @Override
        public String key(ItemStack slot) {
            return keyOf(slot);
        }

        @Override
        public int amount(ItemStack slot) {
            return slot.getAmount();
        }
    };

    private ItemStackDelta() {}

    /**
     * The stacks an administrator added between the base copy and the edited copy: every amount that is present
     * in {@code edited} and not in {@code base}. Moved and split stacks contribute nothing.
     */
    static List<ItemStack> added(List<ItemStack> base, List<ItemStack> edited) {
        StackDelta.Diff diff = StackDelta.of(base, edited, VIEW);
        if (diff.added().isEmpty()) {
            return List.of();
        }
        Map<String, ItemStack> representatives = new HashMap<>();
        for (ItemStack stack : edited) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            representatives.putIfAbsent(keyOf(stack), stack.clone());
        }
        return rebuild(diff.added(), representatives);
    }

    /** Rebuilds ordinary stacks from an added-count map, using one representative stack per identity. */
    static List<ItemStack> rebuild(Map<String, Integer> added, Map<String, ItemStack> representatives) {
        List<ItemStack> stacks = new ArrayList<>();
        added.forEach((key, amount) -> {
            ItemStack representative = representatives.get(key);
            if (representative == null) {
                return;
            }
            int maxStack = Math.max(1, representative.getMaxStackSize());
            for (int portion : RefundPlan.portions(amount, maxStack)) {
                ItemStack stack = representative.clone();
                stack.setAmount(portion);
                stacks.add(stack);
            }
        });
        return List.copyOf(stacks);
    }

    /** The stored form of a contributed-stack list; {@code null} for an empty list. */
    static byte[] encode(List<ItemStack> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[items.size()]));
    }

    static List<ItemStack> decode(byte[] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        ItemStack[] decoded = ItemStack.deserializeItemsFromBytes(data);
        List<ItemStack> items = new ArrayList<>(decoded.length);
        for (ItemStack stack : decoded) {
            if (stack != null && !stack.isEmpty()) {
                items.add(stack);
            }
        }
        return List.copyOf(items);
    }

    private static String keyOf(ItemStack slot) {
        if (slot == null || slot.isEmpty() || slot.getType().isAir()) {
            return "empty";
        }
        ItemStack single = slot.clone();
        single.setAmount(1);
        return Base64.getEncoder().encodeToString(
                ItemStack.serializeItemsAsBytes(new ItemStack[] {single}));
    }
}
