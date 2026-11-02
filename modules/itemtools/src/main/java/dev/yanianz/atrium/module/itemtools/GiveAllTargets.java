package dev.yanianz.atrium.module.itemtools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recipient arithmetic for the offline part of {@code /giveall}: known accounts minus the players who are
 * online (they already received the item directly), de-duplicated and capped so one command cannot write an
 * unbounded amount of rows.
 */
final class GiveAllTargets {
    private GiveAllTargets() {
    }

    static List<UUID> offlineRecipients(Collection<UUID> known, Set<UUID> online, int limit) {
        if (known == null || known.isEmpty() || limit <= 0) {
            return List.of();
        }
        Set<UUID> onlineIds = online == null ? Set.of() : online;
        LinkedHashSet<UUID> selected = new LinkedHashSet<>();
        for (UUID id : known) {
            if (id == null || onlineIds.contains(id)) {
                continue;
            }
            selected.add(id);
            if (selected.size() >= limit) {
                break;
            }
        }
        return List.copyOf(new ArrayList<>(selected));
    }
}
