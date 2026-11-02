package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RateLimitedLogTest {
    @Test
    void suppressesWithinIntervalAndReportsCount() {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { messages.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        AtomicLong now = new AtomicLong();
        RateLimitedLog log = new RateLimitedLog(logger, 1000, now::get);
        assertTrue(log.log(Level.WARNING, "k", "first", null));
        assertFalse(log.log(Level.WARNING, "k", "second", null));
        assertFalse(log.log(Level.WARNING, "k", "third", null));
        assertTrue(log.log(Level.WARNING, "other", "independent key", null));
        now.addAndGet(1_000_000_000L);
        assertTrue(log.log(Level.WARNING, "k", "later", null));
        assertEquals(List.of("first", "independent key", "later (2 similar message(s) suppressed)"), messages);
    }
}
