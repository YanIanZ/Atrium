package dev.yanianz.atrium.module.deathmessages;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short recent-damage history per player, used to explain a death that was caused by a previous hit
 * (for example falling after a knockback). Newest hit first; only hits inside the configured window count.
 *
 * <p>Only the victim's own thread writes an entry, and the map is concurrent because deaths of different
 * players run on different region threads.
 */
public final class DamageHistory {
    /** One damage event. The weapon is resolved at death time (inventory access must stay on the owner's thread). */
    public record Hit(UUID attacker, String attackerName, String block, String cause, long atMillis) {}

    private final int maximum;
    private final Map<UUID, Deque<Hit>> logs = new ConcurrentHashMap<>();

    public DamageHistory(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("Damage history size must be positive");
        }
        this.maximum = maximum;
    }

    /** Records one hit, dropping entries older than {@code windowMillis} and keeping at most {@code maximum}. */
    public void record(UUID victim, Hit hit, long nowMillis, long windowMillis) {
        if (victim == null || hit == null) {
            return;
        }
        Deque<Hit> hits = logs.computeIfAbsent(victim, key -> new ArrayDeque<>());
        synchronized (hits) {
            prune(hits, nowMillis, windowMillis);
            hits.addFirst(hit);
            while (hits.size() > maximum) {
                hits.removeLast();
            }
        }
    }

    /** The newest hit inside the window. */
    public Optional<Hit> latest(UUID victim, long nowMillis, long windowMillis) {
        Deque<Hit> hits = logs.get(victim);
        if (hits == null) {
            return Optional.empty();
        }
        synchronized (hits) {
            prune(hits, nowMillis, windowMillis);
            return Optional.ofNullable(hits.peekFirst());
        }
    }

    /**
     * The newest hit that differs from {@code current} in attacker, block or cause. This is the
     * "while fleeing from …" record: the same attacker with the same weapon is not interesting.
     */
    public Optional<Hit> previous(UUID victim, Hit current, long nowMillis, long windowMillis) {
        Deque<Hit> hits = logs.get(victim);
        if (hits == null || current == null) {
            return Optional.empty();
        }
        synchronized (hits) {
            prune(hits, nowMillis, windowMillis);
            for (Hit hit : hits) {
                boolean same = java.util.Objects.equals(hit.attacker(), current.attacker())
                        && java.util.Objects.equals(hit.block(), current.block())
                        && java.util.Objects.equals(hit.cause(), current.cause());
                if (!same) {
                    return Optional.of(hit);
                }
            }
            return Optional.empty();
        }
    }

    public void clear(UUID victim) {
        logs.remove(victim);
    }

    public void clearAll() {
        logs.clear();
    }

    private static void prune(Deque<Hit> hits, long nowMillis, long windowMillis) {
        if (windowMillis <= 0) {
            hits.clear();
            return;
        }
        while (!hits.isEmpty() && nowMillis - hits.peekLast().atMillis() > windowMillis) {
            hits.removeLast();
        }
    }
}
