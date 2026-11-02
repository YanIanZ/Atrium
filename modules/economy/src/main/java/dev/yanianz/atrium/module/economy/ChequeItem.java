package dev.yanianz.atrium.module.economy;

import dev.yanianz.atrium.core.menu.ItemBuilder;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Builds and reads cheque items. The item only carries the note id; the value lives in
 * {@code economy_cheques}, so editing the displayed amount or copying the item changes nothing.
 * The stack size is forced to one, so a note can never be split.
 */
final class ChequeItem {
    private final NamespacedKey key;
    private final Material material;

    ChequeItem(Plugin plugin, Material material) {
        this.key = new NamespacedKey(plugin, "cheque_id");
        this.material = material;
    }

    /** A single non-stackable note carrying {@code id}. */
    ItemStack create(Component name, List<Component> lore, String id) {
        ItemStack stack = ItemBuilder.of(material).name(name).lore(lore).build();
        stack.editMeta(meta -> {
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
        });
        return stack;
    }

    /** The note id, or null when the stack is not a cheque of this plugin. */
    @Nullable String idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        // Only the marker counts: an old note stays redeemable after the configured material changes.
        String id = stack.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null || id.isBlank() ? null : id;
    }
}
