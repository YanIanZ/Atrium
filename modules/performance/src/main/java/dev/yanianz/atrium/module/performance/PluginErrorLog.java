package dev.yanianz.atrium.module.performance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The last problems of the console, tagged with the code they came from. {@code /consolefilter recent} reads it,
 * so a hidden or noisy line can still be inspected. Pure apart from the clock, so the ring and the attribution
 * are unit tested.
 */
final class PluginErrorLog {
    /** Packages that must never be reported as "the plugin that failed". */
    private static final String[] SERVER_PACKAGES = {
        "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.bukkit.", "net.minecraft.", "io.papermc.",
        "com.mojang.", "org.spongepowered.", "org.apache.", "io.netty.", "org.slf4j.", "net.kyori.",
    };

    record Entry(long time, String level, String logger, String message, String tag) {
        Entry {
            level = level == null ? "" : level;
            logger = logger == null ? "" : logger;
            message = message == null ? "" : message;
            tag = tag == null ? "" : tag;
        }
    }

    private final int capacity;
    private final Object lock = new Object();
    private final Deque<Entry> entries = new ArrayDeque<>();

    PluginErrorLog(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    void add(long time, String level, String logger, String message, String stack) {
        Entry entry = new Entry(time, level, logger, message, tag(logger, stack));
        synchronized (lock) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    /** Newest first, at most {@code count}. */
    List<Entry> recent(int count) {
        List<Entry> all = new ArrayList<>();
        synchronized (lock) {
            all.addAll(entries);
        }
        List<Entry> out = new ArrayList<>(Math.min(count, all.size()));
        for (int i = all.size() - 1; i >= 0 && out.size() < count; i--) {
            out.add(all.get(i));
        }
        return out;
    }

    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    /**
     * The short origin of a record: the first stack frame outside the server, JDK and common libraries, else the
     * logger name. Atrium's own code is reported as {@code Atrium}.
     */
    static String tag(String logger, String stack) {
        String name = logger == null ? "" : logger;
        if (stack != null && !stack.isEmpty()) {
            for (String line : stack.split("\n")) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("at ")) {
                    continue;
                }
                String frame = trimmed.substring(3);
                if (frame.indexOf('.') <= 0) {
                    continue;
                }
                if (!ignored(frame)) {
                    return shortName(frame);
                }
            }
        }
        return shortName(name);
    }

    private static boolean ignored(String frame) {
        for (String prefix : SERVER_PACKAGES) {
            if (frame.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String shortName(String frame) {
        String value = frame;
        if (value.startsWith("dev.yanianz.atrium")) {
            return "Atrium";
        }
        String[] parts = value.split("\\.");
        if (parts.length <= 2) {
            return value.isEmpty() ? "unknown" : value;
        }
        String joined = parts[0] + "." + parts[1];
        return joined.length() > 40 ? joined.substring(0, 40) : joined;
    }

    static String levelKey(String level) {
        return level == null ? "" : level.toUpperCase(Locale.ROOT);
    }
}
