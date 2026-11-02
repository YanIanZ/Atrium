package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Where the entities of one page go, relative to the hologram location (the bottom centre of the page).
 *
 * <p>Consecutive text lines without an own offset are merged into one element, which becomes a single
 * multi-line {@code TextDisplay}; item and block lines (and offset text lines) get their own element. The
 * elements are stacked bottom-up: the last line sits on the location, earlier lines above it.
 *
 * <p>Every entity of a hologram is spawned at the same location and positioned with its transformation
 * translation, so all of them stay in one chunk (and one region) whatever the offsets are.
 *
 * <p>Text sizes are estimates (the server cannot measure glyphs): {@link Metrics#charWidth} blocks per
 * visible character and {@link Metrics#lineHeight} blocks per line at scale 1, wrapped at the line width.
 */
record Layout(List<Element> elements, double width, double height) {
    /** One text pixel at scale 1 is 1/40 block (a 10 px line is 0.25 blocks). */
    static final double BLOCKS_PER_TEXT_PIXEL = 0.025;

    Layout {
        elements = List.copyOf(elements);
    }

    record Metrics(double charWidth, double lineHeight, double itemSize, double blockSize, double padding) {}

    sealed interface Element {
        float x();

        float y();

        float z();

        double width();

        double height();
    }

    /** {@code y} is the bottom of the text (a TextDisplay grows upwards from its origin). */
    record TextElement(List<String> lines, int visualLines, float x, float y, float z, double width, double height)
            implements Element {
        TextElement {
            lines = List.copyOf(lines);
        }
    }

    /** {@code y} is the centre of the item; {@code size} is the item edge length in blocks. */
    record ItemElement(LineContent content, float x, float y, float z, double width, double height) implements Element {}

    /** {@code x,y,z} is the minimum corner of the block (a BlockDisplay renders from its origin corner). */
    record BlockElement(LineContent.Block content, float x, float y, float z, double width, double height)
            implements Element {}

    int textElementCount() {
        return (int) elements.stream().filter(e -> e instanceof TextElement).count();
    }

    List<TextElement> textElements() {
        return elements.stream().filter(e -> e instanceof TextElement).map(e -> (TextElement) e).toList();
    }

    /**
     * @param visibleChars visible characters of a text template (tags removed)
     */
    static Layout of(List<HoloLine> page, float scale, float lineSpacing, int lineWidthPixels, Metrics metrics,
            ToIntFunction<String> visibleChars) {
        // 1. group lines top to bottom
        List<List<HoloLine>> groups = new ArrayList<>();
        List<HoloLine> text = null;
        for (HoloLine line : page) {
            boolean mergeable = line.content().isText() && !line.hasOffset();
            if (mergeable) {
                if (text == null) {
                    text = new ArrayList<>();
                    groups.add(text);
                }
                text.add(line);
            } else {
                text = null;
                groups.add(List.of(line));
            }
        }
        // 2. stack bottom-up
        double wrapWidth = lineWidthPixels * BLOCKS_PER_TEXT_PIXEL;
        List<Element> reversed = new ArrayList<>();
        double y = 0;
        double maxWidth = 0;
        for (int g = groups.size() - 1; g >= 0; g--) {
            List<HoloLine> group = groups.get(g);
            HoloLine first = group.getFirst();
            LineContent content = first.content();
            Element element;
            if (content.isText()) {
                int visual = 0;
                double widest = 0;
                List<String> templates = new ArrayList<>();
                for (HoloLine line : group) {
                    String template = ((LineContent.Text) line.content()).template();
                    templates.add(template);
                    double unwrapped = Math.max(0, visibleChars.applyAsInt(template)) * metrics.charWidth();
                    visual += Math.max(1, (int) Math.ceil(unwrapped / wrapWidth - 1e-9));
                    widest = Math.max(widest, Math.min(unwrapped, wrapWidth));
                }
                double height = visual * metrics.lineHeight() * scale;
                double width = widest * scale;
                element = new TextElement(templates, visual, first.offsetX(), (float) (y + first.offsetY()),
                        first.offsetZ(), width, height);
            } else if (content instanceof LineContent.Block block) {
                double size = metrics.blockSize() * scale;
                element = new BlockElement(block, (float) (first.offsetX() - size / 2), (float) (y + first.offsetY()),
                        (float) (first.offsetZ() - size / 2), size, size);
            } else {
                double size = metrics.itemSize() * scale;
                element = new ItemElement(content, first.offsetX(), (float) (y + size / 2 + first.offsetY()),
                        first.offsetZ(), size, size);
            }
            reversed.add(element);
            y += element.height();
            if (g > 0) {
                y += lineSpacing * scale;
            }
            maxWidth = Math.max(maxWidth, element.width() + 2 * Math.abs(first.offsetX()));
        }
        List<Element> elements = new ArrayList<>(reversed.reversed());
        return new Layout(elements, maxWidth, groups.isEmpty() ? 0 : y);
    }

    /** Size and vertical position of the click box (an {@code Interaction}, an axis-aligned box). */
    record Box(float width, float height, float yOffset) {}

    static final float MIN_BOX = 0.2f;

    /**
     * The click box covers the largest page, so it does not jump while pages change. Width and height come from
     * the text bounds (plus padding on every side) unless overridden; the box is vertically centred on the
     * content. A billboarded hologram turns towards the viewer, so a square footprint as wide as the text covers
     * it from every side.
     */
    static Box interactionBox(List<Layout> pages, float overrideWidth, float overrideHeight, double padding) {
        double width = 0;
        double height = 0;
        for (Layout layout : pages) {
            width = Math.max(width, layout.width());
            height = Math.max(height, layout.height());
        }
        float boxWidth = overrideWidth > 0 ? overrideWidth : (float) Math.max(MIN_BOX, width + 2 * padding);
        float boxHeight = overrideHeight > 0 ? overrideHeight : (float) Math.max(MIN_BOX, height + 2 * padding);
        float yOffset = (float) ((height - boxHeight) / 2);
        return new Box(boxWidth, boxHeight, yOffset);
    }
}
