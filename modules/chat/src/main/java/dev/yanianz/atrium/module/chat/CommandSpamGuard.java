package dev.yanianz.atrium.module.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Detects the same command being used over and over inside a short window. */
final class CommandSpamGuard {
    enum Result { ALLOWED, TOO_MANY }

    record Settings(boolean enabled, int maxRepeats, long windowMillis, Set<String> whitelist) {
        Settings {
            maxRepeats = Math.max(1, maxRepeats);
            windowMillis = Math.max(0, windowMillis);
            whitelist = whitelist == null ? Set.of() : Set.copyOf(whitelist);
        }

        static Settings of(ChatConfig.CommandSpam config) {
            if (config == null) {
                return new Settings(false, 3, 0, Set.of());
            }
            Set<String> names = new java.util.LinkedHashSet<>();
            if (config.whitelist != null) {
                for (String entry : config.whitelist) {
                    if (entry != null && !entry.isBlank()) {
                        names.add(entry.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return new Settings(config.enabled, config.maxRepeats, Math.max(0, config.windowSeconds) * 1000L, names);
        }
    }

    private record Entry(String command, long at) {}

    private final Map<UUID, Deque<Entry>> states = new ConcurrentHashMap<>();

    synchronized Result check(UUID player, String commandLine, long nowMillis, Settings settings) {
        if (settings == null || !settings.enabled() || settings.windowMillis() == 0) {
            return Result.ALLOWED;
        }
        String command = label(commandLine);
        if (command.isEmpty() || settings.whitelist().contains(command)) {
            return Result.ALLOWED;
        }
        Deque<Entry> entries = states.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        while (!entries.isEmpty() && nowMillis - entries.peekFirst().at() >= settings.windowMillis()) {
            entries.pollFirst();
        }
        int count = 0;
        for (Entry entry : entries) {
            if (entry.command().equals(command)) {
                count++;
            }
        }
        if (count >= settings.maxRepeats()) {
            return Result.TOO_MANY;
        }
        entries.addLast(new Entry(command, nowMillis));
        return Result.ALLOWED;
    }

    /** First token of a command line, without the leading slash and namespace. */
    static String label(String commandLine) {
        if (commandLine == null) {
            return "";
        }
        String value = commandLine.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return "";
        }
        int space = value.indexOf(' ');
        String label = space < 0 ? value : value.substring(0, space);
        int colon = label.indexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) {
            label = label.substring(colon + 1);
        }
        return label.toLowerCase(Locale.ROOT);
    }

    void forget(UUID player) {
        states.remove(player);
    }

    void clear() {
        states.clear();
    }
}
