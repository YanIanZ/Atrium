package dev.yanianz.atrium.module.nick;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NickLogicTest {
    private static NickRules rules(boolean normalize) {
        NickConfig config = new NickConfig();
        config.minLength = 3;
        config.maxLength = 16;
        config.blockedWords = List.of("admin", "staff");
        config.normalizeBlockedWords = normalize;
        return NickRules.from(config, message -> fail(message));
    }

    @Test void acceptsPlainNamesInsideTheLengthWindow() {
        NickRules rules = rules(true);
        assertNull(NickLogic.validate("Bob", rules));
        assertNull(NickLogic.validate("A_1-b", rules));
        assertEquals(NickLogic.Violation.LENGTH, NickLogic.validate("ab", rules));
        assertEquals(NickLogic.Violation.LENGTH, NickLogic.validate("abcdefghijklmnopq", rules));
    }

    @Test void rejectsCharactersOutsideTheConfiguredPattern() {
        NickRules rules = rules(true);
        assertEquals(NickLogic.Violation.CHARACTERS, NickLogic.validate("Bob!", rules));
        assertEquals(NickLogic.Violation.CHARACTERS, NickLogic.validate("<red>Bob", rules));
        assertEquals(NickLogic.Violation.CHARACTERS, NickLogic.validate("Bob Smith", rules));
    }

    @Test void blocksWordsAnywhereAndFoldsCommonEvasion() {
        NickRules rules = rules(true);
        assertEquals(NickLogic.Violation.BLOCKED, NickLogic.validate("admin", rules));
        assertEquals(NickLogic.Violation.BLOCKED, NickLogic.validate("xStaffx", rules));
        assertEquals(NickLogic.Violation.BLOCKED, NickLogic.validate("4dm1n", rules));
        assertEquals(NickLogic.Violation.BLOCKED, NickLogic.validate("adm_in", rules));
        assertNull(NickLogic.validate("admiral", rules));
    }

    @Test void normalizationCanBeDisabled() {
        NickRules rules = rules(false);
        assertNull(NickLogic.validate("4dm1n", rules));
        assertEquals(NickLogic.Violation.BLOCKED, NickLogic.validate("Admin", rules));
    }

    @Test void restrictTagsKeepsOnlyPermittedGroups() {
        String raw = "<red><bold><gradient:red:blue>Bob</gradient></bold></red>";
        assertEquals(raw, NickLogic.restrictTags(raw, true, true, true));
        assertEquals("<red><gradient:red:blue>Bob</gradient></red>",
                NickLogic.restrictTags(raw, true, false, true));
        assertEquals("<bold>Bob</bold>", NickLogic.restrictTags(raw, false, true, false));
        assertEquals("<gradient:red:blue>Bob</gradient>",
                NickLogic.restrictTags(raw, false, false, true));
        assertEquals("Bob", NickLogic.restrictTags(raw, false, false, false));
        assertEquals("Bob", NickLogic.restrictTags(
                "<click:open_url:'https://example.org'>Bob</click>", true, true, true));
        assertEquals("<rainbow>Bob</rainbow>", NickLogic.restrictTags("<rainbow>Bob</rainbow>", false, false, true));
        assertEquals("Bob", NickLogic.restrictTags("<rainbow>Bob</rainbow>", true, true, false));
        assertEquals("<transition:red:blue>Bob</transition>",
                NickLogic.restrictTags("<transition:red:blue>Bob</transition>", false, false, true));
    }

    @Test void restrictTagsHandlesClosingSelfClosingAndAliases() {
        assertEquals("<#ff0000>Bob</#ff0000>",
                NickLogic.restrictTags("<#ff0000>Bob</#ff0000>", true, false, false));
        assertEquals("<color:grey>Bob</color>",
                NickLogic.restrictTags("<color:grey>Bob</color>", true, false, false));
        assertEquals("Bob", NickLogic.restrictTags("<color:grey>Bob</color>", false, false, false));
        assertEquals("Bob", NickLogic.restrictTags("<bold/>Bob", false, false, false));
        assertEquals("<!bold>Bob", NickLogic.restrictTags("<!bold>Bob", false, true, false));
    }

    @Test void canonicalisesAndStripsTheDisplayPrefix() {
        assertEquals("bob", NickLogic.canonical("BoB"));
        assertEquals("Bob", NickLogic.stripPrefix("  ~Bob ", "~"));
        assertEquals("Bob", NickLogic.stripPrefix("Bob", "~"));
        assertEquals("Bob", NickLogic.stripPrefix("Bob", ""));
        assertEquals("", NickLogic.stripPrefix("~", "~"));
    }

    @Test void normalizesLettersWithoutLosingOrdinaryWords() {
        assertEquals("admin", NickLogic.normalizeWord(true, "4d-m!n"));
        assertEquals("admin", NickLogic.normalizeWord(true, "@DM1N"));
        assertEquals("4dmin", NickLogic.normalizeWord(false, "4dmin"));
        assertEquals("hello", NickLogic.normalizeWord(true, "h e l l o"));
    }

    @Test void rulesClampLengthsAndFallBackFromABrokenRegex() {
        NickConfig config = new NickConfig();
        config.minLength = 0;
        config.maxLength = 99;
        config.allowedCharacters = "[A-Za-z0-9";
        StringBuilder warning = new StringBuilder();
        NickRules rules = NickRules.from(config, warning::append);
        assertEquals(1, rules.minLength());
        assertEquals(32, rules.maxLength());
        assertEquals(NickRules.DEFAULT_PATTERN, rules.allowedPattern());
        assertTrue(warning.length() > 0);
    }
}
