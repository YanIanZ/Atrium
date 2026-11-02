package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandSpyFilterTest {
    private static final List<String> SENSITIVE = List.of("login", "register", "l", "reg", "changepassword", "cp",
            "msg", "tell", "w", "r");

    @Test
    void normalCommandsStayWhole() {
        assertEquals("/home spawn", CommandSpyFilter.mask("/home spawn", SENSITIVE));
        assertEquals("/gamemode creative Notch", CommandSpyFilter.mask("/gamemode creative Notch", SENSITIVE));
    }

    @Test
    void sensitiveArgumentsAreMasked() {
        assertEquals("/login " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/login hunter2", SENSITIVE));
        assertEquals("/msg " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/msg Notch secret", SENSITIVE));
        assertEquals("/TELL " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/TELL Notch secret", SENSITIVE));
    }

    @Test
    void sensitiveCommandWithoutArgumentsKeepsTheName() {
        assertEquals("/register", CommandSpyFilter.mask("/register", SENSITIVE));
        assertEquals("/login", CommandSpyFilter.mask("login", SENSITIVE));
    }

    @Test
    void namespacedAndSlashlessCommandsAreNormalized() {
        assertEquals("/msg " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/minecraft:msg Notch secret", SENSITIVE));
        assertEquals("/copy", CommandSpyFilter.mask("copy", List.of("copy")));
    }

    @Test
    void aliasesOfSensitiveCommandsAreMasked() {
        java.util.function.Function<String, java.util.Collection<String>> aliases = label ->
                CommandNames.normalize(label).equals("whisper") ? List.of("msg", "whisper") : List.of();
        assertEquals("/whisper " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/whisper Notch secret", List.of("msg"), aliases));
        assertEquals("/whisper " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/essentials:whisper Notch secret", List.of("msg"), aliases));
        assertEquals("/home a", CommandSpyFilter.mask("/home a", List.of("msg"), aliases));
    }

    @Test
    void leadingSpacesAndDoubleSlashesDoNotBypassMasking() {
        assertEquals("/login " + CommandSpyFilter.HIDDEN, CommandSpyFilter.mask("  /login hunter2", SENSITIVE));
        assertEquals("/login " + CommandSpyFilter.HIDDEN, CommandSpyFilter.mask("//login hunter2", SENSITIVE));
        assertEquals("/LOGIN " + CommandSpyFilter.HIDDEN,
                CommandSpyFilter.mask("/AuthMe:LOGIN hunter2", SENSITIVE));
    }

    @Test
    void emptyInputStaysEmpty() {
        assertEquals("", CommandSpyFilter.mask("", SENSITIVE));
        assertEquals("", CommandSpyFilter.mask(null, SENSITIVE));
        assertEquals("/", CommandSpyFilter.mask("/", SENSITIVE));
    }
}
