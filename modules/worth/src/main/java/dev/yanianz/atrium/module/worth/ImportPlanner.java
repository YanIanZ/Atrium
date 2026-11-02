package dev.yanianz.atrium.module.worth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Turns parsed import candidates into Atrium price entries. Pure: the material snapshot and the existing
 * entries are passed in, so the whole mapping (idempotency, overwrite, id choice, duplicates) is testable.
 */
final class ImportPlanner {
    record Result(List<PriceEntry> write, List<ImportSkip> skipped, int imported, int updated, int existing) {
        Result {
            write = List.copyOf(write);
            skipped = List.copyOf(skipped);
        }
    }

    private ImportPlanner() {}

    static Result plan(List<ImportCandidate> candidates, List<ImportSkip> parsedSkips,
            List<PriceEntry> existingEntries, PriceMaterials materials, boolean legacyNames, boolean overwrite) {
        Map<Identity, PriceEntry> existing = new HashMap<>();
        Set<String> usedIds = new HashSet<>();
        for (PriceEntry entry : existingEntries) {
            existing.putIfAbsent(Identity.of(entry), entry);
            usedIds.add(entry.id());
        }
        List<PriceEntry> write = new ArrayList<>();
        List<ImportSkip> skipped = new ArrayList<>(parsedSkips);
        Set<Identity> queued = new HashSet<>();
        int imported = 0;
        int updated = 0;
        int alreadySet = 0;
        for (ImportCandidate candidate : candidates) {
            Optional<String> material = materials.resolve(candidate.material(), legacyNames);
            if (material.isEmpty()) {
                skipped.add(new ImportSkip(candidate.origin(), "unknown-material", candidate.material()));
                continue;
            }
            Identity identity = Identity.of(material.get(), candidate.customModelData(), candidate.name());
            if (!queued.add(identity)) {
                skipped.add(new ImportSkip(candidate.origin(), "duplicate",
                        material.get() + " is already priced by another line of this import"));
                continue;
            }
            PriceEntry match = existing.get(identity);
            if (match != null && !overwrite) {
                alreadySet++;
                continue;
            }
            String id;
            if (match != null) {
                id = match.id();
                updated++;
            } else if (identity.isPlain() && !usedIds.contains(material.get())) {
                id = material.get();
                imported++;
            } else {
                id = freeId(usedIds, material.get());
                imported++;
            }
            usedIds.add(id);
            write.add(new PriceEntry(id, material.get(), candidate.unitPrice(), null,
                    candidate.customModelData(), candidate.name(), null, Set.of()));
        }
        return new Result(write, skipped, imported, updated, alreadySet);
    }

    private static String freeId(Set<String> used, String material) {
        for (int i = 1; ; i++) {
            String id = material + "-" + i;
            if (!used.contains(id)) {
                return id;
            }
        }
    }

    /** The identifying data of a price entry, used for idempotency. */
    record Identity(String material, @Nullable String itemModel,
            @Nullable List<String> customModelData,
            @Nullable String name,
            @Nullable Map<String, Integer> enchantments,
            Set<PriceEntry.Attribute> accept) {

        static Identity of(PriceEntry entry) {
            return new Identity(entry.material(), entry.itemModel(), entry.customModelData(), entry.name(),
                    entry.enchantments(), entry.accept());
        }

        static Identity of(String material, @Nullable List<String> customModelData,
                @Nullable String name) {
            return new Identity(material, null, customModelData, name, null, Set.of());
        }

        boolean isPlain() {
            return itemModel == null && customModelData == null && name == null && enchantments == null
                    && accept.isEmpty();
        }
    }
}
