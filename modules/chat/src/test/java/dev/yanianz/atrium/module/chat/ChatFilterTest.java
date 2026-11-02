package dev.yanianz.atrium.module.chat;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatFilterTest {
    @Test
    void normalisationDefeatsColourSpacingLeetAndRepeatBypasses() {
        assertEquals("fuck", ChatFilter.normalize("F U C K").text());
        assertEquals("fuck", ChatFilter.normalize("f.u.c.k").text());
        assertEquals("fuck", ChatFilter.normalize("&cF\u00a7aU<red>C&lK").text());
        assertEquals("fuck", ChatFilter.normalize("fuuuck").text());
        assertEquals("fuck", ChatFilter.normalize("&#ff0000fuck").text());
        assertEquals("fuck", ChatFilter.normalize("&x&f&f&0&0&0&0fuck").text());
        assertEquals("shit", ChatFilter.normalize("sh1t").text());
        assertEquals("bitch", ChatFilter.normalize("b!tch").text());
        assertEquals("as", ChatFilter.normalize("@ss").text());
    }

    @Test
    void wordRuleCensorsTheOriginalSpanAndKeepsPrefixCodes() {
        List<ChatFilter.Rule> rules = List.of(wordRule("bad", "fuck", ChatFilter.Action.CENSOR));
        ChatFilter.Verdict verdict = ChatFilter.apply("&cf.u.c.k you", rules, "***");
        assertFalse(verdict.blocked());
        assertEquals("&c*** you", verdict.text());
        assertEquals(1, verdict.matches().size());
    }

    @Test
    void blockRuleStopsTheMessageWithoutRewritingIt() {
        List<ChatFilter.Rule> rules = List.of(wordRule("bad", "badword", ChatFilter.Action.BLOCK));
        ChatFilter.Verdict verdict = ChatFilter.apply("this is a b.a.d.w.o.r.d", rules, "***");
        assertTrue(verdict.blocked());
        assertEquals("this is a b.a.d.w.o.r.d", verdict.text());
    }

    @Test
    void warnRuleReportsAMatchButLeavesTextAlone() {
        List<ChatFilter.Rule> rules = List.of(wordRule("warn", "idiot", ChatFilter.Action.WARN));
        ChatFilter.Verdict verdict = ChatFilter.apply("you idiot", rules, "***");
        assertFalse(verdict.blocked());
        assertEquals("you idiot", verdict.text());
        assertEquals(ChatFilter.Action.WARN, verdict.matches().getFirst().action());
    }

    @Test
    void regexRulesUseTheNormalisedText() {
        ChatConfig.Filter config = new ChatConfig.Filter();
        config.rules = List.of(new ChatConfig.FilterRule("ip", "REGEX", "\\d{1,3}(\\.\\d{1,3}){3}", "CENSOR"));
        List<ChatFilter.Rule> rules = ChatFilter.compile(config, message -> fail(message));
        ChatFilter.Verdict verdict = ChatFilter.apply("join 1.2.3.4 now", rules, "[hidden]");
        assertEquals("join [hidden] now", verdict.text());
    }

    private static ChatFilter.Rule wordRule(String id, String word, ChatFilter.Action action) {
        String normalized = ChatFilter.normalize(word).text();
        return new ChatFilter.Rule(id, Pattern.compile(Pattern.quote(normalized)), action, false);
    }
}
