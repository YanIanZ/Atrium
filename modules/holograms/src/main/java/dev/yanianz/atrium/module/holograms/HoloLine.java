package dev.yanianz.atrium.module.holograms;

import java.util.Objects;

/** A stored line: raw text plus an optional offset in blocks (applied to this line only). */
record HoloLine(String raw, float offsetX, float offsetY, float offsetZ) {
    HoloLine {
        Objects.requireNonNull(raw, "raw");
        if (!Float.isFinite(offsetX) || !Float.isFinite(offsetY) || !Float.isFinite(offsetZ)) {
            throw new IllegalArgumentException("non-finite offset");
        }
    }

    static HoloLine of(String raw) {
        return new HoloLine(raw, 0f, 0f, 0f);
    }

    LineContent content() {
        return LineContent.parse(raw);
    }

    boolean hasOffset() {
        return offsetX != 0f || offsetY != 0f || offsetZ != 0f;
    }

    HoloLine withRaw(String newRaw) {
        return new HoloLine(newRaw, offsetX, offsetY, offsetZ);
    }

    HoloLine withOffset(float x, float y, float z) {
        return new HoloLine(raw, x, y, z);
    }
}
