package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.homes.HomeService.HomeInfo;
import dev.yanianz.atrium.api.nick.NickService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure import decisions. Keeping them out of the JDBC/service code makes dry-run counting and the
 * idempotent "skip existing unless --overwrite" rule testable without a server.
 */
final class ImportPlanner {
    private ImportPlanner() {}

    enum Action { CREATE, REPLACE, SKIP, INVALID }

    /**
     * @param undo one entry per home this merge creates or replaces: what is written and what it replaces
     *             ({@code null} when the home is new), so a rollback can undo exactly these writes
     */
    record Merge(List<HomeInfo> target, int created, int replaced, int skipped, List<String> problems,
            List<HomeUndo> undo) {}

    /** One home written by the import and the home it replaced, or {@code null} when it was created. */
    record HomeUndo(HomeInfo written, HomeInfo previous) {}

    /**
     * @param target   the homes to store, or {@code null} when nothing has to change
     * @param restored writes undone
     * @param kept     writes left in place because the player (or an admin) changed that home since
     */
    record HomeRollback(List<HomeInfo> target, int restored, int kept) {}

    /** Whether a write whose outcome is being rolled back actually happened. */
    enum Applied { NO, YES, UNKNOWN }

    record NickPlan(Action action, String problem) {}

    record BalancePlan(Action action, BigDecimal amount, String problem) {}

    record WarpPlan(Action action, String problem) {}

    record PlaytimePlan(Action action, long seconds, String problem) {}

    /**
     * Merges incoming homes into the existing snapshot. Names are case-insensitive. Without overwrite an
     * existing home is left untouched; the resulting target is identical to {@code existing}, so the caller
     * can skip the write entirely.
     */
    static Merge mergeHomes(List<HomeInfo> existing, List<HomeInfo> incoming, boolean overwrite) {
        Map<String, HomeInfo> target = new LinkedHashMap<>();
        for (HomeInfo home : existing) {
            target.put(key(home.name()), home);
        }
        int created = 0;
        int replaced = 0;
        int skipped = 0;
        List<String> problems = new ArrayList<>();
        Map<String, HomeUndo> undo = new LinkedHashMap<>();
        for (HomeInfo home : incoming) {
            String key = key(home.name());
            HomeInfo current = target.get(key);
            if (current != null && !overwrite) {
                skipped++;
                continue;
            }
            if (current != null) {
                replaced++;
            } else {
                created++;
            }
            target.put(key, home);
            // A duplicate name in the source replaces our own earlier write: keep the stored original.
            HomeUndo earlier = undo.get(key);
            undo.put(key, new HomeUndo(home, earlier != null ? earlier.previous() : current));
        }
        return new Merge(List.copyOf(target.values()), created, replaced, skipped, List.copyOf(problems),
                List.copyOf(undo.values()));
    }

    /**
     * Undoes only what this import wrote, against the homes stored <em>now</em>: a home is removed (or its
     * previous value restored) only while it still equals what the import wrote. Homes the player created,
     * moved or deleted since are left exactly as they are.
     */
    static HomeRollback rollbackHomes(List<HomeInfo> current, List<HomeUndo> undo) {
        Map<String, HomeInfo> target = new LinkedHashMap<>();
        for (HomeInfo home : current) {
            target.put(key(home.name()), home);
        }
        int restored = 0;
        int kept = 0;
        for (HomeUndo step : undo) {
            String key = key(step.written().name());
            HomeInfo now = target.get(key);
            if (now == null || !sameHome(now, step.written())) {
                kept++;
                continue;
            }
            if (step.previous() == null) {
                target.remove(key);
            } else {
                target.put(key, step.previous());
            }
            restored++;
        }
        return new HomeRollback(restored == 0 ? null : List.copyOf(target.values()), restored, kept);
    }

