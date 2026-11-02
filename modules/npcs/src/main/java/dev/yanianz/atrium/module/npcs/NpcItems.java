package dev.yanianz.atrium.module.npcs;

import java.util.Base64;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Equipment storage: full item data as base64 {@link ItemStack#serializeAsBytes()}. */
final class NpcItems {
    private NpcItems() {}

    static @Nullable String encode(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    static @Nullable ItemStack decode(@Nullable String base64, java.util.function.Consumer<String> warn) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64));
            return item.getType().isAir() ? null : item;
        } catch (RuntimeException e) {
            warn.accept("Ignoring a malformed stored equipment item: " + e);
            return null;
        }
    }
}
