package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BubbleAnchorTest {
    private static final double EYE_HEIGHT = 1.62;
    private static final double Y_OFFSET = 0.55;

    @Test
    void stationaryPlayerSitsAtEyeHeightPlusOffset() {
        BubbleAnchor.Point point = BubbleAnchor.follow(10, 64, -3, 0, 0, 0, EYE_HEIGHT, Y_OFFSET);
        assertEquals(10, point.x(), 1e-9);
        assertEquals(64 + EYE_HEIGHT + Y_OFFSET, point.y(), 1e-9);
        assertEquals(-3, point.z(), 1e-9);
    }

    @Test
    void horizontalVelocityIsAppliedOneTickAhead() {
        BubbleAnchor.Point point = BubbleAnchor.follow(10, 64, -3, 0.2, 0, -0.1, EYE_HEIGHT, Y_OFFSET);
        assertEquals(10.2, point.x(), 1e-9);
        assertEquals(-3.1, point.z(), 1e-9);
        assertEquals(64 + EYE_HEIGHT + Y_OFFSET, point.y(), 1e-9);
    }

    @Test
    void verticalVelocityIsAppliedOneTickAhead() {
        BubbleAnchor.Point rising = BubbleAnchor.follow(0, 64, 0, 0, 0.42, 0, EYE_HEIGHT, Y_OFFSET);
        BubbleAnchor.Point falling = BubbleAnchor.follow(0, 64, 0, 0, -0.08, 0, EYE_HEIGHT, Y_OFFSET);
        assertEquals(64 + EYE_HEIGHT + Y_OFFSET + 0.42, rising.y(), 1e-9);
        assertEquals(64 + EYE_HEIGHT + Y_OFFSET - 0.08, falling.y(), 1e-9);
    }

    @Test
    void packetTranslationIsRelativeToThePlayersFeet() {
        assertEquals(2.17, BubbleAnchor.translationY(EYE_HEIGHT, Y_OFFSET), 1e-9);
    }
}
