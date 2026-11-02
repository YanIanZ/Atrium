package dev.yanianz.atrium.module.portals;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/**
 * Price of one portal use. A portal with its own positive price overrides the configured default; zero or
 * a negative/absent value means free. Charged only after a destination was resolved.
 */
final class PortalCost {
    /** Upper bound of one use, so a typo in the config cannot empty a balance. */
    static final BigDecimal MAX = new BigDecimal("1000000000");

    private PortalCost() {}

    static BigDecimal of(@Nullable Double portalCost, double defaultCost) {
        double value = portalCost != null ? portalCost : defaultCost;
        if (!Double.isFinite(value) || value <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = BigDecimal.valueOf(value);
        return amount.compareTo(MAX) > 0 ? MAX : amount;
    }

    static boolean affordable(@Nullable BigDecimal balance, BigDecimal cost) {
        return balance != null && balance.compareTo(cost) >= 0;
    }
}
