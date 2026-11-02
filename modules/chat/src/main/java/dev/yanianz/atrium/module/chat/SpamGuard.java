package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player rate limit, duplicate check and similarity check. Pure and easy to test with an explicit clock. */
final class SpamGuard {
    enum Result { ALLOWED, TOO_FAST, DUPLICATE, SIMILAR }

    record Settings(boolean enabled, long minimumIntervalMillis, long duplicateWindowMillis,
            boolean similarityEnabled, int similarityPercentage, int similarityMinAmount, long similarityWindowMillis) {
        Settings {
            similarityPercentage = Math.max(1, Math.min(100, similarityPercentage));
            similarityMinAmount = Math.max(1, similarityMinAmount);
        }

        /** Keeps the old three-argument shape (similarity off) for callers and tests. */
        Settings(boolean enabled, long minimumIntervalMillis, long duplicateWindowMillis) {
            this(enabled, minimumIntervalMillis, duplicateWindowMillis, false, 80, 2, 0);
        }

        static Settings of(ChatConfig.AntiSpam config) {
            if (config == null) {
                return new Settings(false, 0, 0);
            }
            return new Settings(config.enabled, Math.max(0, config.minimumIntervalMillis),
                    Math.max(0, config.duplicateWindowSeconds) * 1000L,
                    config.similarityEnabled, config.similarityPercentage, config.similarityMinAmount,
                    Math.max(0, config.duplicateWindowSeconds) * 1000L);
        }
    }

    private record Entry(String text, long at) {}

    private record State(long lastAcceptedAt, List<Entry> recent) {}

    private static final int MAX_RECENT = 10;

    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    synchronized Result check(UUID player, String plain, long nowMillis, Settings settings) {
        if (settings == null || !settings.enabled()) {
            return Result.ALLOWED;
        }
        State previous = states.get(player);
        String normalized = ChatFilter.normalize(plain).text();
        long window = Math.max(settings.duplicateWindowMillis(), settings.similarityWindowMillis());
        List<Entry> recent = new ArrayList<>();
        if (previous != null) {
            if (settings.minimumIntervalMillis() > 0
                    && nowMillis - previous.lastAcceptedAt() < settings.minimumIntervalMillis()) {
                return Result.TOO_FAST;
            }
            for (Entry entry : previous.recent()) {
                if (nowMillis - entry.at() < window) {
                    recent.add(entry);
                }
            }
            if (settings.duplicateWindowMillis() > 0) {
                for (Entry entry : recent) {
                    if (nowMillis - entry.at() < settings.duplicateWindowMillis()
                            && normalized.equals(entry.text())) {
                        return Result.DUPLICATE;
                    }
                }
            }
            if (settings.similarityEnabled() && settings.similarityWindowMillis() > 0) {
                int similar = 0;
                for (Entry entry : recent) {
                    if (nowMillis - entry.at() < settings.similarityWindowMillis()
                            && similarity(normalized, entry.text()) * 100 >= settings.similarityPercentage()) {
                        similar++;
                    }
                }
                if (similar >= settings.similarityMinAmount()) {
                    return Result.SIMILAR;
                }
            }
        }
        recent.add(new Entry(normalized, nowMillis));
        if (recent.size() > MAX_RECENT) {
            recent = new ArrayList<>(recent.subList(recent.size() - MAX_RECENT, recent.size()));
        }
        states.put(player, new State(nowMillis, recent));
        return Result.ALLOWED;
    }

    /** Normalised Levenshtein similarity: 1.0 for equal strings, 0.0 for completely different ones. */
    static double similarity(String first, String second) {
        String a = first == null ? "" : first;
        String b = second == null ? "" : second;
        int longest = Math.max(a.length(), b.length());
        if (longest == 0) {
            return 1.0;
        }
        return (longest - distance(a, b)) / (double) longest;
    }

    private static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            char left = Character.toLowerCase(a.charAt(i - 1));
            for (int j = 1; j <= b.length(); j++) {
                int cost = left == Character.toLowerCase(b.charAt(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    void forget(UUID player) {
        states.remove(player);
    }

    void clear() {
        states.clear();
    }
}
