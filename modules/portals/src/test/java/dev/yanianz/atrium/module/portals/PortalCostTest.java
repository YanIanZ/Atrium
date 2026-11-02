package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PortalCostTest {
    @Test
    void portalPriceOverridesTheDefaultAndZeroIsFree() {
        assertEquals(new BigDecimal("12.5"), PortalCost.of(12.5, 3));
        assertEquals(new BigDecimal("3.0"), PortalCost.of(null, 3));
        assertEquals(BigDecimal.ZERO, PortalCost.of(0.0, 3));
        assertEquals(BigDecimal.ZERO, PortalCost.of(-1.0, 3));
        assertEquals(BigDecimal.ZERO, PortalCost.of(Double.NaN, 0));
        assertEquals(new BigDecimal("2.0"), PortalCost.of(null, 2));
    }

    @Test
    void absurdPricesAreCapped() {
        assertEquals(PortalCost.MAX, PortalCost.of(1e12, 0));
        assertEquals(PortalCost.MAX, PortalCost.of(null, 1e12));
    }

    @Test
    void affordabilityIsInclusive() {
        assertTrue(PortalCost.affordable(new BigDecimal("10"), new BigDecimal("10")));
        assertTrue(PortalCost.affordable(new BigDecimal("10.01"), new BigDecimal("10")));
        assertFalse(PortalCost.affordable(new BigDecimal("9.99"), new BigDecimal("10")));
        assertFalse(PortalCost.affordable(null, BigDecimal.ONE));
    }
}
