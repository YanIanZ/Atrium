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
    /** Spawn and first-join spawn points (SpawnAdminService). */
    SPAWN("spawn"),
    /** Kits with their items (KitAdminService). */
    KITS("kits"),
    /** Jails (ModerationImportService). */
    JAILS("jails"),
    /** Active bans, temp bans, IP bans, mutes and jail sentences (ModerationImportService). */
    PUNISHMENTS("punishments"),
    /** Last/logout location as one imported /back entry (BackHistoryAdminService). */
    LOCATIONS("locations"),
    /** Offline mail (MailAdminService). */
    MAIL("mail"),
    /** Login/logout timestamps; reported only. */
    HISTORY("history"),
    /** Ignore lists; reported only (the chat module has no write API for them). */
    IGNORE("ignore"),
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
        if (normalized.equals("states")) {
            return PUNISHMENTS; // name used before punishments were imported
        }
        for (ImportCategory category : values()) {
            if (category != SOURCE && category.key.equals(normalized)) {
                return category;
            }
        }
        return null;
    }

    /** Categories the CMI importer understands, in display order. */
    public static Set<ImportCategory> cmi() {
        return Collections.unmodifiableSet(EnumSet.of(ECONOMY, HOMES, WARPS, NICKS, PLAYTIME, HOLOGRAMS, SPAWN,
                KITS, JAILS, PUNISHMENTS, LOCATIONS));
    }

    /** Categories the EssentialsX importer understands, in display order. */
    public static Set<ImportCategory> essentials() {
        return Collections.unmodifiableSet(EnumSet.of(ECONOMY, HOMES, WARPS, NICKS, SPAWN, KITS, JAILS,
                PUNISHMENTS, LOCATIONS, MAIL, HISTORY, IGNORE));
    }

    /** Categories of the server's own ban files ({@code banned-players.json}, {@code banned-ips.json}). */
    public static Set<ImportCategory> vanilla() {
        return Collections.unmodifiableSet(EnumSet.of(PUNISHMENTS));
    }

    /** Categories of a Vault economy provider. */
    public static Set<ImportCategory> economyOnly() {
        return Collections.unmodifiableSet(EnumSet.of(ECONOMY));
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
