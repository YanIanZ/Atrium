package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.playtime.PlaytimeAdminService;
import dev.yanianz.atrium.api.warps.WarpAdminService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

/**
 * The dry-run decisions and the real writes use the same rules: an existing entry is skipped unless
 * {@code --overwrite} is set, so re-running the import is idempotent. The service fakes below mirror
 * the contract of the new administrative services without needing a server.
 */
class ImportWritePlanningTest {
    private static final class FakeWarpAdmin implements WarpAdminService {
        final Map<String, Location> warps = new HashMap<>();

        @Override
        public CompletableFuture<Boolean> importWarp(String name, Location location, String category,
                String permission, UUID creator, boolean overwrite) {
            String key = name.toLowerCase(Locale.ROOT);
            if (warps.containsKey(key) && !overwrite) {
                return CompletableFuture.completedFuture(false);
            }
            warps.put(key, location);
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class FakePlaytimeAdmin implements PlaytimeAdminService {
        final Map<UUID, Long> totals = new HashMap<>();

        @Override
        public CompletableFuture<Void> setPlaytime(UUID player, Duration total) {
            totals.put(player, total.getSeconds());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> addPlaytime(UUID player, Duration amount) {
            totals.merge(player, amount.getSeconds(), Long::sum);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static Location location() {
        return new Location(null, 10.5, 64, -3.5, 90, 0);
    }

    @Test
    void warpsAreSkippedOnASecondRunAndReplacedOnlyWithOverwrite() {
        FakeWarpAdmin admin = new FakeWarpAdmin();
        Location target = location();

        ImportPlanner.WarpPlan first = ImportPlanner.planWarp(admin.warps.containsKey("spawn"), true, false);
        assertEquals(ImportPlanner.Action.CREATE, first.action());
        assertTrue(admin.importWarp("spawn", target, "general", null, null, false).join());

        ImportPlanner.WarpPlan second = ImportPlanner.planWarp(admin.warps.containsKey("spawn"), true, false);
        assertEquals(ImportPlanner.Action.SKIP, second.action());
        assertFalse(admin.importWarp("spawn", target, "general", null, null, false).join(),
                "the service also refuses an existing warp without overwrite");

        ImportPlanner.WarpPlan third = ImportPlanner.planWarp(admin.warps.containsKey("spawn"), true, true);
        assertEquals(ImportPlanner.Action.REPLACE, third.action());
        assertTrue(admin.importWarp("spawn", target, "general", null, null, true).join());

        assertEquals(ImportPlanner.Action.INVALID, ImportPlanner.planWarp(false, false, true).action());
    }

    @Test
    void playtimeIsAbsoluteAndSkippedUnlessOverwrite() {
        FakePlaytimeAdmin admin = new FakePlaytimeAdmin();
        UUID player = UUID.randomUUID();

        long existing = admin.totals.getOrDefault(player, 0L);
        ImportPlanner.PlaytimePlan first = ImportPlanner.planPlaytime(existing, 3_600, false);
        assertEquals(ImportPlanner.Action.CREATE, first.action());
        admin.setPlaytime(player, Duration.ofSeconds(first.seconds())).join();
        assertEquals(3_600, admin.totals.get(player));

        existing = admin.totals.getOrDefault(player, 0L);
        ImportPlanner.PlaytimePlan second = ImportPlanner.planPlaytime(existing, 3_600, false);
        assertEquals(ImportPlanner.Action.SKIP, second.action(), "a re-run must not add the total again");
        assertEquals(3_600, admin.totals.get(player));

        ImportPlanner.PlaytimePlan third = ImportPlanner.planPlaytime(existing, 4_000, true);
        assertEquals(ImportPlanner.Action.REPLACE, third.action());
        admin.setPlaytime(player, Duration.ofSeconds(third.seconds())).join();
        assertEquals(4_000, admin.totals.get(player), "overwrite replaces the absolute total");

        admin.addPlaytime(player, Duration.ofSeconds(60)).join();
        assertEquals(4_060, admin.totals.get(player));
        assertEquals(ImportPlanner.Action.INVALID, ImportPlanner.planPlaytime(0, -1, true).action());
    }
}
