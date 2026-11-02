package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BubbleMetaIdsTest {
    @Test
    void modernDisplayHasThreeInterpolationIdsBeforeTranslation() {
        assertEquals(new BubbleMetaIds(11, 15, 17, 23, 24, 25, 26, 27), BubbleMetaIds.of(true));
    }

    @Test
    void olderDisplayHasTwoInterpolationIdsBeforeTranslation() {
        assertEquals(new BubbleMetaIds(10, 14, 16, 22, 23, 24, 25, 26), BubbleMetaIds.of(false));
    }
}
