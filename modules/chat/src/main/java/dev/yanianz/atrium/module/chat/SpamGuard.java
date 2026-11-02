package dev.yanianz.atrium.module.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player rate limit and duplicate check. Pure and easy to test with an explicit clock. */
final class SpamGuard {
    enum Result { ALLOWED, TOO_FAST, DUPLICATE }

    record Settings(boolean enabled, long minimumIntervalMillis, long duplicateWindowMillis) {
        static Settings of(ChatConfig.AntiSpam config) {
            if (config == null) {
                return new Settings(false, 0, 0);
            }
            return new Settings(config.enabled, Math.max(0, config.minimumIntervalMillis),
                    Math.max(0, config.duplicateWindowSeconds) * 1000L);
        }
    }

    private record State(long lastAcceptedAt, String lastMessage, long lastMessageAt) {}

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    synchronized Result check(UUID player, String plain, long nowMillis, Settings settings) {
        if (settings == null || !settings.enabled()) {
            return Result.ALLOWED;
        }
        State previous = states.get(player);
        String normalized = ChatFilter.normalize(plain).text();
        if (previous != null) {
            if (settings.minimumIntervalMillis() > 0
                    && nowMillis - previous.lastAcceptedAt() < settings.minimumIntervalMillis()) {
                return Result.TOO_FAST;
            }
            if (settings.duplicateWindowMillis() > 0 && normalized.equals(previous.lastMessage())
                    && nowMillis - previous.lastMessageAt() < settings.duplicateWindowMillis()) {
                return Result.DUPLICATE;
            }
        }
        states.put(player, new State(nowMillis, normalized, nowMillis));
        return Result.ALLOWED;
    }

    void forget(UUID player) {
        states.remove(player);
    }

    void clear() {
        states.clear();
    }
}
