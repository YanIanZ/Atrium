package dev.yanianz.atrium.module.inventories;

import java.util.Locale;

/** Why a snapshot was taken. Stored as a short code, translated for display. */
enum SnapshotReason {
    MANUAL,
    DEATH,
    QUIT,
    PRE_LOAD,
    CLEAR_ENDER,
    CLEAR_ALL,
    OFFLINE_COPY,
    /** Backup taken on join, right before a pending offline edit is applied. */
    PRE_PENDING_EDIT,
    /** The edited copy of an offline edit that could not be applied and was kept for review. */
    PENDING_EDIT_REJECTED;

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static SnapshotReason parse(String raw) {
        if (raw == null) {
            return MANUAL;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // An unknown reason from an older/newer build must never break a listing.
            return MANUAL;
        }
    }

    /** Strict lookup for an external reason code; {@code null} when the code is unknown. */
    static SnapshotReason byKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
