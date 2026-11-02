package dev.yanianz.atrium.module.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class HomeSpecTest {
    @Test
    void ownHome() {
        assertEquals(Optional.of(new HomeSpec(null, "base", false)), HomeSpec.parse("base"));
        assertEquals(Optional.of(new HomeSpec(null, "base", true)), HomeSpec.parse("base confirm"));
        assertEquals(Optional.of(HomeSpec.NONE), HomeSpec.parse(""));
        assertEquals(Optional.of(HomeSpec.NONE), HomeSpec.parse(null));
    }

    @Test
    void otherPlayersHome() {
        assertEquals(Optional.of(new HomeSpec("Steve", "base", false)), HomeSpec.parse("Steve:base"));
        assertEquals(Optional.of(new HomeSpec("Steve", null, false)), HomeSpec.parse("Steve:"));
        assertEquals(Optional.of(new HomeSpec("Steve", "base", true)), HomeSpec.parse("  Steve:base   CONFIRM "));
        assertEquals("Steve:base", HomeSpec.parse("Steve:base").orElseThrow().reference());
    }

    @Test
    void rejectsInvalid() {
        for (String bad : new String[] {"a b", "base yes", ":base", "St eve:base", "Steve:ba:se", "Steve:<red>",
                "toolongplayername_x:base", "a confirm extra", "<bold>"}) {
            assertTrue(HomeSpec.parse(bad).isEmpty(), "should reject: " + bad);
        }
    }
}
