package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class EscalationTest {
    private record Rule(int warnings, String action) {}

    private static final List<Rule> RULES = List.of(new Rule(5, "kick"), new Rule(3, "mute"), new Rule(10, "ban"));

    private static List<String> fired(int before, int after) {
        return Escalation.triggered(RULES, Rule::warnings, before, after).stream().map(Rule::action).toList();
    }

    @Test
    void firesWhenCrossingThreshold() {
        assertEquals(List.of(), fired(1, 2));
        assertEquals(List.of("mute"), fired(2, 3));
        assertEquals(List.of(), fired(3, 4), "4th warning must not re-run the 3-warning rule");
        assertEquals(List.of("kick"), fired(4, 5));
    }

    @Test
    void multipleThresholdsInOrder() {
        assertEquals(List.of("mute", "kick", "ban"), fired(0, 10));
    }

    @Test
    void decreasingOrUnchangedNeverFires() {
        assertEquals(List.of(), fired(3, 3));
        assertEquals(List.of(), fired(5, 2));
    }

    @Test
    void refiresAfterExpiryDropsCountBelow() {
        assertEquals(List.of("mute"), fired(2, 3));
        // warnings expired: count went 3 -> 2, a new warning brings it back to 3
        assertEquals(List.of("mute"), fired(2, 3));
    }

    @Test
    void nonPositiveThresholdsIgnored() {
        List<Rule> rules = List.of(new Rule(0, "never"), new Rule(-1, "never"));
        assertEquals(List.of(), Escalation.triggered(rules, Rule::warnings, 0, 5));
    }

    @Test
    void expandsPlaceholders() {
        assertEquals("tempmute Steve 1h Reached 3 warnings",
                Escalation.expand("/tempmute {player} 1h Reached {count} warnings", "Steve", "u", 3));
        assertEquals("note u", Escalation.expand("note {uuid}", "Steve", "u", 1));
    }
}
