package dev.yanianz.atrium.module.playerplus;

import java.util.Collection;
import java.util.Locale;

/** Pure rules used by the cuff listener: the confinement area and the command whitelist. */
final class CuffRules {
    private CuffRules() {}

    /** Whether a position is inside the sphere around the cuff point. */
    static boolean inside(CuffPoint point, String world, double x, double y, double z, double radius) {
        if (point == null || world == null || !point.world().equals(world)) {
            return false;
        }
        double limit = Math.max(0, radius);
        double dx = x - point.x();
        double dy = y - point.y();
        double dz = z - point.z();
        return dx * dx + dy * dy + dz * dz <= limit * limit + 1e-9;
    }

    /**
     * Whether a raw command line is allowed for a cuffed player. A leading slash is ignored, the namespace
     * of the command (for example {@code minecraft:}) is stripped and matching is word by word, so
     * {@code msg} allows {@code /msg Steve hi} but not {@code /msgtoggle}.
     */
    static boolean commandAllowed(String rawLine, Collection<String> allowed) {
        String[] words = words(rawLine);
        if (words.length == 0) {
            return true;
        }
        for (String entry : allowed) {
            String[] pattern = words(entry);
            if (pattern.length == 0 || pattern.length > words.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < pattern.length; i++) {
                if (!words[i].equals(pattern[i])) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }

    private static String[] words(String raw) {
        if (raw == null) {
            return new String[0];
        }
        String line = raw.trim();
        while (line.startsWith("/")) {
            line = line.substring(1).trim();
        }
        if (line.isEmpty()) {
            return new String[0];
        }
        String[] parts = line.split("\\s+");
        String first = parts[0].toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        if (colon >= 0 && colon + 1 < first.length()) {
            first = first.substring(colon + 1);
        }
        parts[0] = first;
        for (int i = 1; i < parts.length; i++) {
            parts[i] = parts[i].toLowerCase(Locale.ROOT);
        }
        return parts;
    }
}
