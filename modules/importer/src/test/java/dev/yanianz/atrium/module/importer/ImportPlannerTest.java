package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.homes.HomeService.HomeInfo;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportPlannerTest {
    private static HomeInfo home(String name, String world) {
        return new HomeInfo(name, new StoredLocation(world, 1, 64, 1, 0, 0), 0L);
    }

    @Test
    void skipsExistingHomesWithoutOverwrite() {
        List<HomeInfo> existing = List.of(home("base", "world"), home("farm", "world"));
        ImportPlanner.Merge merge = ImportPlanner.mergeHomes(existing,
                List.of(home("BASE", "nether"), home("mine", "world")), false);
        assertEquals(1, merge.created());
        assertEquals(0, merge.replaced());
        assertEquals(1, merge.skipped());
        assertEquals("world", merge.target().get(0).location().world());
        assertEquals(List.of("base", "farm", "mine"), merge.target().stream().map(HomeInfo::name).toList());
    }

    @Test
    void replacesExistingHomesWithOverwrite() {
        List<HomeInfo> existing = List.of(home("base", "world"));
        ImportPlanner.Merge merge = ImportPlanner.mergeHomes(existing,
                List.of(home("base", "nether")), true);
        assertEquals(0, merge.created());
        assertEquals(1, merge.replaced());
        assertEquals(0, merge.skipped());
        assertEquals("nether", merge.target().getFirst().location().world());
    }

    @Test
    void reRunningIsIdempotent() {
        List<HomeInfo> incoming = List.of(home("base", "world"), home("farm", "world"));
        ImportPlanner.Merge first = ImportPlanner.mergeHomes(List.of(), incoming, false);
        assertEquals(2, first.created());
        ImportPlanner.Merge second = ImportPlanner.mergeHomes(first.target(), incoming, false);
        assertEquals(0, second.created());
        assertEquals(0, second.replaced());
        assertEquals(2, second.skipped());
        assertEquals(first.target(), second.target());
    }

    @Test
    void nicknameAndBalancePlanning() {
        assertEquals(ImportPlanner.Action.CREATE, ImportPlanner.planNick(false, true, false).action());
        assertEquals(ImportPlanner.Action.SKIP, ImportPlanner.planNick(true, true, false).action());
        assertEquals(ImportPlanner.Action.REPLACE, ImportPlanner.planNick(true, true, true).action());
        assertEquals(ImportPlanner.Action.INVALID, ImportPlanner.planNick(false, false, true).action());

        BigDecimal ten = new BigDecimal("10");
        assertEquals(ImportPlanner.Action.CREATE, ImportPlanner.planBalance(false, false, ten, false).action());
        assertEquals(ImportPlanner.Action.SKIP, ImportPlanner.planBalance(true, false, ten, false).action());
        assertEquals(ImportPlanner.Action.REPLACE, ImportPlanner.planBalance(true, false, ten, true).action());
        assertEquals(ImportPlanner.Action.INVALID,
                ImportPlanner.planBalance(false, false, new BigDecimal("-1"), false).action());
    }

    @Test
    void balanceIsDecidedOnAccountExistenceNotOnTheValue() {
        // Regression: a missing account reads as the starting balance (> 0); it used to be SKIPPED as existing.
        assertEquals(ImportPlanner.Action.CREATE,
                ImportPlanner.planBalance(false, false, new BigDecimal("250"), false).action());
        // Regression: a player who spent down to 0 after the first import used to get the balance again.
        ImportPlanner.BalancePlan rerun = ImportPlanner.planBalance(true, true, new BigDecimal("250"), false);
        assertEquals(ImportPlanner.Action.SKIP, rerun.action());
        // Even when the account row is gone, a recorded import is never applied twice without overwrite.
        assertEquals(ImportPlanner.Action.SKIP,
                ImportPlanner.planBalance(false, true, new BigDecimal("250"), false).action());
        assertEquals(ImportPlanner.Action.CREATE,
                ImportPlanner.planBalance(false, true, new BigDecimal("250"), true).action());
    }

    @Test
    void playtimeAlreadyImportedIsNotAppliedAgain() {
        assertEquals(ImportPlanner.Action.SKIP, ImportPlanner.planPlaytime(0, true, 3_600, false).action());
        assertEquals(ImportPlanner.Action.CREATE, ImportPlanner.planPlaytime(0, true, 3_600, true).action());
        assertEquals(ImportPlanner.Action.CREATE, ImportPlanner.planPlaytime(0, false, 3_600, false).action());
    }

    @Test
    void homeRollbackUndoesOnlyThisImportsWrites() {
        HomeInfo base = home("base", "world");
        ImportPlanner.Merge merge = ImportPlanner.mergeHomes(List.of(base),
                List.of(home("mine", "world"), home("base", "nether")), true);
        assertEquals(2, merge.undo().size());

        // Regression: the player set a new home after the import wrote; restoring the old snapshot wiped it.
        List<HomeInfo> now = new java.util.ArrayList<>(merge.target());
        now.add(home("newer", "world"));
        ImportPlanner.HomeRollback rollback = ImportPlanner.rollbackHomes(now, merge.undo());
        assertEquals(2, rollback.restored());
        assertEquals(0, rollback.kept());
        assertEquals(List.of("base", "newer"), rollback.target().stream().map(HomeInfo::name).toList());
        assertEquals("world", rollback.target().getFirst().location().world(), "replaced home restored");
    }

    @Test
    void homeRollbackKeepsHomesChangedSinceTheImport() {
        ImportPlanner.Merge merge = ImportPlanner.mergeHomes(List.of(), List.of(home("mine", "world")), false);
        // The player moved "mine" after the import created it: it is theirs now.
        List<HomeInfo> now = List.of(new HomeInfo("mine", new StoredLocation("world", 9, 70, 9, 0, 0), 5L));
        ImportPlanner.HomeRollback rollback = ImportPlanner.rollbackHomes(now, merge.undo());
        assertEquals(0, rollback.restored());
        assertEquals(1, rollback.kept());
        assertEquals(null, rollback.target(), "nothing to write");
        // A write that never landed has nothing to undo either.
        assertEquals(null, ImportPlanner.rollbackHomes(List.of(), merge.undo()).target());
    }

    @Test
    void nickRollbackOnlyUndoesAppliedWrites() {
        // Regression: setNick returned SKIPPED (the player kept their real nick) and the rollback cleared it.
        ImportPlanner.Applied skipped = ImportPlanner.nickApplied(dev.yanianz.atrium.api.nick.NickService.Result.SKIPPED);
        assertEquals(ImportPlanner.Applied.NO, skipped);
        assertTrue(!ImportPlanner.shouldRestore(skipped, "<red>Same", "<red>Same"));
        assertEquals(ImportPlanner.Applied.NO,
                ImportPlanner.nickApplied(dev.yanianz.atrium.api.nick.NickService.Result.TAKEN));

        ImportPlanner.Applied ok = ImportPlanner.nickApplied(dev.yanianz.atrium.api.nick.NickService.Result.SUCCESS);
        assertTrue(ImportPlanner.shouldRestore(ok, "<red>Ours", "<red>Ours"));
        assertTrue(!ImportPlanner.shouldRestore(ok, "<blue>Changed", "<red>Ours"), "changed since: keep");
        // A failed call may have stored: compare against the stored value.
        ImportPlanner.Applied unknown = ImportPlanner.nickApplied(null);
        assertTrue(ImportPlanner.shouldRestore(unknown, "<red>Ours", "<red>Ours"));
        assertTrue(!ImportPlanner.shouldRestore(unknown, null, "<red>Ours"));
        // Playtime uses the same compare-and-restore rule.
        assertTrue(ImportPlanner.shouldRestore(ImportPlanner.Applied.YES, 3_600L, 3_600L));
        assertTrue(!ImportPlanner.shouldRestore(ImportPlanner.Applied.YES, 3_700L, 3_600L));
    }

    @Test
    void dryRunCountingUsesTheSameMergeRules() {
        ImportReport report = new ImportReport();
        report.found(ImportCategory.HOMES, 2);
        ImportPlanner.Merge merge = ImportPlanner.mergeHomes(List.of(home("base", "world")),
                List.of(home("base", "world"), home("mine", "world")), false);
        report.imported(ImportCategory.HOMES, merge.created() + merge.replaced());
        report.skipped(ImportCategory.HOMES, merge.skipped());
        String rendered = report.render("CMI", ImportOptions.defaults(), "fixture", false, 10, null);
        assertTrue(rendered.contains("homes: found=2 imported=1"));
        assertTrue(rendered.contains("skipped=1"));
    }
}
