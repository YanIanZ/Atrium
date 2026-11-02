package dev.yanianz.atrium.module.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CombatRulesTest {
    @Test
    void exactWorldEntryWinsOverTheFallback() {
        Map<String, Boolean> worlds = new LinkedHashMap<>();
        worlds.put("*", false);
        worlds.put("Arena", true);
        assertTrue(CombatRules.worldEnabled(worlds, "arena", false));
        assertTrue(CombatRules.worldEnabled(worlds, "ARENA", false));
        assertFalse(CombatRules.worldEnabled(worlds, "world", true));
        assertFalse(CombatRules.worldEnabled(worlds, null, true));
    }

    @Test
    void missingWorldMapUsesFallback() {
        assertTrue(CombatRules.worldEnabled(null, "world", true));
        assertTrue(CombatRules.worldEnabled(Map.of(), "world", true));
        assertFalse(CombatRules.worldEnabled(Map.of(), "world", false));
        Map<String, Boolean> onlyArena = new LinkedHashMap<>();
        onlyArena.put("Arena", true);
        assertFalse(CombatRules.worldEnabled(onlyArena, "world", false));
    }

    @Test
    void commandRootNormalisesTheTypedLine() {
        assertEquals("home", CombatRules.commandRoot("/home"));
        assertEquals("home", CombatRules.commandRoot("//HOME"));
        assertEquals("home", CombatRules.commandRoot(" /essentials:home 2"));
        assertEquals("msg", CombatRules.commandRoot("/msg Steve hello"));
        assertEquals("", CombatRules.commandRoot("   "));
        assertEquals("", CombatRules.commandRoot(null));
    }

    @Test
    void blockedCommandsMatchWordByWord() {
        List<String> blocked = List.of("home", "tpa", "cmi spawn");
        assertTrue(CombatRules.blocksCommand("/home", blocked, List.of()));
        assertTrue(CombatRules.blocksCommand("/home 2", blocked, List.of()));
        assertTrue(CombatRules.blocksCommand("/essentials:home 2", blocked, List.of()));
        assertTrue(CombatRules.blocksCommand("/tpa Steve", blocked, List.of()));
        assertTrue(CombatRules.blocksCommand("/cmi spawn zombie", blocked, List.of()));
        assertFalse(CombatRules.blocksCommand("/homeless", blocked, List.of()));
        assertFalse(CombatRules.blocksCommand("/tpahere Steve", blocked, List.of()));
        assertFalse(CombatRules.blocksCommand("/cmi home", blocked, List.of()));
        assertFalse(CombatRules.blocksCommand("/home", List.of(), List.of()));
        assertFalse(CombatRules.blocksCommand("", blocked, List.of()));
    }

    @Test
    void allowedCommandsOverrideTheBlockList() {
        List<String> blocked = List.of("msg", "home", "tp");
        List<String> allowed = List.of("msg");
        assertFalse(CombatRules.blocksCommand("/msg Steve hi", blocked, allowed));
        assertTrue(CombatRules.blocksCommand("/tp Steve", blocked, allowed));
    }

    @Test
    void displayFallsBackWhenTheConfigIsWrong() {
        assertEquals(CombatRules.Display.ACTIONBAR, CombatRules.display("ACTIONBAR"));
        assertEquals(CombatRules.Display.BOSSBAR, CombatRules.display(" bossbar "));
        assertEquals(CombatRules.Display.NONE, CombatRules.display("none"));
        assertEquals(CombatRules.Display.ACTIONBAR, CombatRules.display("chat"));
        assertEquals(CombatRules.Display.ACTIONBAR, CombatRules.display(null));
        assertNull(CombatRules.parseDisplay("chat"));
        assertNull(CombatRules.parseDisplay(""));
        assertEquals(CombatRules.Display.NONE, CombatRules.parseDisplay("NONE"));
    }
}