    /** Same name (case-insensitive) and same location; the stored creation time is ignored. */
    static boolean sameHome(HomeInfo a, HomeInfo b) {
        return key(a.name()).equals(key(b.name())) && a.location().equals(b.location());
    }

    /** Maps a nick write's outcome; {@code null} means the call failed and may or may not have stored. */
    static Applied nickApplied(NickService.Result result) {
        if (result == null) {
            return Applied.UNKNOWN;
        }
        return result == NickService.Result.SUCCESS ? Applied.YES : Applied.NO;
    }

    /**
     * A value is restored only when the import may have written it <em>and</em> the stored value is still
     * the one it wrote. A SKIPPED/TAKEN nick write never touched the player's real nickname.
     */
    static boolean shouldRestore(Applied applied, Object current, Object written) {
        return applied != Applied.NO && current != null && current.equals(written);
    }

    static NickPlan planNick(boolean hasExisting, boolean valid, boolean overwrite) {
        if (!valid) {
            return new NickPlan(Action.INVALID, "nickname rejected by the nick module rules");
        }
        if (hasExisting && !overwrite) {
            return new NickPlan(Action.SKIP, null);
        }
        return new NickPlan(hasExisting ? Action.REPLACE : Action.CREATE, null);
    }

    /**
     * Decided on account existence, never on the balance value: the economy reports its starting balance
     * for a missing account, and a player who spent down to zero still has an account. A balance already
     * imported from this source is never applied again without {@code overwrite} (no money minting on a
     * re-run). The SKIP reason is returned as {@code problem} so the caller can explain it.
     */
    static BalancePlan planBalance(boolean accountExists, boolean alreadyImported, BigDecimal incoming,
            boolean overwrite) {
        if (incoming == null) {
            return new BalancePlan(Action.INVALID, null, "invalid balance");
        }
        if (incoming.signum() < 0) {
            return new BalancePlan(Action.INVALID, null, "negative balance");
        }
        if (!overwrite && alreadyImported) {
            return new BalancePlan(Action.SKIP, incoming, "already imported from this source");
        }
        if (!overwrite && accountExists) {
            return new BalancePlan(Action.SKIP, incoming, "economy account already exists");
        }
        return new BalancePlan(accountExists ? Action.REPLACE : Action.CREATE, incoming, null);
    }

    /**
     * A warp is skipped when it already exists, unless {@code overwrite} is set. The warps module
     * validates the name again with {@code Warp.validName}; this is only the pre-check for counting.
     */
    static WarpPlan planWarp(boolean exists, boolean validName, boolean overwrite) {
        if (!validName) {
            return new WarpPlan(Action.INVALID, "invalid warp name");
        }
        if (exists && !overwrite) {
            return new WarpPlan(Action.SKIP, null);
        }
        return new WarpPlan(exists ? Action.REPLACE : Action.CREATE, null);
    }

    /**
     * Playtime is an absolute total, so re-running the import must not add the CMI total again. Zero
     * stored seconds means "never tracked" (like a zero balance), so a first run is still imported.
     */
    static PlaytimePlan planPlaytime(long existingSeconds, long incomingSeconds, boolean overwrite) {
        return planPlaytime(existingSeconds, false, incomingSeconds, overwrite);
    }

    /** As above; a total already imported from this source is never applied again without overwrite. */
    static PlaytimePlan planPlaytime(long existingSeconds, boolean alreadyImported, long incomingSeconds,
            boolean overwrite) {
        if (incomingSeconds < 0) {
            return new PlaytimePlan(Action.INVALID, 0, "negative playtime");
        }
        if (incomingSeconds == 0) {
            return new PlaytimePlan(Action.SKIP, 0, null);
        }
        if ((existingSeconds > 0 || alreadyImported) && !overwrite) {
            return new PlaytimePlan(Action.SKIP, incomingSeconds, null);
        }
        return new PlaytimePlan(existingSeconds > 0 ? Action.REPLACE : Action.CREATE, incomingSeconds, null);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
