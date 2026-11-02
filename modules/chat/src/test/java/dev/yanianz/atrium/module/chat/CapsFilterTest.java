package dev.yanianz.atrium.module.chat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CapsFilterTest {
    private static CapsFilter.Settings settings(int minLetters) {
        return new CapsFilter.Settings(true, 60, minLetters, true, false, List.of("OK"));
    }

    @Test
    void rejectsMostlyUpperCaseTextAndLowerCasesIt() {
        CapsFilter.Result result = CapsFilter.check("HELLO EVERYONE", settings(6), List.of());

        assertTrue(result.exceeded());
        assertEquals("Hello Everyone", result.text());
    }

    @Test
    void shortAndWhitelistedMessagesPass() {
        assertFalse(CapsFilter.check("OK", settings(2), List.of()).exceeded());
        assertFalse(CapsFilter.check("Hi", settings(6), List.of()).exceeded());
    }

    @Test
    void playerNamesDoNotCountAsUpperCaseLetters() {
        assertFalse(CapsFilter.check("ALICE", settings(2), List.of("Alice")).exceeded());
    }

    @Test
    void formattingIsIgnoredWhenCounting() {
        CapsFilter.Result result = CapsFilter.check("<red>HELLO</red> <bold>EVERYONE</bold>", settings(6), List.of());

        assertTrue(result.exceeded());
        assertTrue(result.text().contains("<red>"));
    }

    @Test
    void disabledSettingsAlwaysPass() {
        CapsFilter.Settings off = new CapsFilter.Settings(false, 60, 1, true, false, List.of());
        assertFalse(CapsFilter.check("HELLO", off, List.of()).exceeded());
    }
}
