package dev.yanianz.atrium.module.holograms;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaceholderTextTest {
    @Test void replacesPlaceholderApiTokensWithTags() {
        var p = PlaceholderText.prepare("<gold>Hi %player_name%, online: %server_online%");
        assertEquals("<gold>Hi <atrium_ph_0>, online: <atrium_ph_1>", p.miniMessage());
        assertEquals(List.of("player_name", "server_online"), p.tokens());
        assertTrue(p.dynamic());
    }

    @Test void percentSignsThatAreNotTokensStay() {
        var p = PlaceholderText.prepare("50% off, 20 % more, %notatoken%");
        assertEquals("50% off, 20 % more, %notatoken%", p.miniMessage());
        assertTrue(p.tokens().isEmpty());
        assertFalse(p.dynamic());
    }

    @Test void detectsTheViewerTag() {
        assertTrue(PlaceholderText.prepare("Hello <Viewer>").viewerTag());
        assertTrue(PlaceholderText.prepare("Hello <viewer>").dynamic());
        assertFalse(PlaceholderText.hasViewerTag("Hello viewer"));
        assertTrue(PlaceholderText.hasTokens("%vault_eco_balance_formatted%"));
    }

    @Test void replacementTextIsQuoted() {
        var p = PlaceholderText.prepare("cost $5 %player_x$1%");
        assertEquals("cost $5 <atrium_ph_0>", p.miniMessage());
        assertEquals(List.of("player_x$1"), p.tokens());
    }
}
