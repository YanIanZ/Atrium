package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatBubblesConfigTest {
    @Test
    void usesHeightAboveEyesByDefault() {
        ChatBubblesConfig config = new ChatBubblesConfig();
        assertEquals(0.55, config.effectiveHeightAboveEyes(), 1e-9);
    }

    @Test
    void convertsTheLegacyFeetRelativeOffset() {
        ChatBubblesConfig config = new ChatBubblesConfig();
        config.yOffset = 2.15;
        assertEquals(2.15 - 1.62, config.effectiveHeightAboveEyes(), 1e-9);
    }

    @Test
    void neverGoesBelowTheHeadForTinyLegacyValues() {
        ChatBubblesConfig config = new ChatBubblesConfig();
        config.yOffset = 0.5;
        assertEquals(0.1, config.effectiveHeightAboveEyes(), 1e-9);
    }
}
