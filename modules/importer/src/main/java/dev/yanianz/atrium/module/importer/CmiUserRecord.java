package dev.yanianz.atrium.module.importer;

import java.util.UUID;

/** One row of CMI's users table, as read from the source database. */
record CmiUserRecord(long id, UUID uuid, String rawUuid, String name, String nickname, String homes, String economy,
        String lastLocation, String mutedUntil) {
    CmiUserRecord(long id, UUID uuid, String rawUuid, String name, String nickname, String homes, String economy) {
        this(id, uuid, rawUuid, name, nickname, homes, economy, null, null);
    }

    String label() {
        if (name != null && !name.isBlank()) {
            return name + " (" + rawUuid + ")";
        }
        return rawUuid == null || rawUuid.isBlank() ? "user#" + id : rawUuid;
    }
}
