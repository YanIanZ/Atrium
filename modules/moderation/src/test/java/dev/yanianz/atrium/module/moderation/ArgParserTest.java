package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArgParserTest {
    @Test
    void flagsAreRemovedAnywhere() {
        ArgParser.Parsed parsed = ArgParser.parse("  -s spamming in   chat -SHADOW ");
        assertTrue(parsed.silent());
        assertTrue(parsed.shadow());
        assertEquals("spamming in chat", parsed.text());

        ArgParser.Parsed plain = ArgParser.parse("using -sneaky hacks");
        assertFalse(plain.silent());
        assertEquals("using -sneaky hacks", plain.text());

        assertEquals("", ArgParser.parse(null).text());
    }

    @Test
    void jailArgsInOrder() {
        List<String> jails = List.of("default", "cell2");
        ArgParser.JailArgs full = ArgParser.jailArgs(List.of("CELL2", "1h30m", "grief", "spawn"), jails);
        assertEquals("cell2", full.jail());
        assertEquals(Duration.ofMinutes(90), full.duration());
        assertEquals("grief spawn", full.reason());

        ArgParser.JailArgs noJail = ArgParser.jailArgs(List.of("2d", "x"), jails);
        assertNull(noJail.jail());
        assertEquals(Duration.ofDays(2), noJail.duration());
        assertEquals("x", noJail.reason());

        ArgParser.JailArgs reasonOnly = ArgParser.jailArgs(List.of("being", "rude"), jails);
        assertNull(reasonOnly.jail());
        assertNull(reasonOnly.duration());
        assertEquals("being rude", reasonOnly.reason());

        ArgParser.JailArgs number = ArgParser.jailArgs(List.of("3", "times"), jails);
        assertNull(number.duration(), "a bare number is part of the reason, not a duration");
        assertEquals("3 times", number.reason());

        assertEquals("", ArgParser.jailArgs(List.of(), jails).reason());
    }

    @Test
    void durationsMustBePositive() {
        assertEquals(Optional.of(Duration.ofHours(1)), ArgParser.parseDuration("1h"));
        assertTrue(ArgParser.parseDuration("0s").isEmpty());
        assertTrue(ArgParser.parseDuration("12").isEmpty());
        assertTrue(ArgParser.parseDuration("soon").isEmpty());
    }

    @Test
    void reasonPresetsAndLimits() {
        Map<String, String> presets = new LinkedHashMap<>();
        presets.put("hacking", "Use of a modified client");
        assertEquals("Use of a modified client", ArgParser.reason("HACKING", presets, "None", 100));
        assertEquals("Use of a modified client", ArgParser.reason("#hacking", presets, "None", 100));
        assertEquals("hacking again", ArgParser.reason("hacking again", presets, "None", 100));
        assertEquals("None", ArgParser.reason("   ", presets, "None", 100));
        assertEquals("abc", ArgParser.reason("abcdef", presets, "None", 3));
    }
}
