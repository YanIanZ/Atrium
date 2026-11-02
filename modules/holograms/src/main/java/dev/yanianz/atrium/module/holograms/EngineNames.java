package dev.yanianz.atrium.module.holograms;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Names of the mirrors in an external hologram plugin. Atrium owns the data, the external plugin only gets a
 * stable derived name: {@code atrium_} plus the lower-case Atrium key, with every character the external
 * plugin does not accept replaced by {@code _}. The prefix alone does not prove ownership: an admin may name a
 * hologram {@code atrium_spawn} by hand, so {@link MirrorOwnership} decides which prefixed holograms Atrium may
 * touch.
 */
final class EngineNames {
    static final String PREFIX = "atrium_";
    /** DH rules (letters, digits, _ and -); Atrium names are stricter, so this only defends API-created input. */
    private static final Pattern SAFE = Pattern.compile("[a-z0-9_-]{1,64}");
    private static final int MAX_BASE = 64 - PREFIX.length();

    private EngineNames() {}

    static String externalName(String atriumName) {
        String lower = atriumName == null ? "" : atriumName.strip().toLowerCase(Locale.ROOT);
        StringBuilder base = new StringBuilder(Math.min(lower.length(), MAX_BASE));
        for (int i = 0; i < lower.length() && base.length() < MAX_BASE; i++) {
            char c = lower.charAt(i);
            base.append(Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-' ? c : '_');
        }
        if (base.isEmpty()) {
            base.append("hologram");
        }
        return PREFIX + base;
    }

    static boolean isManaged(String externalName) {
        return externalName != null && externalName.length() > PREFIX.length()
                && externalName.startsWith(PREFIX) && SAFE.matcher(externalName.toLowerCase(Locale.ROOT)).matches();
    }

    /** The Atrium key of a prefixed external name; empty for names without the prefix. Not an ownership check. */
    static Optional<String> atriumName(String externalName) {
        if (!isManaged(externalName)) {
            return Optional.empty();
        }
        return Optional.of(externalName.substring(PREFIX.length()).toLowerCase(Locale.ROOT));
    }

    /** Guards an entry before it is handed to an external plugin. */
    static boolean validExternalName(String externalName) {
        return externalName != null && SAFE.matcher(externalName).matches();
    }
}
