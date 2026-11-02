package dev.yanianz.atrium.module.chatbubbles;

/**
 * Vanilla metadata ids for a TextDisplay, derived from the data layout each version registers:
 * Entity contributes 8 ids; Display contributes its interpolation ids and transformation fields; then
 * TextDisplay contributes text, line width, background, opacity and style flags.
 *
 * <p>Display gained a third interpolation id in 1.21.2 (position/rotation duration was split from
 * transformation duration), so every later id moves one place.
 */
record BubbleMetaIds(int translation, int billboard, int viewRange, int text, int lineWidth, int background,
        int opacity, int styleFlags) {
    static BubbleMetaIds of(boolean splitInterpolation) {
        return splitInterpolation
                ? new BubbleMetaIds(11, 15, 17, 23, 24, 25, 26, 27)
                : new BubbleMetaIds(10, 14, 16, 22, 23, 24, 25, 26);
    }

    /** Display scale follows translation directly (translation, scale, left rotation, ...). */
    int scale() {
        return translation + 1;
    }
}
