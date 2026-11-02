package dev.yanianz.atrium.module.importer;

import java.util.List;
import java.util.Map;

/**
 * One kit item as read from a foreign kit file, before it becomes an {@code ItemStack}. Either
 * {@code serialized} holds the full item ({@code ItemStack#serializeAsBytes}: CMI's binary items and
 * EssentialsX's {@code @}-prefixed items), or the common subset is set: material, amount, name, lore and
 * enchantments. {@code unsupported} names item data that cannot be carried over (reported per item).
 *
 * @param slot          target slot (0-40), or -1 for "next free storage slot"
 * @param name          display name in legacy {@code &}/{@code §} colour codes, or {@code null}
 * @param enchantments  enchantment key (any spelling) to level
 * @param problem       why the item cannot be imported at all, or {@code null}
 */
record KitItemSpec(int slot, String material, int amount, String name, List<String> lore,
        Map<String, Integer> enchantments, byte[] serialized, List<String> unsupported, String problem) {
    KitItemSpec {
        lore = lore == null ? List.of() : List.copyOf(lore);
        enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        unsupported = unsupported == null ? List.of() : List.copyOf(unsupported);
    }

    static KitItemSpec broken(int slot, String problem) {
        return new KitItemSpec(slot, null, 0, null, List.of(), Map.of(), null, List.of(), problem);
    }

    static KitItemSpec serialized(int slot, byte[] bytes) {
        return new KitItemSpec(slot, null, 0, null, List.of(), Map.of(), bytes, List.of(), null);
    }

    boolean ok() {
        return problem == null;
    }

    /** Short label for problem lines: {@code diamond_sword x1}, or {@code serialized item}. */
    String label() {
        if (serialized != null) {
            return "serialized item";
        }
        return (material == null ? "?" : material) + " x" + amount;
    }
}
