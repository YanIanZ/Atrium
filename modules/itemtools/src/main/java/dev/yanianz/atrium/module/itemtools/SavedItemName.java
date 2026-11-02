package dev.yanianz.atrium.module.itemtools;

import java.util.Locale;

/** Names of saved items: lower-case letters, digits, {@code _} and {@code -} (1-48 characters). */
final class SavedItemName {
    static final int MAX_LENGTH = 48;

    private SavedItemName() {
    }

    static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    /** Normalises admin input; throws when the result would not be a valid name. */
    static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("name is missing");
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        if (!isValid(normalized)) {
            throw new IllegalArgumentException("name must be 1-" + MAX_LENGTH
                    + " lower-case letters, digits, _ or -");
        }
        return normalized;
    }
}
