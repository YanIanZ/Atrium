package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GroupOptionTest {
    @Test void selectorsAreSplitOffAnywhere() {
        assertEquals(new GroupOption("100", "survival"), GroupOption.parse("100 g:survival"));
        assertEquals(new GroupOption("100", "survival"), GroupOption.parse("G:survival 100"));
        assertEquals(new GroupOption("25%", "resource"), GroupOption.parse("25% --group resource"));
        assertEquals(new GroupOption("2", "events"), GroupOption.parse("--group=events 2"));
        assertEquals(new GroupOption("rand:1-5", null), GroupOption.parse("  rand:1-5  "));
        assertEquals(GroupOption.NONE, GroupOption.parse(""));
        assertEquals(GroupOption.NONE, GroupOption.parse(null));
    }

    @Test void malformedSelectorsFail() {
        assertThrows(IllegalArgumentException.class, () -> GroupOption.parse("100 --group"));
        assertThrows(IllegalArgumentException.class, () -> GroupOption.parse("g: 100"));
        assertThrows(IllegalArgumentException.class, () -> GroupOption.parse("g:a g:b"));
    }
}
