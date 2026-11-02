package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class BubbleReceiversTest {
    private static final UUID SPEAKER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_WORLD = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private static BubbleReceivers.Viewer viewer(UUID world, double x, double y, double z) {
        return new BubbleReceivers.Viewer(VIEWER, world, x, y, z);
    }

    private static boolean accepts(BubbleReceivers.Viewer viewer, boolean visible, boolean ignoring,
            boolean hideFromSender) {
        return BubbleReceivers.accepts(viewer, SPEAKER, WORLD, 0, 64, 0, 48, visible, ignoring, hideFromSender);
    }

    @Test
    void sameWorldInsideRangeIsAccepted() {
        assertTrue(accepts(viewer(WORLD, 0, 64, 0), true, false, false));
        assertTrue(accepts(viewer(WORLD, 48, 64, 0), true, false, false));
    }

    @Test
    void anotherWorldOrOutsideRangeIsRejected() {
        assertFalse(accepts(viewer(OTHER_WORLD, 0, 64, 0), true, false, false));
        assertFalse(accepts(viewer(WORLD, 48.01, 64, 0), true, false, false));
        assertFalse(accepts(new BubbleReceivers.Viewer(VIEWER, null, 0, 64, 0), true, false, false));
    }

    @Test
    void vanishedOrIgnoredSpeakersAreRejected() {
        assertFalse(accepts(viewer(WORLD, 0, 64, 0), false, false, false));
        assertFalse(accepts(viewer(WORLD, 0, 64, 0), true, true, false));
    }

    @Test
    void theSenderOnlyDependsOnHideFromSender() {
        BubbleReceivers.Viewer self = new BubbleReceivers.Viewer(SPEAKER, OTHER_WORLD, 500, 0, 500);
        assertTrue(accepts(self, false, true, false));
        assertFalse(accepts(self, true, false, true));
    }
}
