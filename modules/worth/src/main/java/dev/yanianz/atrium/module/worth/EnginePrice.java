package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Small conversions shared by the external price engines; pure so they can be tested without a server. */
final class EnginePrice {
    /** Scale used when an engine quotes a total for several items and Atrium pays per unit. */
    static final int UNIT_SCALE = 8;

    private EnginePrice() {}

    /**
     * Unit price from a total quoted for {@code amount} items, rounded down. A missing, zero or negative
     * total means the engine does not sell the item, so it is refused instead of being paid at 0.
     */
    static Optional<BigDecimal> unit(double total, int amount) {
        if (!Double.isFinite(total)) {
            return Optional.empty();
        }
        return unit(BigDecimal.valueOf(total), amount);
    }

    static Optional<BigDecimal> unit(BigDecimal total, int amount) {
        if (total == null || total.signum() <= 0) {
            return Optional.empty();
        }
        int units = Math.max(1, amount);
        BigDecimal value = total.divide(BigDecimal.valueOf(units), UNIT_SCALE, RoundingMode.DOWN);
        return value.signum() > 0 ? Optional.of(value) : Optional.empty();
    }

    /**
     * The Vault price of a multi-currency quote. Atrium pays through its own economy, so a price in another
     * currency (experience, points, ...) must never be paid as money: a single non-Vault price is refused
     * unless it is the only price quoted.
     */
    static Optional<BigDecimal> vaultPrice(Map<String, Double> pricesByCurrency) {
        for (Map.Entry<String, Double> entry : pricesByCurrency.entrySet()) {
            if ("VAULT".equalsIgnoreCase(entry.getKey())) {
                Double value = entry.getValue();
                return value == null ? Optional.empty() : unit(value, 1);
            }
        }
        if (pricesByCurrency.size() == 1) {
            return unit(pricesByCurrency.values().iterator().next(), 1);
        }
        return Optional.empty();
    }

    static Optional<BigDecimal> nullable(@Nullable Double total) {
        return total == null ? Optional.empty() : unit(total, 1);
    }
}
