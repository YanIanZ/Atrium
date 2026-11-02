package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class LineTextTest {
    /** A width counter that ignores MiniMessage tags, good enough for the pure tests. */
    private static final ToIntFunction<String> WIDTH = text -> {
        String plain = text.replaceAll("<[^>]*>", "");
        return plain.replace("%filler%", "").length();
    };

    private static List<HoloLine> lines(String... raw) {
        return java.util.Arrays.stream(raw).map(HoloLine::of).toList();
    }

    @Test
    void rainbowMarkerIsConvertedAndRemoved() {
        List<HoloLine> prepared = LineText.preparePage(lines("&uHello"), WIDTH, " ");
        assertEquals("<rainbow>Hello</rainbow>", prepared.getFirst().raw());
        assertTrue(LineText.hasMarker("&UHello"));
        assertFalse(LineText.hasMarker("&aAbc"));
        assertEquals("Hello", LineText.stripMarker("&u Hello"));
    }

    @Test
    void otherLinesAreUntouched() {
        List<HoloLine> prepared = LineText.preparePage(lines("<gray>Hi", "&aGreen"), WIDTH, " ");
        assertEquals("<gray>Hi", prepared.get(0).raw());
        assertEquals("&aGreen", prepared.get(1).raw());
    }

    @Test
    void fillerPadsUpToTheWidestLineOfThePage() {
        List<HoloLine> prepared = LineText.preparePage(lines("12345", "ab%filler%"), WIDTH, " ");
        assertEquals("ab   ", prepared.get(1).raw());
    }

    @Test
    void twoFillersShareThePadding() {
        List<HoloLine> prepared = LineText.preparePage(lines("12345", "%filler%ab%filler%"), WIDTH, " ");
        assertEquals("  ab ", prepared.get(1).raw());
    }

    @Test
    void aLineLongerThanTheTargetGetsNoPadding() {
        List<HoloLine> prepared = LineText.preparePage(lines("abcdef%filler%", "12345"), WIDTH, " ");
        assertEquals("abcdef", prepared.getFirst().raw());
    }

    @Test
    void fillerWorksWithACustomCharacter() {
        List<HoloLine> prepared = LineText.preparePage(lines("1234", "ab%filler%"), WIDTH, "-");
        assertEquals("ab--", prepared.get(1).raw());
    }

    @Test
    void fillerAndRainbowCombine() {
        List<HoloLine> prepared = LineText.preparePage(lines("1234", "&uab%filler%"), WIDTH, " ");
        assertEquals("<rainbow>ab  </rainbow>", prepared.get(1).raw());
    }

    @Test
    void theRawTextOfANonTextLineIsKept() {
        List<HoloLine> prepared = LineText.preparePage(lines("#item:diamond"), WIDTH, " ");
        assertEquals("#item:diamond", prepared.getFirst().raw());
    }
}
