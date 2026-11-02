package dev.yanianz.atrium.module.maps;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTextTest {
    @Test void escapesEveryHtmlMetacharacter() {
        assertEquals("&lt;b&gt;A &amp; B&quot;c&quot; &#39;d&#39;&lt;/b&gt;",
                MapText.escapeHtml("<b>A & B\"c\" 'd'</b>"));
    }

    @Test void escapesExistingEntitiesAgainInsteadOfDoubleDecoding() {
        assertEquals("&amp;lt;script&amp;gt;", MapText.escapeHtml("&lt;script&gt;"));
    }

    @Test void nullAndEmptyLabelsStayEmpty() {
        assertEquals("", MapText.escapeHtml(null));
        assertEquals("", MapText.escapeHtml(""));
    }

    @Test void leavesUnicodeAndOrdinaryTextAlone() {
        assertEquals("Pasar Malam — 東京", MapText.escapeHtml("Pasar Malam — 東京"));
    }

    @Test void sanitisesIdsToTheCommonBackendAlphabet() {
        assertEquals("my_warp_2", MapText.sanitizeId("  My-Warp  #2  ", "fallback"));
        assertEquals("abc_def", MapText.sanitizeId("abc   def", "fallback"));
        assertEquals("a_b", MapText.sanitizeId("--a---b--", "fallback"));
        assertEquals("hi", MapText.sanitizeId("?hi", "fallback"));
        assertEquals("fallback", MapText.sanitizeId("", "fallback"));
        assertEquals("fallback", MapText.sanitizeId(null, "fallback"));
        assertEquals("marker", MapText.sanitizeId("!!!", "???"));
    }

    @Test void sanitisedIdsAreBoundedAndContainOnlySafeCharacters() {
        String id = MapText.sanitizeId("x".repeat(200), "fallback");
        assertEquals(48, id.length());
        assertTrue(id.matches("[a-z0-9_-]+"), id);
    }

    @Test void markerIdsAreDeterministic() {
        assertEquals(MapText.markerId("warp", "Spawn"), MapText.markerId("warp", "Spawn"));
    }

    @Test void markerIdsSeparateNamesThatSlugifyTheSame() {
        String first = MapText.markerId("portal", "Nether Gate");
        String second = MapText.markerId("portal", "nether_gate");
        assertEquals(MapText.sanitizeId("Nether Gate", "marker"), MapText.sanitizeId("nether_gate", "marker"));
        assertNotEquals(first, second);
        assertTrue(first.matches("[a-z0-9_-]+"), first);
        assertTrue(second.matches("[a-z0-9_-]+"), second);
    }

    @Test void markerIdsStayBoundedAndUniqueForManyNames() {
        List<String> ids = IntStream.range(0, 500)
                .mapToObj(i -> MapText.markerId("warp", "Warp " + i))
                .collect(Collectors.toList());
        assertEquals(500, ids.stream().distinct().count());
        for (String id : ids) {
            assertTrue(id.length() <= 48 + 1 + 48 + 1 + 8, id);
            assertFalse(id.isEmpty());
        }
    }

    @Test void markerIdUsesTheFallbackForBlankInput() {
        String id = MapText.markerId("portal", "");
        assertTrue(id.startsWith("portal_marker_"), id);
    }
}
