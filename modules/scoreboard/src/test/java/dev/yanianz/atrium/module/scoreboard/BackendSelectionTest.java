package dev.yanianz.atrium.module.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.scoreboard.SidebarBackend.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackendSelectionTest {
    @Test
    void autoPrefersBukkitWhenBothAreAvailable() {
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.AUTO, true, true));
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.AUTO, true, false));
    }

    @Test
    void autoUsesPacketsOnlyWithoutBukkit() {
        assertEquals(Kind.PACKETS, BackendSelection.select(BackendMode.AUTO, false, true));
        assertEquals(Kind.NONE, BackendSelection.select(BackendMode.AUTO, false, false));
    }

    @Test
    void explicitModesNeverSilentlyUseTheOtherBackend() {
        assertEquals(Kind.NONE, BackendSelection.select(BackendMode.BUKKIT, false, true));
        assertEquals(Kind.NONE, BackendSelection.select(BackendMode.PACKETS, true, false));
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.BUKKIT, true, true));
        assertEquals(Kind.PACKETS, BackendSelection.select(BackendMode.PACKETS, true, true));
    }

    @Test
    void configModeIsParsedCaseInsensitivelyAndFallsBackToAuto() {
        assertEquals(BackendMode.AUTO, BackendMode.parse("", null));
        assertEquals(BackendMode.AUTO, BackendMode.parse(null, null));
        assertEquals(BackendMode.AUTO, BackendMode.parse(" Auto ", null));
        assertEquals(BackendMode.BUKKIT, BackendMode.parse("bukkit", null));
        assertEquals(BackendMode.PACKETS, BackendMode.parse("PACKETS", null));
        assertEquals(BackendMode.TAB, BackendMode.parse("tab", null));
        List<String> problems = new ArrayList<>();
        assertEquals(BackendMode.AUTO, BackendMode.parse("packet", (option, problem) -> problems.add(option)));
        assertEquals(List.of("backend"), problems);
    }

    @Test
    void autoPrefersTabThenBukkitOnPaperThenPacketsOnFolia() {
        assertEquals(Kind.TAB, BackendSelection.select(BackendMode.AUTO, true, true, true));
        assertEquals(Kind.TAB, BackendSelection.select(BackendMode.AUTO, false, true, true));
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.AUTO, true, true, false));
        assertEquals(Kind.PACKETS, BackendSelection.select(BackendMode.AUTO, false, true, false));
        assertEquals(Kind.NONE, BackendSelection.select(BackendMode.AUTO, false, false, false));
    }

    @Test
    void explicitTabFallsBackToTheAutoChoiceWithoutTab() {
        assertEquals(Kind.TAB, BackendSelection.select(BackendMode.TAB, true, false, true));
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.TAB, true, true, false), "Paper");
        assertEquals(Kind.PACKETS, BackendSelection.select(BackendMode.TAB, false, true, false), "Folia");
        assertEquals(Kind.NONE, BackendSelection.select(BackendMode.TAB, false, false, false));
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.TAB, true, false));
    }

    @Test
    void explicitBukkitAndPacketsIgnoreTab() {
        assertEquals(Kind.BUKKIT, BackendSelection.select(BackendMode.BUKKIT, true, true, true));
        assertEquals(Kind.PACKETS, BackendSelection.select(BackendMode.PACKETS, true, true, true));
        assertEquals(Kind.NONE, BackendSelection.select(BackendMode.PACKETS, true, false, true));
    }

    @Test
    void tabProblemExplainsWhyTabIsNotUsed() {
        assertEquals(BackendSelection.TAB_PLUGIN_MISSING, BackendSelection.tabProblem(false, false, false));
        assertEquals(BackendSelection.TAB_PLUGIN_MISSING, BackendSelection.tabProblem(false, true, true));
        assertEquals(BackendSelection.TAB_SCOREBOARD_DISABLED, BackendSelection.tabProblem(true, false, false));
        assertEquals(BackendSelection.TAB_START_FAILED, BackendSelection.tabProblem(true, true, false));
        assertEquals("", BackendSelection.tabProblem(true, true, true));
    }

    @Test
    void capabilityMatrix() {
        SidebarCapabilities bukkit = SidebarCapabilities.of(Kind.BUKKIT, false);
        assertFalse(bukkit.supports(SidebarCapabilities.Feature.FOLIA));
        assertEquals(List.of(SidebarCapabilities.Feature.FOLIA), bukkit.missing());
        SidebarCapabilities packetsFolia = SidebarCapabilities.of(Kind.PACKETS, true);
        assertTrue(packetsFolia.supports(SidebarCapabilities.Feature.FOLIA));
        assertFalse(packetsFolia.supports(SidebarCapabilities.Feature.RESPECTS_OTHER_SCOREBOARDS));
        assertTrue(SidebarCapabilities.of(Kind.PACKETS, false).missing().isEmpty());
        SidebarCapabilities tab = SidebarCapabilities.of(Kind.TAB, true);
        assertTrue(tab.supports(SidebarCapabilities.Feature.FOLIA));
        assertTrue(tab.supports(SidebarCapabilities.Feature.PER_PLAYER_TEXT));
        assertTrue(tab.supports(SidebarCapabilities.Feature.RESPECTS_OTHER_SCOREBOARDS));
        assertEquals("hidden-numbers", SidebarCapabilities.describe(tab.missing()));
        assertEquals("none", SidebarCapabilities.describe(SidebarCapabilities.of(Kind.NONE, false).supported()));
        assertEquals("tab", Kind.TAB.id());
    }

    @Test
    void noneBackendDoesNothing() {
        assertEquals(Kind.NONE, SidebarBackend.NONE.kind());
        assertFalse(SidebarBackend.NONE.canRender(null));
        assertEquals(0, SidebarBackend.NONE.update(null, null, List.of()));
        SidebarBackend.NONE.hide(null);
        SidebarBackend.NONE.forget(null);
        SidebarBackend.NONE.flush();
        SidebarBackend.NONE.stop();
        SidebarBackend.NONE.detach();
        assertTrue(SidebarBackend.NONE.usable());
        assertTrue(SidebarBackend.NONE.toString().contains("NONE"));
    }
}
