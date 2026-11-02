package dev.yanianz.atrium.module.importer;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** The data groups {@code /atrium-import <source> --only=...} understands. */
public enum ImportCategory {
    ECONOMY("economy"),
    HOMES("homes"),
    WARPS("warps"),
    NICKS("nicks"),
    PLAYTIME("playtime"),
    HOLOGRAMS("holograms"),
    /** EssentialsSpawn's spawn point; reported until a spawn write API exists. */
    SPAWN("spawn"),
    /** Essentials kits; reported until the kits module exposes an import API. */
    KITS("kits"),
    /** Last and logout locations; reported until Atrium stores a per-player location. */
    LOCATIONS("locations"),
    /** Login/logout timestamps; reported only. */
    HISTORY("history"),
    /** Essentials ignore lists; reported because IgnoreService is read-only. */
    IGNORE("ignore"),
    /** Essentials mail; reported because the mail module has no public API. */
    MAIL("mail"),
    /** Jail, mute and ban states; reported only. */
    STATES("states"),
    /** Row-level source problems that are not tied to one importable record. */
    SOURCE("source");

    private final String key;

    ImportCategory(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public static ImportCategory byKey(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ImportCategory category : values()) {
            if (category != SOURCE && category.key.equals(normalized)) {
                return category;
            }
        }
        return null;
    }

    /** Categories the CMI importer understands, in display order. */
    public static Set<ImportCategory> cmi() {
        return Collections.unmodifiableSet(EnumSet.range(ECONOMY, HOLOGRAMS));
    }

    /** Categories the EssentialsX importer understands, in display order. */
    public static Set<ImportCategory> essentials() {
        return Collections.unmodifiableSet(EnumSet.of(ECONOMY, HOMES, WARPS, NICKS, SPAWN, KITS,
                LOCATIONS, HISTORY, IGNORE, MAIL, STATES));
    }

    /** {@code economy,homes,...} text used in usage messages. */
    public static String keys(Set<ImportCategory> categories) {
        return categories.stream()
                .sorted()
                .map(ImportCategory::key)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
    }
}
