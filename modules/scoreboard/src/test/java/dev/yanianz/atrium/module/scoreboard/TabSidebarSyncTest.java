package dev.yanianz.atrium.module.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.scoreboard.SidebarBackend.Kind;
import dev.yanianz.atrium.module.scoreboard.TabSidebarSync.DefaultMode;
import dev.yanianz.atrium.module.scoreboard.TabSidebarSync.Defaults;
import dev.yanianz.atrium.module.scoreboard.TabSidebarSync.Step;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exclusivity of the sidebar slot with the TAB backend. */
class TabSidebarSyncTest {
    @Test
    void shownSidebarIsAtriumsAndVisible() {
        Step first = TabSidebarSync.plan(true, DefaultMode.HIDE, false, true, false);
        assertTrue(first.show());
        assertNull(first.visible());
        assertFalse(first.reset());
        assertTrue(TabSidebarSync.plan(true, DefaultMode.HIDE, true, true, false).none(), "already shown");
        // TAB's join handling or toggle hid scoreboards: Atrium's board must still show, so visibility goes on.
        assertEquals(Boolean.TRUE, TabSidebarSync.plan(true, DefaultMode.SHOW, true, false, false).visible());
    }

    @Test
    void hideModeNeverRevealsTabsDefault() {
        Step hide = TabSidebarSync.plan(false, DefaultMode.HIDE, true, true, false);
        assertEquals(Boolean.FALSE, hide.visible());
        assertFalse(hide.reset(), "resetting would hand the slot to TAB's default scoreboard");
        assertTrue(TabSidebarSync.plan(false, DefaultMode.HIDE, true, false, true).none());
        // Never had an Atrium sidebar (no board matches): TAB's own still must not show.
        assertEquals(Boolean.FALSE, TabSidebarSync.plan(false, DefaultMode.HIDE, false, true, false).visible());
    }

    @Test
    void showModeHandsTheSlotBackToTab() {
        Step step = TabSidebarSync.plan(false, DefaultMode.SHOW, true, true, false);
        assertTrue(step.reset());
        assertNull(step.visible());
        // Switched from hide to show: undo the visibility Atrium turned off.
        Step undo = TabSidebarSync.plan(false, DefaultMode.SHOW, false, false, true);
        assertEquals(Boolean.TRUE, undo.visible());
        assertFalse(undo.reset());
        // The player hid TAB's scoreboards themselves: leave that alone.
        assertTrue(TabSidebarSync.plan(false, DefaultMode.SHOW, false, false, false).none());
    }

    @Test
    void defaultModeParsesWithHideAsDefault() {
        List<String> problems = new ArrayList<>();
        assertEquals(DefaultMode.HIDE, DefaultMode.parse(null, (o, p) -> problems.add(o)));
        assertEquals(DefaultMode.HIDE, DefaultMode.parse(" Hide ", (o, p) -> problems.add(o)));
        assertEquals(DefaultMode.SHOW, DefaultMode.parse("show", (o, p) -> problems.add(o)));
        assertTrue(problems.isEmpty());
        assertEquals(DefaultMode.HIDE, DefaultMode.parse("maybe", (o, p) -> problems.add(o)));
        assertEquals(List.of("tab-default-scoreboard"), problems);
    }

    @Test
    void defaultsOwnershipPerBackend() {
        assertEquals(Defaults.HIDDEN_BY_ATRIUM, TabSidebarSync.defaults(Kind.TAB, true, DefaultMode.HIDE));
        assertEquals(Defaults.SHOWN_WHEN_ATRIUM_HIDES, TabSidebarSync.defaults(Kind.TAB, true, DefaultMode.SHOW));
        assertEquals(Defaults.CONFLICT, TabSidebarSync.defaults(Kind.PACKETS, true, DefaultMode.HIDE),
                "native sidebar next to TAB's scoreboards: two sources");
        assertEquals(Defaults.CONFLICT, TabSidebarSync.defaults(Kind.BUKKIT, true, DefaultMode.HIDE));
        assertEquals(Defaults.NOT_RUNNING, TabSidebarSync.defaults(Kind.PACKETS, false, DefaultMode.HIDE));
        assertEquals(Defaults.SHOWN_WHEN_ATRIUM_HIDES, TabSidebarSync.defaults(Kind.NONE, true, DefaultMode.HIDE));
    }
}
