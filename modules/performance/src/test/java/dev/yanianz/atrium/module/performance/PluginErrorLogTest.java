package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PluginErrorLogTest {
    @Test
    void keepsTheNewestEntriesFirstAndHonoursTheCapacity() {
        PluginErrorLog log = new PluginErrorLog(3);
        for (int i = 1; i <= 5; i++) {
            log.add(i, "ERROR", "com.example.Plugin", "problem " + i, null);
        }
        assertEquals(3, log.size());
        assertEquals(List.of("problem 5", "problem 4", "problem 3"),
                log.recent(10).stream().map(PluginErrorLog.Entry::message).toList());
    }

    @Test
    void recentNeverReturnsMoreThanAsked() {
        PluginErrorLog log = new PluginErrorLog(100);
        log.add(1, "WARN", "logger", "a", null);
        log.add(2, "WARN", "logger", "b", null);
        assertEquals(1, log.recent(1).size());
        assertTrue(log.recent(0).isEmpty());
    }

    @Test
    void tagsAtriumAsAtrium() {
        String stack = "at dev.yanianz.atrium.module.homes.HomeService.save(HomeService.java:1)\n"
                + "at java.base/java.lang.Thread.run(Thread.java:1)";
        assertEquals("Atrium", PluginErrorLog.tag("dev.yanianz.atrium.module.homes.HomeService", stack));
    }

    @Test
    void tagsTheFirstForeignPackageFromTheStack() {
        String stack = "at java.base/java.util.Objects.requireNonNull(Objects.java:1)\n"
                + "at com.acme.shop.Cart.add(Cart.java:12)";
        assertEquals("com.acme", PluginErrorLog.tag("", stack));
    }

    @Test
    void fallsBackToTheLoggerName() {
        assertEquals("com.acme", PluginErrorLog.tag("com.acme.plugin.Main", null));
        assertEquals("plugins", PluginErrorLog.tag("plugins", ""));
    }

    @Test
    void clearEmptiesTheRing() {
        PluginErrorLog log = new PluginErrorLog(5);
        log.add(1, "ERROR", "logger", "x", null);
        log.clear();
        assertTrue(log.recent(5).isEmpty());
        assertFalse(log.size() > 0);
    }
}
