package dev.yanianz.atrium.module.tablist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tab list order values for Paper's {@code Player#setPlayerListOrder}. The client lists higher values first
 * and breaks ties by account name, so group order alone only needs one value per group.
 */
final class TabOrder {
    /** What one player contributes to sorting: their group position (0 = top) and visible name. */
    record Entry(UUID id, int groupIndex, String sortName) {}

    private static final Comparator<Entry> ORDER = Comparator.comparingInt(Entry::groupIndex)
            .thenComparing(e -> e.sortName().toLowerCase(Locale.ROOT))
            .thenComparing(Entry::id);

    private TabOrder() {}

    /** Order by group only: the first group gets {@code groupCount}, players without a group get 0. */
    static int groupOrder(int groupIndex, int groupCount) {
        return Math.max(0, groupCount - Math.min(groupIndex, groupCount));
    }

    /**
     * Distinct order values by (group, visible name, uuid): the first entry gets {@code entries.size()} and
     * the last gets 1, so every value stays positive and group boundaries are kept.
     */
    static Map<UUID, Integer> byGroupAndName(Collection<Entry> entries) {
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(ORDER);
        Map<UUID, Integer> orders = new HashMap<>(sorted.size() * 2);
        for (int i = 0; i < sorted.size(); i++) {
            orders.put(sorted.get(i).id(), sorted.size() - i);
        }
        return orders;
    }
}
