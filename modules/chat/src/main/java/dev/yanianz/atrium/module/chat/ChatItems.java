package dev.yanianz.atrium.module.chat;

import java.util.Locale;
import java.util.OptionalInt;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Resolves the server-side item behind an {@code [item:slot]} token. */
final class ChatItems {
    private ChatItems() {}

    /**
     * Inventory index for a slot name. {@code -1} means the item in the main hand; empty means the token is
     * unknown and should stay literal text.
     */
    static OptionalInt slotIndex(String slot) {
        if (slot == null || slot.isBlank()) {
            return OptionalInt.of(-1);
        }
        String name = slot.trim().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "hand", "main", "mainhand", "main_hand" -> OptionalInt.of(-1);
            case "offhand", "off", "off_hand" -> OptionalInt.of(40);
            case "head", "helmet" -> OptionalInt.of(39);
            case "chest", "chestplate", "body" -> OptionalInt.of(38);
            case "legs", "leggings" -> OptionalInt.of(37);
            case "feet", "boots" -> OptionalInt.of(36);
            default -> {
                try {
                    int index = Integer.parseInt(name);
                    yield index >= 0 && index <= 40 ? OptionalInt.of(index) : OptionalInt.empty();
                } catch (NumberFormatException e) {
                    yield OptionalInt.empty();
                }
            }
        };
    }

    static Component render(Player player, String slot) {
        OptionalInt index = slotIndex(slot);
        if (index.isEmpty()) {
            return null;
        }
        ItemStack stack = index.getAsInt() < 0
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItem(index.getAsInt());
        if (stack == null || stack.getType().isAir()) {
            return Component.text("[" + (slot == null || slot.isBlank() ? "item" : slot) + "]");
        }
        String name = PlainTextComponentSerializer.plainText().serialize(stack.displayName());
        if (name.isBlank()) {
            name = pretty(stack.getType());
        }
        Component label = Component.text("[" + name + "]");
        try {
            return label.hoverEvent(stack.asHoverEvent());
        } catch (RuntimeException e) {
            return label;
        }
    }

    static String pretty(Material material) {
        String raw = material == null ? "air" : material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder out = new StringBuilder(raw.length());
        boolean start = true;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            out.append(start ? Character.toUpperCase(c) : c);
            start = c == ' ';
        }
        return out.toString();
    }
}
