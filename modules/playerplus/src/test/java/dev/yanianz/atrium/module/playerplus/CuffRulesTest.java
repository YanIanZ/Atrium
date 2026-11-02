package dev.yanianz.atrium.module.playerplus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CuffRulesTest {
    private static final CuffPoint POINT = new CuffPoint("world", 100, 64, 100, "staff", 0);
    private static final double RADIUS = 2.0;

    @Test
    void insideTheRadiusIsAllowed() {
        assertTrue(CuffRules.inside(POINT, "world", 100, 64, 100, RADIUS));
        assertTrue(CuffRules.inside(POINT, "world", 102, 64, 100, RADIUS));
        assertTrue(CuffRules.inside(POINT, "world", 100, 66, 100, RADIUS));
        assertTrue(CuffRules.inside(POINT, "world", 101, 65, 101, RADIUS));
    }

    @Test
    void movingPastTheRadiusIsClamped() {
        assertFalse(CuffRules.inside(POINT, "world", 102.01, 64, 100, RADIUS));
        assertFalse(CuffRules.inside(POINT, "world", 100, 61.9, 100, RADIUS));
        assertFalse(CuffRules.inside(POINT, "world", 100, 64, 103, RADIUS));
    }

    @Test
    void anotherWorldIsAlwaysOutside() {
        assertFalse(CuffRules.inside(POINT, "world_nether", 100, 64, 100, RADIUS));
        assertFalse(CuffRules.inside(POINT, "", 100, 64, 100, RADIUS));
    }

    @Test
    void commandsMatchWordByWord() {
        List<String> allowed = List.of("msg", "r", "mail send");
        assertTrue(CuffRules.commandAllowed("/msg Steve hello", allowed));
        assertTrue(CuffRules.commandAllowed("MSG Steve hello", allowed));
        assertTrue(CuffRules.commandAllowed("/minecraft:msg Steve hello", allowed));
        assertTrue(CuffRules.commandAllowed("/r hi", allowed));
        assertTrue(CuffRules.commandAllowed("/mail send Steve hello", allowed));
        assertFalse(CuffRules.commandAllowed("/mail read", allowed));
        assertFalse(CuffRules.commandAllowed("/msgtoggle", allowed));
        assertFalse(CuffRules.commandAllowed("/op Steve", allowed));
    }
}
