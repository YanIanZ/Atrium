package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;

/**
 * One home parsed from a foreign source, before the homes module's rules are applied. A {@code problem}
 * is carried instead of throwing so one broken home never aborts a player's whole record.
 */
record ImportHome(String name, StoredLocation location, String problem) {
    boolean ok() {
        return problem == null;
    }

    static ImportHome broken(String problem) {
        return new ImportHome(null, null, problem);
    }
}
