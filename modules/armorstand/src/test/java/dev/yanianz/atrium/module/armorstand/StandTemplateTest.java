package dev.yanianz.atrium.module.armorstand;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure parts of the placement templates: name rules and the stored toggle list. */
class StandTemplateTest {
    @Test void namesAreShortAndPlain() {
        assertTrue(TemplateStore.validName("knight"));
        assertTrue(TemplateStore.validName("knight_2-a"));
        assertFalse(TemplateStore.validName("Knight"));
        assertFalse(TemplateStore.validName("has space"));
        assertFalse(TemplateStore.validName(""));
        assertFalse(TemplateStore.validName(null));
        assertFalse(TemplateStore.validName("x".repeat(33)));
    }

    @Test void toggleKeysAreStableAndSorted() {
        StandTemplate template = new StandTemplate("guard", Pose.DEFAULT,
                EnumSet.of(Toggle.ARMS, Toggle.SMALL, Toggle.BASE_PLATE), null, Map.of());
        assertEquals(List.of("arms", "base-plate", "small"), List.copyOf(template.toggleKeys()));
        assertTrue(template.equipment().isEmpty());
        assertNull(template.customName());
    }

    @Test void equipmentIsDefensivelyCopiedAndEmptyStacksDropped() {
        StandTemplate template = new StandTemplate("guard", Pose.DEFAULT, EnumSet.noneOf(Toggle.class), null, Map.of());
        assertTrue(template.equipment().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> template.equipment().put(null, null));
    }
}
