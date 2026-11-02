package dev.yanianz.atrium.module.chat;

import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdFilterTest {
    private static AdFilter.Settings settings(AdFilter.Action action) {
        return new AdFilter.Settings(true, action, "***", Set.of("google.com", "youtube.com"), true);
    }

    @Test
    void censorsIpAddressesAndDomains() {
        AdFilter.Verdict ip = AdFilter.apply("join 1.2.3.4 now", settings(AdFilter.Action.CENSOR));
        assertEquals("join *** now", ip.text());
        assertEquals(1, ip.matches().size());

        AdFilter.Verdict domain = AdFilter.apply("visit example.com today", settings(AdFilter.Action.CENSOR));
        assertEquals("visit *** today", domain.text());
    }

    @Test
    void blockAndWarnActionsDoNotRewriteTheText() {
        AdFilter.Verdict blocked = AdFilter.apply("example.com", settings(AdFilter.Action.BLOCK));
        assertTrue(blocked.blocked());
        assertEquals("example.com", blocked.text());

        AdFilter.Verdict warned = AdFilter.apply("example.com", settings(AdFilter.Action.WARN));
        assertFalse(warned.blocked());
        assertEquals("example.com", warned.text());
    }

    @Test
    void whitelistOnlyCoversTheWholeHostOrASubdomain() {
        assertTrue(AdFilter.apply("google.com", settings(AdFilter.Action.CENSOR)).matches().isEmpty());
        assertTrue(AdFilter.apply("maps.google.com", settings(AdFilter.Action.CENSOR)).matches().isEmpty());
        assertFalse(AdFilter.apply("google.evil.com", settings(AdFilter.Action.CENSOR)).matches().isEmpty());
    }

    @Test
    void dotSpellingOfAnIpIsRecognised() {
        AdFilter.Verdict verdict = AdFilter.apply("server 1 dot 2 dot 3 dot 4", settings(AdFilter.Action.CENSOR));
        assertEquals(1, verdict.matches().size());
        assertEquals("1.2.3.4", verdict.matches().getFirst().host());
    }

    @Test
    void ordinaryNumbersWithoutSeparatorsAreNotAnIp() {
        assertTrue(AdFilter.apply("score 10 20 30 40", settings(AdFilter.Action.CENSOR)).matches().isEmpty());
    }

    @Test
    void disabledSettingsPassEverything() {
        AdFilter.Settings off = new AdFilter.Settings(false, AdFilter.Action.BLOCK, "***", Set.of(), true);
        assertFalse(AdFilter.apply("example.com", off).blocked());
    }
}
