package dev.yanianz.atrium.module.holograms;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DecentHolograms added Folia support in 2.10. Older versions (including the 2.8 API used at compile time)
 * only schedule on the Bukkit main thread and break on Folia. The version is only used to decide whether
 * the engine may be selected, never to choose a code path.
 */
final class DecentHologramsVersion {
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*");

    private DecentHologramsVersion() {}

    static boolean supportsFolia(String version) {
        int[] parsed = parse(version);
        if (parsed == null) {
            return false;
        }
        return parsed[0] > 2 || parsed[0] == 2 && parsed[1] >= 10;
    }

    /** Major/minor/patch, or {@code null} when the version cannot be read. */
    static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(version.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
