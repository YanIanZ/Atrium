package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BubbleModeTest {
    @Test
    void configModeIsParsedCaseInsensitivelyAndFallsBackToAuto() {
        assertEquals(BubbleMode.AUTO, BubbleMode.parse("", null));
        assertEquals(BubbleMode.AUTO, BubbleMode.parse(null, null));
        assertEquals(BubbleMode.AUTO, BubbleMode.parse(" Auto ", null));
        assertEquals(BubbleMode.PACKETS, BubbleMode.parse("packets", null));
        assertEquals(BubbleMode.FOLLOW, BubbleMode.parse("FOLLOW", null));
        List<String> problems = new ArrayList<>();
        assertEquals(BubbleMode.AUTO, BubbleMode.parse("packet", (option, problem) -> problems.add(option)));
        assertEquals(List.of("mode"), problems);
    }

    @Test
    void effectiveModeUsesPacketsOnlyWhenTheyAreAvailable() {
        assertEquals(BubbleMode.PACKETS, BubbleMode.effective(BubbleMode.AUTO, true));
        assertEquals(BubbleMode.FOLLOW, BubbleMode.effective(BubbleMode.AUTO, false));
        assertEquals(BubbleMode.PACKETS, BubbleMode.effective(BubbleMode.PACKETS, true));
        assertEquals(BubbleMode.FOLLOW, BubbleMode.effective(BubbleMode.PACKETS, false));
        assertEquals(BubbleMode.FOLLOW, BubbleMode.effective(BubbleMode.FOLLOW, true));
        assertEquals(BubbleMode.FOLLOW, BubbleMode.effective(BubbleMode.FOLLOW, false));
    }

    @Test
    void idsAreTheConfigNames() {
        assertEquals("auto", BubbleMode.AUTO.id());
        assertEquals("packets", BubbleMode.PACKETS.id());
        assertEquals("follow", BubbleMode.FOLLOW.id());
    }
}
