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
        List<String> problems = new ArrayList<>();
        assertEquals(BackendMode.AUTO, BackendMode.parse("packet", (option, problem) -> problems.add(option)));
        assertEquals(List.of("backend"), problems);
    }

    @Test
    void noneBackendDoesNothing() {
        assertEquals(Kind.NONE, SidebarBackend.NONE.kind());
        assertFalse(SidebarBackend.NONE.canRender(null));
        assertEquals(0, SidebarBackend.NONE.update(null, null, List.of()));
        SidebarBackend.NONE.hide(null);
        SidebarBackend.NONE.forget(null);
        assertTrue(SidebarBackend.NONE.toString().contains("NONE"));
    }
}
