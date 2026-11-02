package dev.yanianz.atrium.module.interactive;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Pure parsing of {@code [ic:name]} signs. */
class SignCommandsTest {
    @Test void readsTheNameFromAnyLine() {
        assertEquals(Optional.of("shop"), SignCommands.name(List.of("[ic:shop]", "", "", "")));
        assertEquals(Optional.of("shop"), SignCommands.name(List.of("", "", "[IC:Shop]", "")));
        assertEquals(Optional.of("shop"), SignCommands.name(List.of("  [ ic : shop ]  ", "", "", "")));
        assertEquals(Optional.empty(), SignCommands.name(List.of("", "", "", "")));
        assertEquals(Optional.empty(), SignCommands.name(null));
    }

    @Test void rejectsNamesThatAreNotPlain() {
        assertEquals(Optional.empty(), SignCommands.name(List.of("[ic:my shop]", "", "", "")));
        assertEquals(Optional.empty(), SignCommands.name(List.of("[ic:]", "", "", "")));
    }

    @Test void exposesSignLinesAsVariables() {
        Map<String, String> vars = SignCommands.variables(List.of("[ic:shop]", "Diamond", "", "64"));
        assertEquals("[ic:shop]", vars.get("sign_line1"));
        assertEquals("Diamond", vars.get("sign_line2"));
        assertEquals("", vars.get("sign_line3"));
        assertEquals("64", vars.get("sign_line4"));
        assertEquals("Diamond 64", vars.get("sign_args"));
    }

    @Test void flagsMalformedTags() {
        assertEquals("[ic:my shop]", SignCommands.malformedTag(List.of("[ic:my shop]", "", "", "")));
        assertNull(SignCommands.malformedTag(List.of("[ic:shop]", "", "", "")));
        assertNull(SignCommands.malformedTag(List.of("Hello", "", "", "")));
        assertNull(SignCommands.malformedTag(null));
    }
}
