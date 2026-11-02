package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression: items picked up while the backup was written must not be overwritten without a matching backup. */
class BackupGuardTest {
    private static boolean empty(String item) {
        return item.isEmpty();
    }

    @Test
    void identicalBytesAreUnchanged() {
        assertTrue(BackupGuard.unchanged(new byte[] {1, 2}, new byte[] {1, 2}, List.of(), List.of(),
                BackupGuardTest::empty));
    }

    @Test
    void pickedUpItemIsAChange() {
        List<String> backup = Arrays.asList("dirt", null, null);
        List<String> now = Arrays.asList("dirt", "diamond", null);
        assertFalse(BackupGuard.unchanged(new byte[] {1}, new byte[] {2}, List.of(backup), List.of(now),
                BackupGuardTest::empty));
    }

    @Test
    void equalItemsWithDifferentEncodingAreUnchanged() {
        List<String> backup = Arrays.asList("dirt", null, "");
        List<String> now = Arrays.asList("dirt", "", null, null);
        assertTrue(BackupGuard.unchanged(new byte[] {1}, new byte[] {2}, List.of(backup), List.of(now),
                BackupGuardTest::empty));
    }

    @Test
    void movedItemIsAChange() {
        assertFalse(BackupGuard.sameSlots(Arrays.asList("dirt", null), Arrays.asList(null, "dirt"),
                BackupGuardTest::empty));
    }

    @Test
    void changeTriggersBoundedRebackupThenAbort() {
        assertEquals(BackupGuard.Decision.APPLY, BackupGuard.decide(true, 1));
        assertEquals(BackupGuard.Decision.APPLY, BackupGuard.decide(true, BackupGuard.MAX_ATTEMPTS));
        assertEquals(BackupGuard.Decision.REBACKUP, BackupGuard.decide(false, 1));
        assertEquals(BackupGuard.Decision.REBACKUP, BackupGuard.decide(false, BackupGuard.MAX_ATTEMPTS - 1));
        assertEquals(BackupGuard.Decision.ABORT, BackupGuard.decide(false, BackupGuard.MAX_ATTEMPTS));
    }
}
