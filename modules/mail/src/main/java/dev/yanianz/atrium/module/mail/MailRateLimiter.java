package dev.yanianz.atrium.module.mail;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory per-sender send cooldown. Pure logic so it can be tested with a fake clock. */
final class MailRateLimiter {
    private final Map<UUID, Long> lastSend = new ConcurrentHashMap<>();

    record Decision(boolean allowed, long remainingMillis) {}

    Decision check(UUID sender, long now, long cooldownMillis) {
        if (cooldownMillis <= 0) {
            return new Decision(true, 0);
        }
        boolean[] allowed = {false};
        long[] remaining = {0};
        lastSend.compute(sender, (id, previous) -> {
            if (previous == null || now < previous || now - previous >= cooldownMillis) {
                allowed[0] = true;
                return now;
            }
            remaining[0] = cooldownMillis - (now - previous);
            return previous;
        });
        return new Decision(allowed[0], Math.max(0, remaining[0]));
    }

    void forget(UUID sender) {
        lastSend.remove(sender);
    }

    void clear() {
        lastSend.clear();
    }
}
