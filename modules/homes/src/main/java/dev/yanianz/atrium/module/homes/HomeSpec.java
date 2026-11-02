package dev.yanianz.atrium.module.homes;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Parsed argument of /home and /delhome: {@code [owner:]name [confirm]}.
 *
 * @param owner   other player's name, or null for the sender's own homes
 * @param name    home name, or null when none was given
 * @param confirm whether the trailing word {@code confirm} was given
 */
record HomeSpec(@Nullable String owner, @Nullable String name, boolean confirm) {
    static final HomeSpec NONE = new HomeSpec(null, null, false);

    /** Parses the raw text; empty for invalid syntax. */
    static Optional<HomeSpec> parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.of(NONE);
        }
        String[] words = raw.trim().split("\\s+");
        if (words.length > 2 || (words.length == 2 && !words[1].equalsIgnoreCase("confirm"))) {
            return Optional.empty();
        }
        boolean confirm = words.length == 2;
        String target = words[0];
        int colon = target.indexOf(':');
        if (colon < 0) {
            return Homes.validName(target) ? Optional.of(new HomeSpec(null, target, confirm)) : Optional.empty();
        }
        String owner = target.substring(0, colon);
        String name = target.substring(colon + 1);
        if (!validPlayerName(owner) || (!name.isEmpty() && !Homes.validName(name))) {
            return Optional.empty();
        }
        return Optional.of(new HomeSpec(owner, name.isEmpty() ? null : name, confirm));
    }

    static boolean validPlayerName(String name) {
        return name != null && name.matches("[A-Za-z0-9_]{1,16}");
    }

    boolean other() {
        return owner != null;
    }

    /** The command text that names this home again, e.g. {@code Steve:base}. */
    String reference() {
        return (owner == null ? "" : owner + ":") + (name == null ? "" : name);
    }
}
