package dev.yanianz.atrium.module.particles;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class SlotLimitsTest {
    @Test
    void defaultAppliesWithoutNumericPermission() {
        assertEquals(2, SlotLimits.resolve(2, false, OptionalInt.empty()));
    }

    @Test
    void numericPermissionRaisesTheLimit() {
        assertEquals(5, SlotLimits.resolve(2, false, OptionalInt.of(5)));
    }

    @Test
    void numericPermissionNeverLowersTheConfiguredLimit() {
        assertEquals(3, SlotLimits.resolve(3, false, OptionalInt.of(1)));
    }

    @Test
    void unlimitedWinsOverNumbers() {
        assertEquals(SlotLimits.UNLIMITED, SlotLimits.resolve(2, true, OptionalInt.of(4)));
    }

    @Test
    void negativeFallbackIsClampedToZero() {
        assertEquals(0, SlotLimits.resolve(-1, false, OptionalInt.empty()));
    }
}
