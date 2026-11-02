package dev.yanianz.atrium.module.worth;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Registry;
import org.bukkit.inventory.ItemType;

/**
 * The item materials of this server, captured once while the server thread is running. Imports and price
 * reloads then work on plain names only: the item registry is never touched from an IO thread.
 */
final class PriceMaterials {
    private final Set<String> items;
    private final Map<String, List<String>> legacyIndex;

    private PriceMaterials(Set<String> items, Map<String, List<String>> legacyIndex) {
        this.items = Set.copyOf(items);
        this.legacyIndex = legacyIndex;
    }

    /** Pure factory for tests and for a stored snapshot. */
    static PriceMaterials of(Collection<String> itemNames) {
        Set<String> lower = new HashSet<>();
        List<String> upper = new ArrayList<>();
        for (String name : itemNames) {
            lower.add(name.toLowerCase(Locale.ROOT));
            upper.add(name.toUpperCase(Locale.ROOT));
        }
        return new PriceMaterials(lower, MaterialNames.index(upper));
    }

    static PriceMaterials empty() {
        return new PriceMaterials(Set.of(), Map.of());
    }

    /** Reads Paper's item registry. Call on the server thread (module enable), never from an IO thread. */
    @SuppressWarnings("deprecation")
    static PriceMaterials snapshot() {
        Set<String> lower = new HashSet<>();
        List<String> upper = new ArrayList<>();
        for (ItemType type : Registry.ITEM) {
            String value = type.getKey().getKey();
            if ("air".equals(value)) {
                continue;
            }
            lower.add(value);
            upper.add(value.toUpperCase(Locale.ROOT));
        }
        return new PriceMaterials(lower, MaterialNames.index(upper));
    }

    boolean isItem(String name) {
        return name != null && items.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves a material name written by another plugin to Atrium's lower-case form. Pre-1.13 names
     * ({@code diamondpickaxe}) are matched through the separators-removed index; only unique matches count.
     */
    Optional<String> resolve(String raw, boolean legacyNames) {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.indexOf(':') >= 0) {
            return Optional.empty();
        }
        if (items.contains(name)) {
            return Optional.of(name);
        }
        if (!legacyNames) {
            return Optional.empty();
        }
        return MaterialNames.unique(legacyIndex, name);
    }
}
