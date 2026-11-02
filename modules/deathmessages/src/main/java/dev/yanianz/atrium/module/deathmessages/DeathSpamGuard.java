package dev.yanianz.atrium.module.deathmessages;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses repeated death messages for the same key inside a time window. The first
 * {@code maxPerWindow} messages pass, further ones are hidden from the broadcast (the victim still sees
 * their own message). A window or a count of zero disables the guard.
 */
public final class DeathSpamGuard {
    private final Map<String, Deque<Long>> records = new ConcurrentHashMap<>();

    /**
     * @param key          usually victim + damage cause + killer
     * @param nowMillis    current time
     * @param windowMillis length of the window; {@code <= 0} means "never suppress"
     * @param maxPerWindow messages allowed per window; {@code <= 0} means "never suppress"
     * @return true when the message may be broadcast
     */
    public boolean allow(String key, long nowMillis, long windowMillis, int maxPerWindow) {
        if (windowMillis <= 0 || maxPerWindow <= 0 || key == null) {
            return true;
        }
        Deque<Long> times = records.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            long oldest = nowMillis - windowMillis;
            while (!times.isEmpty() && times.peekFirst() <= oldest) {
                times.removeFirst();
            }
            if (times.size() >= maxPerWindow) {
                return false;
            }
            times.addLast(nowMillis);
            return true;
        }
    }

    public void clear(String key) {
        records.remove(key);
    }

    /** Drops every window of one victim (their key starts with the UUID). */
    public void clearFor(String victimKeyPrefix) {
        if (victimKeyPrefix == null) {
            return;
        }
        records.keySet().removeIf(key -> key.startsWith(victimKeyPrefix));
    }

    public void clearAll() {
        records.clear();
    }
}
