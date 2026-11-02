package dev.yanianz.atrium.module.holograms;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClickActionTest {
    @Test void parsesClickTypeAndAction() {
        ClickAction a = ClickAction.parse("right [console] /give {player} diamond 1");
        assertEquals(ClickAction.Click.RIGHT, a.click());
        assertEquals(ClickAction.Type.CONSOLE, a.type());
        assertEquals("give {player} diamond 1", a.value());

        ClickAction any = ClickAction.parse("[message] <green>Hi <player>");
        assertEquals(ClickAction.Click.ANY, any.click());
        assertEquals("<green>Hi <player>", any.value());

        assertEquals(ClickAction.Type.PREV, ClickAction.parse("LEFT [Prev]").type());
        assertEquals(3, ClickAction.parse("[page] 3").page());
    }

    @Test void encodeRoundTrips() {
        for (String text : new String[]{"right [console] say {player}", "any [next]", "left [page] 2", "any [message] <red>x"}) {
            ClickAction parsed = ClickAction.parse(text);
            assertEquals(parsed, ClickAction.parse(parsed.encode()));
            assertEquals(text, parsed.encode());
        }
    }

    @Test void rejectsMalformedActions() {
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("say hi"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("[console]"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("[next] 2"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("[page] zero"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("[page] 0"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("[explode] now"));
        assertThrows(IllegalArgumentException.class, () -> ClickAction.parse("[console unclosed"));
    }

    @Test void matchesButtons() {
        assertTrue(ClickAction.Click.ANY.matches(ClickAction.Click.LEFT));
        assertTrue(ClickAction.Click.RIGHT.matches(ClickAction.Click.RIGHT));
        assertFalse(ClickAction.Click.RIGHT.matches(ClickAction.Click.LEFT));
    }

    @Test void fillsOnlySafePlayerNames() {
        UUID uuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(Optional.of("give Steve_1 diamond " + uuid), ClickAction.fill("give {player} diamond {uuid}", "Steve_1", uuid));
        assertEquals(Optional.of("give .BedrockGuy x"), ClickAction.fill("give {player} x", ".BedrockGuy", uuid));
        assertTrue(ClickAction.fill("give {player} x", "evil; op me", uuid).isEmpty());
        assertTrue(ClickAction.fill("give {player} x", "a\nop b", uuid).isEmpty());
        assertEquals(Optional.of("spawn"), ClickAction.fill("spawn", "evil name", uuid)); // no {player}: nothing to inject
    }
}
