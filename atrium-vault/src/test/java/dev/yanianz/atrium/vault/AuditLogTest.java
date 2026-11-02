package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {
    @Test
    void writesEntriesAsynchronously(@TempDir Path dir) throws Exception {
        AuditLog audit = new AuditLog(dir, 100, 1024 * 1024, 3, new RateLimitedLog(Logger.getAnonymousLogger(), 0));
        audit.start();
        audit.record(new AuditEntry(0, "depositPlayer", "ali\tce\n", null, 5.0, "SUCCESS", 5.0, "Shop", null));
        audit.record(new AuditEntry(0, "withdrawPlayer", Players.offline("bob"), "world", 1.5, "FAILURE", 0, null, "no"));
        audit.close();
        List<String> lines = Files.readAllLines(dir.resolve(AuditLog.ACTIVE));
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).endsWith("\tdepositPlayer\tali ce \t-\t5.0\tSUCCESS\t5.0\tShop\t-"), lines.get(0));
        assertTrue(lines.get(1).contains("(bob)\tworld\t1.5\tFAILURE"), lines.get(1));
        assertEquals(2, audit.written());
        assertEquals(0, audit.queued(), "close drains the queue");
        assertEquals(0, audit.dropped());
    }

    @Test
    void countsUtf8BytesNotChars() {
        assertEquals(1, "é".length());
        assertEquals(2, AuditLog.utf8Bytes("é"), "one two-byte character");
        assertEquals(4, AuditLog.utf8Bytes("éé"));
        assertEquals(3, AuditLog.utf8Bytes("€"), "three-byte character");
        assertEquals(1, AuditLog.utf8Bytes("a"));
    }

    @Test
    void boundedQueueDropsInsteadOfBlocking(@TempDir Path dir) {
        AuditLog audit = new AuditLog(dir, 16, 1024 * 1024, 3, new RateLimitedLog(Logger.getAnonymousLogger(), 0));
        // not started: entries are ignored, nothing blocks
        audit.record(new AuditEntry(0, "depositPlayer", "a", null, 1, "SUCCESS", 1, null, null));
        assertEquals(0, audit.queued());
    }
}
