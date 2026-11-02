package dev.yanianz.atrium.module.chat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MentionDetectorTest {
    @Test
    void findsNamesWithAndWithoutAtSign() {
        assertEquals(List.of("Alice", "Bob"),
                MentionDetector.find("hey Alice and @bob", List.of("Alice", "Bob"), false));
    }

    @Test
    void requireAtSignOnlyMatchesExplicitMentions() {
        assertTrue(MentionDetector.find("hey alice", List.of("Alice"), true).isEmpty());
        assertEquals(List.of("Alice"), MentionDetector.find("hey @alice", List.of("Alice"), true));
    }

    @Test
    void respectsNameBoundariesAndCase() {
        assertTrue(MentionDetector.find("alicea", List.of("Alice"), false).isEmpty());
        assertTrue(MentionDetector.find("xAlice", List.of("Alice"), false).isEmpty());
        assertEquals(List.of("Alice"), MentionDetector.find("@Alice, hi", List.of("Alice"), false));
    }

    @Test
    void prefersTheLongestNameAndRemovesDuplicates() {
        assertEquals(List.of("Bobby"), MentionDetector.find("Bobby was here", List.of("Bob", "Bobby"), false));
        assertEquals(List.of("Alice"), MentionDetector.find("Alice and @alice", List.of("Alice"), false));
    }
}
