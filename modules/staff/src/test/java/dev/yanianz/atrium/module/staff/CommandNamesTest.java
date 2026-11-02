package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CommandNamesTest {
    /** Simulated command map: whisper/tell/w are aliases of msg; minecraft:msg resolves the same. */
    private static final Function<String, Collection<String>> ALIASES = label -> {
        String name = CommandNames.normalize(label);
        return Map.<String, Collection<String>>of(
                "msg", List.of("msg", "tell", "w", "whisper"),
                "whisper", List.of("msg", "tell", "w", "whisper"),
                "tell", List.of("msg", "tell", "w", "whisper"),
                "hat", List.of("hat", "head")).getOrDefault(name, List.of());
    };

    @Test
    void labelSkipsSlashesAndArguments() {
        assertEquals("login", CommandNames.label("/login secret"));
        assertEquals("Plugin:Login", CommandNames.label("  / Plugin:Login secret"));
        assertEquals("", CommandNames.label("   "));
        assertEquals("", CommandNames.label(null));
    }

    @Test
    void normalizeStripsNamespaceAndCase() {
        assertEquals("login", CommandNames.normalize("/AuthMe:LOGIN"));
        assertEquals("msg", CommandNames.normalize("minecraft:msg"));
        assertEquals("", CommandNames.normalize(null));
    }

    @Test
    void directNamespacedAndAliasFormsMatch() {
        List<String> listed = List.of("msg");
        assertTrue(CommandNames.matches("msg", ALIASES, listed));
        assertTrue(CommandNames.matches("minecraft:MSG", ALIASES, listed));
        assertTrue(CommandNames.matches("whisper", ALIASES, listed));
        assertTrue(CommandNames.matches("essentials:tell", ALIASES, listed));
        assertFalse(CommandNames.matches("home", ALIASES, listed));
    }

    @Test
    void listedAliasCatchesTheRealCommand() {
        assertTrue(CommandNames.matches("hat", ALIASES, List.of("head")));
        assertTrue(CommandNames.matches("/plugin:hat", ALIASES, List.of("HEAD")));
    }

    @Test
    void emptyInputsNeverMatch() {
        assertFalse(CommandNames.matches("", ALIASES, List.of("msg")));
        assertFalse(CommandNames.matches("msg", ALIASES, List.of()));
        assertFalse(CommandNames.matches("msg", label -> null, List.of("tell")));
    }
}
