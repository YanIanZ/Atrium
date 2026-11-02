package dev.yanianz.atrium.module.holograms;

import java.util.Objects;

/**
 * A stored line: raw text plus an optional offset in blocks (applied to this line only) and optional per-line
 * hover text and click command.
 *
 * <p>{@code hover} is a MiniMessage template; {@code click} is a command without a leading slash. Empty means
 * "not set". Both are limited to text lines: an item or block line cannot carry them.
 */
record HoloLine(String raw, float offsetX, float offsetY, float offsetZ, String hover, String click) {
    HoloLine {
        Objects.requireNonNull(raw, "raw");
        hover = hover == null ? "" : hover;
        click = click == null ? "" : click;
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY) || !Float.isFinite(offsetZ)) {
            throw new IllegalArgumentException("non-finite offset");
        }
    }

    /** A line without an offset, hover or click. */
    static HoloLine of(String raw) {
        return new HoloLine(raw, 0f, 0f, 0f, "", "");
    }

    LineContent content() {
        return LineContent.parse(raw);
    }

    boolean hasOffset() {
        return offsetX != 0f || offsetY != 0f || offsetZ != 0f;
    }

    boolean hasEvents() {
        return !hover.isEmpty() || !click.isEmpty();
    }

    HoloLine withRaw(String newRaw) {
        return new HoloLine(newRaw, offsetX, offsetY, offsetZ, hover, click);
    }

    HoloLine withOffset(float x, float y, float z) {
        return new HoloLine(raw, x, y, z, hover, click);
    }

    HoloLine withHover(String newHover) {
        return new HoloLine(raw, offsetX, offsetY, offsetZ, newHover, click);
    }

    HoloLine withClick(String newClick) {
        return new HoloLine(raw, offsetX, offsetY, offsetZ, hover, newClick);
    }
}
