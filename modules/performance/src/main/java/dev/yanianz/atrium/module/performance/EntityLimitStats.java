package dev.yanianz.atrium.module.performance;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Denied-spawn counters, safe to update from parallel region threads. */
final class EntityLimitStats {
    record Snapshot(long total, long animals, long monsters, long items, long minecarts) {}

    private final Map<EntityCategory, LongAdder> denied = new EnumMap<>(EntityCategory.class);

    EntityLimitStats() {
        for (EntityCategory category : EntityCategory.values()) {
            denied.put(category, new LongAdder());
        }
    }

    void record(EntityCategory category) {
        if (category != null) {
            denied.get(category).increment();
        }
    }

    Snapshot snapshot() {
        long animals = denied.get(EntityCategory.ANIMALS).sum();
        long monsters = denied.get(EntityCategory.MONSTERS).sum();
        long items = denied.get(EntityCategory.ITEMS).sum();
        long minecarts = denied.get(EntityCategory.MINECARTS).sum();
        return new Snapshot(animals + monsters + items + minecarts, animals, monsters, items, minecarts);
    }
}
