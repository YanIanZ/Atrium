package dev.yanianz.atrium.module.inventories;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LabelsTest {
    @Test
    void controlCharactersAreReplacedAndSpacesCollapsed() {
        assertEquals("before death", Labels.clean("  before\n\tdeath  ", 64));
        assertEquals("a b", Labels.clean("a\u00a7b", 64));
    }

    @Test
    void blankLabelsBecomeNull() {
        assertNull(Labels.clean("   ", 64));
        assertNull(Labels.clean("\n\t", 64));
        assertNull(Labels.clean(null, 64));
    }

    @Test
    void labelsAreTruncatedToTheConfiguredLength() {
        assertEquals("abcd", Labels.clean("abcdefgh", 4));
        assertEquals("a", Labels.clean("abcdefgh", 1));
    }

    @Test
    void onlyRealMinecraftNamesMayBeUsedInClickCommands() {
        assertTrue(Labels.validName("Notch"));
        assertTrue(Labels.validName("player_1"));
        for (String invalid : new String[] {"", "a b", "a-b", "<red>", "abcdefghijklmnopq", null}) {
            assertFalse(Labels.validName(invalid));
        }
    }
}
