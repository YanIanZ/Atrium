package dev.yanianz.atrium.module.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageFlagsTest {
    @Test void keepsTheDefaultWhenNoFlagsAreGiven() {
        MessageFlags.Result result = MessageFlags.parse("hello world", 5, 300, false);
        assertTrue(result.ok());
        assertEquals("hello world", result.text());
        assertEquals(5, result.seconds());
        assertFalse(result.silent());
        assertTrue(result.perTarget().isEmpty());
    }

    @Test void parsesSecondsAndCompoundDurations() {
        MessageFlags.Result seconds = MessageFlags.parse("-t:30 hello", 5, 300, false);
        assertTrue(seconds.ok());
        assertEquals(30, seconds.seconds());
        assertEquals("hello", seconds.text());

        MessageFlags.Result minutes = MessageFlags.parse("-t:1m hello", 5, 300, false);
        assertTrue(minutes.ok());
        assertEquals(60, minutes.seconds());
    }

    @Test void acceptsTheCmiDurationSpelling() {
        MessageFlags.Result shortForm = MessageFlags.parse("-s:15 hello", 5, 300, false);
        assertTrue(shortForm.ok());
        assertEquals(15, shortForm.seconds());
        assertEquals("hello", shortForm.text());
        assertFalse(shortForm.silent());
    }

    @Test void perTargetDurationsOverrideTheGlobalOne() {
        MessageFlags.Result result = MessageFlags.parse("-t:8 -t:Alice=30 -t:bob=1m hello", 5, 300, false);
        assertTrue(result.ok());
        assertEquals(8, result.seconds());
        assertEquals(30, result.secondsFor("alice"));
        assertEquals(30, result.secondsFor("ALICE"));
        assertEquals(60, result.secondsFor("Bob"));
        assertEquals(8, result.secondsFor("Carol"));
        assertEquals("hello", result.text());
    }

    @Test void perTargetDurationsAreClampedAndValidated() {
        MessageFlags.Result clamped = MessageFlags.parse("-t:Alice=9999 hi", 5, 300, false);
        assertEquals(300, clamped.secondsFor("alice"));
        assertEquals(MessageFlags.Problem.INVALID_DURATION,
                MessageFlags.parse("-t:=5s hi", 5, 300, false).problem());
        assertEquals(MessageFlags.Problem.INVALID_DURATION,
                MessageFlags.parse("-t:Alice=nope hi", 5, 300, false).problem());
    }

    @Test void clampsLongDurationsToTheConfiguredMaximum() {
        MessageFlags.Result result = MessageFlags.parse("-t:9999 hello", 5, 300, false);
        assertTrue(result.ok());
        assertEquals(300, result.seconds());
    }

    @Test void parsesSilentOnlyWhenTheSenderMayUseIt() {
        MessageFlags.Result allowed = MessageFlags.parse("-s -t:10 <red>hi", 5, 300, true);
        assertTrue(allowed.ok());
        assertTrue(allowed.silent());
        assertEquals(10, allowed.seconds());
        assertEquals("<red>hi", allowed.text());

        MessageFlags.Result denied = MessageFlags.parse("-s hello", 5, 300, false);
        assertEquals(MessageFlags.Problem.SILENT_NOT_ALLOWED, denied.problem());
        assertEquals("-s", denied.detail());
    }

    @Test void rejectsInvalidAndEmptyDurations() {
        assertEquals(MessageFlags.Problem.INVALID_DURATION,
                MessageFlags.parse("-t:abc hello", 5, 300, false).problem());
        assertEquals(MessageFlags.Problem.INVALID_DURATION,
                MessageFlags.parse("-t:0 hello", 5, 300, false).problem());
        assertEquals(MessageFlags.Problem.INVALID_DURATION,
                MessageFlags.parse("-t: hello", 5, 300, false).problem());
    }

    @Test void rejectsBlankMessages() {
        assertEquals(MessageFlags.Problem.BLANK, MessageFlags.parse("   ", 5, 300, false).problem());
        assertEquals(MessageFlags.Problem.BLANK, MessageFlags.parse("-t:5", 5, 300, false).problem());
    }

    @Test void onlyLeadingFlagsAreRecognised() {
        MessageFlags.Result result = MessageFlags.parse("say -s -t:30", 5, 300, false);
        assertTrue(result.ok());
        assertEquals("say -s -t:30", result.text());
        assertFalse(result.silent());
    }

    @Test void durationFlagIsTextWhenTheCommandDoesNotUseDurations() {
        MessageFlags.Result result = MessageFlags.parse("-t:30 <green>hi", 1, 1, false, false);
        assertTrue(result.ok());
        assertEquals("-t:30 <green>hi", result.text());
        assertFalse(result.silent());
    }

    @Test void normalisesUntrustedText() {
        assertEquals("hello there", MessagingModule.untrustedText("  hello\n\tthere  ", 100));
        assertEquals("hello", MessagingModule.untrustedText("hello", 5));
        assertEquals("a b", MessagingModule.untrustedText("a\u0000b", 100));
    }
}
