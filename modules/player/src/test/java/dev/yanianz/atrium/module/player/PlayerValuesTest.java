package dev.yanianz.atrium.module.player;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerValuesTest {
    @Test void mapsSpeedToApiRange() {
        assertEquals(0f, PlayerValues.speed(0));
        assertEquals(0.1f, PlayerValues.speed(1));
        assertEquals(0.5f, PlayerValues.speed(5));
        assertEquals(1f, PlayerValues.speed(10));
        assertThrows(IllegalArgumentException.class, () -> PlayerValues.speed(-1));
        assertThrows(IllegalArgumentException.class, () -> PlayerValues.speed(11));
    }
    @Test void parsesAllGamemodeAliasesCaseInsensitively() {
        for (String s : new String[]{"creative", "c", "1", "gmc", "CREATIVE"})
            assertEquals(GameMode.CREATIVE, PlayerValues.mode(s));
        for (String s : new String[]{"survival", "s", "0", "gms"})
            assertEquals(GameMode.SURVIVAL, PlayerValues.mode(s));
        for (String s : new String[]{"adventure", "a", "2", "gma"})
            assertEquals(GameMode.ADVENTURE, PlayerValues.mode(s));
        for (String s : new String[]{"spectator", "sp", "3", "gmsp"})
            assertEquals(GameMode.SPECTATOR, PlayerValues.mode(s));
        assertThrows(IllegalArgumentException.class, () -> PlayerValues.mode("builder"));
    }
    @Test void parsesPointsAndLevelsWithoutLosingTheUnit() {
        assertEquals(new PlayerValues.ExpAmount(125, false), PlayerValues.exp("125"));
        assertEquals(new PlayerValues.ExpAmount(12, true), PlayerValues.exp("12L"));
        assertEquals(new PlayerValues.ExpAmount(0, true), PlayerValues.exp("0l"));
        assertEquals(new PlayerValues.ExpAmount(Integer.MAX_VALUE, false), PlayerValues.exp("2147483647"));
        for (String s : new String[]{"", "L", "-1", "+2", "1.5", "2LL", " 3", "2147483648", "1e2"})
            assertThrows(IllegalArgumentException.class, () -> PlayerValues.exp(s), s);
    }
}
