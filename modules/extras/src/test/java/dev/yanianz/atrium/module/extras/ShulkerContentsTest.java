package dev.yanianz.atrium.module.extras;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The pure copy used by the read-only preview, tested with strings so no server is needed. */
class ShulkerContentsTest {
    @Test
    void padsMissingSlotsWithNull() {
        List<String> copy = ShulkerContents.copy(List.of("a", "b"), 4, String::new);

        assertEquals(4, copy.size());
        assertEquals("a", copy.get(0));
        assertEquals("b", copy.get(1));
        assertNull(copy.get(2));
        assertNull(copy.get(3));
    }

    @Test
    void trimsContentsThatDoNotFitThePage() {
        List<String> copy = ShulkerContents.copy(List.of("a", "b", "c"), 2, String::new);

        assertEquals(List.of("a", "b"), copy);
    }

    @Test
    void everyElementIsCopiedThroughTheCopier() {
        String first = new String("a");
        String second = new String("b");

        List<String> copy = ShulkerContents.copy(List.of(first, second), 2, String::new);

        assertEquals(first, copy.get(0));
        assertEquals(second, copy.get(1));
        assertNotSame(first, copy.get(0), "the preview must never share the original element");
        assertNotSame(second, copy.get(1));
    }

    @Test
    void nullAndEmptySourcesProduceAnEmptyPage() {
        assertTrue(ShulkerContents.<String>copy(null, 3, String::new).stream().allMatch(value -> value == null));
        assertTrue(ShulkerContents.<String>copy(List.of(), 3, String::new).stream().allMatch(value -> value == null));
    }

    @Test
    void pageIsNeverEmpty() {
        assertEquals(1, ShulkerContents.copy(List.of("a"), 0, String::new).size());
    }

    @Test
    void copierIsNeverCalledForEmptySlots() {
        List<String> copy = ShulkerContents.copy(java.util.Arrays.asList((String) null), 3, value -> {
            throw new AssertionError("copier called for an empty slot");
        });

        assertNull(copy.get(0));
        assertNull(copy.get(1));
        assertNull(copy.get(2));
    }
}
