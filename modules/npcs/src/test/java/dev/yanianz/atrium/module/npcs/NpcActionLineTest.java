package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NpcActionLineTest {
    @Test void recognisesTheThreeAtriumDirectives() {
        NpcActionLine dialog = NpcActionLine.parse("open dialog shop");
        assertEquals(NpcActionLine.Type.DIALOG, dialog.type());
        assertEquals("shop", dialog.value());
        assertTrue(dialog.problem().isEmpty());

        NpcActionLine warp = NpcActionLine.parse("WARP spawn");
        assertEquals(NpcActionLine.Type.WARP, warp.type());
        assertEquals("spawn", warp.value());

        NpcActionLine server = NpcActionLine.parse("  server lobby-1  ");
        assertEquals(NpcActionLine.Type.SERVER, server.type());
        assertEquals("lobby-1", server.value());
    }

    @Test void ordinaryLinesStayWithTheActionsModule() {
        NpcActionLine line = NpcActionLine.parse("console: say hello");
        assertEquals(NpcActionLine.Type.ACTION, line.type());
        assertFalse(line.directive());
        assertEquals("console: say hello", line.raw());
    }

    @Test void rejectsBlankAndUnsafeDirectiveNames() {
        assertTrue(NpcActionLine.parse("   ").problem().isPresent());
        assertTrue(NpcActionLine.parse("warp ").problem().isPresent());
        assertTrue(NpcActionLine.parse("warp two words").problem().isPresent());
        assertTrue(NpcActionLine.parse("server evil;op").problem().isPresent());
        assertTrue(NpcActionLine.parse("open dialog one\ntwo").problem().isPresent());
    }

    @Test void actionLinesPreserveOrderAndSkipDirectives() {
        List<String> lines = List.of("console: say one", "warp spawn", "", "msg: <gold>hi", "server lobby");
        assertEquals(List.of("console: say one", "msg: <gold>hi"), NpcActionLine.actionLines(lines));
    }
}
