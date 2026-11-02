package dev.yanianz.atrium.module.holograms;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
    private static final Layout.Metrics METRICS = new Layout.Metrics(0.15, 0.25, 0.5, 0.5, 0.1);
    private static final double EPS = 1e-5;

    private static Layout layout(List<HoloLine> page, float scale, float spacing, int lineWidth) {
        return Layout.of(page, scale, spacing, lineWidth, METRICS, String::length);
    }

    private static List<HoloLine> lines(String... raw) {
        return java.util.Arrays.stream(raw).map(HoloLine::of).toList();
    }

    @Test void mergesConsecutiveTextIntoOneElement() {
        Layout layout = layout(lines("aaaa", "bb", "cccccc"), 1f, 0f, 200);
        assertEquals(1, layout.elements().size());
        Layout.TextElement text = (Layout.TextElement) layout.elements().getFirst();
        assertEquals(List.of("aaaa", "bb", "cccccc"), text.lines());
        assertEquals(3, text.visualLines());
        assertEquals(0.75, layout.height(), EPS);
        assertEquals(6 * 0.15, layout.width(), EPS);
        assertEquals(0f, text.y(), EPS);
    }

    @Test void itemLinesSplitTextAndStackBottomUp() {
        Layout layout = layout(lines("top", "#item:diamond", "bottom1", "bottom2"), 1f, 0.1f, 200);
        assertEquals(3, layout.elements().size());
        var top = (Layout.TextElement) layout.elements().get(0);
        var item = (Layout.ItemElement) layout.elements().get(1);
        var bottom = (Layout.TextElement) layout.elements().get(2);
        // bottom text: 2 lines from y=0 to 0.5; spacing 0.1; item 0.5 high centred at 0.85; spacing; top at 1.2
        assertEquals(0.0, bottom.y(), EPS);
        assertEquals(0.5, bottom.height(), EPS);
        assertEquals(0.6 + 0.25, item.y(), EPS);
        assertEquals(1.2, top.y(), EPS);
        assertEquals(1.45, layout.height(), EPS);
    }

    @Test void blockLinesAreCornerAnchored() {
        Layout layout = layout(lines("#block:stone"), 2f, 0f, 200);
        var block = (Layout.BlockElement) layout.elements().getFirst();
        assertEquals(1.0, block.width(), EPS);
        assertEquals(-0.5f, block.x(), EPS);
        assertEquals(-0.5f, block.z(), EPS);
        assertEquals(0f, block.y(), EPS);
        assertEquals(1.0, layout.height(), EPS);
    }

    @Test void scaleAndSpacingScaleEverything() {
        Layout layout = layout(lines("ab", "#item:stone"), 2f, 0.1f, 200);
        assertEquals(0.5 * 2 + 0.2 + 0.25 * 2, layout.height(), EPS);
        assertEquals(1.0, layout.width(), EPS); // item 0.5 x 2 is wider than "ab" (0.3 x 2)
    }

    @Test void longLinesWrapAtTheLineWidth() {
        // 100 px wrap = 2.5 blocks; 40 chars = 6 blocks -> 3 visual lines, width capped at the wrap width
        Layout layout = layout(lines("x".repeat(40)), 1f, 0f, 100);
        var text = (Layout.TextElement) layout.elements().getFirst();
        assertEquals(3, text.visualLines());
        assertEquals(2.5, layout.width(), EPS);
        assertEquals(0.75, layout.height(), EPS);
    }

    @Test void offsetTextLinesGetTheirOwnElement() {
        List<HoloLine> page = List.of(HoloLine.of("a"), HoloLine.of("b").withOffset(1f, 0.5f, 0f), HoloLine.of("c"));
        Layout layout = layout(page, 1f, 0f, 200);
        assertEquals(3, layout.elements().size());
        var offset = (Layout.TextElement) layout.elements().get(1);
        assertEquals(1f, offset.x(), EPS);
        assertEquals(0.25 + 0.5, offset.y(), EPS);
        assertEquals(0.15 + 2, layout.width(), EPS);
    }

    @Test void emptyPageHasNoElements() {
        Layout layout = layout(List.of(), 1f, 0.5f, 200);
        assertTrue(layout.elements().isEmpty());
        assertEquals(0, layout.height(), EPS);
    }

    @Test void emptyTextLineStillTakesALine() {
        Layout layout = layout(lines("", "abc"), 1f, 0f, 200);
        assertEquals(0.5, layout.height(), EPS);
    }

    @Test void interactionBoxCoversTheLargestPageWithPadding() {
        Layout small = layout(lines("ab"), 1f, 0f, 200);
        Layout tall = layout(lines("a", "b", "c", "d"), 1f, 0f, 200);
        Layout.Box box = Layout.interactionBox(List.of(small, tall), 0f, 0f, 0.1);
        assertEquals(0.3 + 0.2, box.width(), EPS);
        assertEquals(1.0 + 0.2, box.height(), EPS);
        assertEquals(-0.1f, box.yOffset(), EPS); // padding below the content
    }

    @Test void interactionBoxOverridesAreCentredOnTheContent() {
        Layout tall = layout(lines("a", "b", "c", "d"), 1f, 0f, 200);
        Layout.Box box = Layout.interactionBox(List.of(tall), 3f, 2f, 0.1);
        assertEquals(3f, box.width(), EPS);
        assertEquals(2f, box.height(), EPS);
        assertEquals(-0.5f, box.yOffset(), EPS); // (1.0 - 2.0) / 2
        // the box spans [-0.5, 1.5]: its centre is the content centre 0.5
        assertEquals(0.5, box.yOffset() + box.height() / 2, EPS);
    }

    @Test void interactionBoxHasAMinimumSize() {
        Layout.Box box = Layout.interactionBox(List.of(layout(List.of(), 1f, 0f, 200)), 0f, 0f, 0);
        assertEquals(Layout.MIN_BOX, box.width(), EPS);
        assertEquals(Layout.MIN_BOX, box.height(), EPS);
    }

    @Test void interactionBoxScalesWithTheHologram() {
        Layout big = layout(lines("abcd"), 2f, 0f, 200);
        Layout.Box box = Layout.interactionBox(List.of(big), 0f, 0f, 0.1);
        assertEquals(4 * 0.15 * 2 + 0.2, box.width(), EPS);
        assertEquals(0.25 * 2 + 0.2, box.height(), EPS);
    }
}
