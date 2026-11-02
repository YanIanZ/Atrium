package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.inventory.ItemStack;

/**
 * Serializes vault contents with Paper's item bytes. Positions are preserved (an empty slot is stored as a
 * null entry), so a vault looks exactly as the player left it.
 *
 * <p>A blob that cannot be decoded is rejected, never guessed: the caller keeps the row untouched and hands the
 * player an empty vault, so a corrupt row cannot silently be overwritten with fewer items.
 */
final class VaultItems {
    private VaultItems() {}

    static byte[] encode(List<ItemStack> items) {
        return ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[items.size()]));
    }

    /**
     * Decodes a stored vault into exactly {@code slots} entries. Extra stored slots become overflow entries,
     * missing ones are empty slots.
     *
     * @throws IllegalArgumentException when the stored bytes are not a valid item list
     */
    static VaultLayout.Fit<ItemStack> decode(byte[] bytes, int slots) {
        ItemStack[] decoded;
        try {
            decoded = ItemStack.deserializeItemsFromBytes(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("corrupt vault item data", e);
        }
        List<ItemStack> items = new ArrayList<>(Arrays.asList(decoded));
        return VaultLayout.fit(items, slots);
    }
}
