package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EnginePriceTest {
    @Test
    void aWholeStackQuoteBecomesAUnitPrice() {
        assertEquals(Optional.of(new BigDecimal("0.78125000")), EnginePrice.unit(25.0, 32));
        assertEquals(Optional.of(new BigDecimal("2.00000000")), EnginePrice.unit(2.0, 1));
        assertEquals(Optional.of(new BigDecimal("2.00000000")), EnginePrice.unit(2.0, 0));
    }

    @Test
    void aMissingOrNonPositiveQuoteIsRefusedNotPaidAtZero() {
        assertTrue(EnginePrice.unit(-1.0, 1).isEmpty());
        assertTrue(EnginePrice.unit(0.0, 1).isEmpty());
        assertTrue(EnginePrice.unit(Double.NaN, 1).isEmpty());
        assertTrue(EnginePrice.nullable(null).isEmpty());
        assertTrue(EnginePrice.unit(new BigDecimal("-5"), 1).isEmpty());
    }

    @Test
    void vaultPriceWinsOverAnotherCurrency() {
        Map<String, Double> prices = new LinkedHashMap<>();
        prices.put("PLAYER_POINTS", 100.0);
        prices.put("VAULT", 2.5);
        assertEquals(Optional.of(new BigDecimal("2.50000000")), EnginePrice.vaultPrice(prices));
    }

    @Test
    void aSingleCurrencyQuoteIsUsed() {
        assertEquals(Optional.of(new BigDecimal("3.00000000")),
                EnginePrice.vaultPrice(Map.of("VAULT", 3.0)));
        assertEquals(Optional.of(new BigDecimal("4.00000000")),
                EnginePrice.vaultPrice(Map.of("ITEM", 4.0)));
    }

    @Test
    void severalForeignCurrenciesWithoutVaultAreRefused() {
        Map<String, Double> prices = new LinkedHashMap<>();
        prices.put("PLAYER_POINTS", 100.0);
        prices.put("EXP", 5.0);
        assertTrue(EnginePrice.vaultPrice(prices).isEmpty());
    }
}
