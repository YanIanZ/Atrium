package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class InspectFormatTest {
    @Test
    void blockDataDropsTheNamespaceAndSpacesProperties() {
        assertEquals("oak_stairs[facing=north, half=bottom, shape=straight, waterlogged=false]",
                InspectFormat.blockData(
                        "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        assertEquals("stone", InspectFormat.blockData("minecraft:stone"));
        assertEquals("modid:custom_block[a=1]", InspectFormat.blockData("modid:custom_block[a=1]"));
        assertEquals("", InspectFormat.blockData(""));
        assertEquals("stone", InspectFormat.blockData("minecraft:stone["));
    }

    @Test
    void shortKeyOnlyStripsTheMinecraftNamespace() {
        assertEquals("plains", InspectFormat.shortKey("minecraft:plains"));
        assertEquals("modid:block", InspectFormat.shortKey("modid:block"));
        assertEquals("", InspectFormat.shortKey(null));
    }

    @Test
    void truncateNeverReturnsMoreThanTheLimit() {
        assertEquals("abcdef", InspectFormat.truncate("abcdef", 6));
        assertEquals("abcd\u2026", InspectFormat.truncate("abcdef", 5));
        assertEquals(5, InspectFormat.truncate("abcdef", 5).length());
        assertEquals("", InspectFormat.truncate(null, 4));
    }

    @Test
    void plainTextDoesNotParseMiniMessage() {
        Component text = Component.text("<red>not formatted</red>");
        assertEquals("<red>not formatted</red>", InspectFormat.plain(text));
        assertEquals("", InspectFormat.plain(null));
    }

    @Test
    void snbtEscapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\\b\"", InspectFormat.snbtQuote("a\\b"));
        assertEquals("\"a\\\"b\"", InspectFormat.snbtQuote("a\"b"));
        assertEquals("\"a\\nb\"", InspectFormat.snbtQuote("a\nb"));
    }

    @Test
    void snbtItemKeepsComponentOrderAndShape() {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("minecraft:custom_name", "Sword \"A\"");
        components.put("minecraft:damage", "3");
        assertEquals("{id:\"minecraft:diamond_sword\",count:1,components:{"
                        + "\"minecraft:custom_name\":\"Sword \\\"A\\\"\",\"minecraft:damage\":\"3\"}}",
                InspectFormat.snbtItem("minecraft:diamond_sword", 1, components));
        assertEquals("{id:\"minecraft:stone\",count:64}",
                InspectFormat.snbtItem("minecraft:stone", 64, Map.of()));
    }

    @Test
    void addressDetectionAcceptsIpv4AndIpv6Only() {
        assertTrue(InspectFormat.isAddress("127.0.0.1"));
        assertTrue(InspectFormat.isAddress("192.168.1.255"));
        assertTrue(InspectFormat.isAddress("::1"));
        assertTrue(InspectFormat.isAddress("2001:db8::1"));
        assertFalse(InspectFormat.isAddress("Notch"));
        assertFalse(InspectFormat.isAddress("256.1.1.1"));
        assertFalse(InspectFormat.isAddress("1.2.3"));
        assertFalse(InspectFormat.isAddress("1.2.3.4.5"));
        assertFalse(InspectFormat.isAddress(""));
        assertFalse(InspectFormat.isAddress(null));
    }

    @Test
    void dateFormatterRejectsInvalidZones() {
        assertThrows(IllegalArgumentException.class, () -> InspectFormat.formatter("yyyy-MM-dd", "Not/AZone"));
        var formatter = InspectFormat.formatter("yyyy-MM-dd HH:mm", "UTC");
        assertEquals("1970-01-01 00:00", InspectFormat.absolute(Instant.EPOCH, formatter));
    }

    @Test
    void sinceClampsClockSkew() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        assertEquals(java.time.Duration.ZERO, InspectFormat.since(now.plusSeconds(30), now));
        assertEquals(java.time.Duration.ofSeconds(30), InspectFormat.since(now.minusSeconds(30), now));
    }

    @Test
    void numbersAndPercentAreLocaleIndependent() {
        assertEquals("20", InspectFormat.number(20.0));
        assertEquals("20.5", InspectFormat.number(20.5));
        assertEquals("50", InspectFormat.percent(0.5));
        assertEquals("12.5", InspectFormat.percent(0.125));
    }
}
