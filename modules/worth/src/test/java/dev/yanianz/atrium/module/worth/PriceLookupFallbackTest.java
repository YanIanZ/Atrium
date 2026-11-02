package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The price-lookup fallback: a failed external call answers from atrium, an empty answer does not. */
class PriceLookupFallbackTest {
    private static final class Engine implements PriceEngine {
        private final PriceEngineKind kind;
        private final Optional<BigDecimal> price;
        private final RuntimeException failure;

        Engine(PriceEngineKind kind, @Nullable BigDecimal price, @Nullable RuntimeException failure) {
            this.kind = kind;
            this.price = Optional.ofNullable(price);
            this.failure = failure;
        }

        @Override
        public PriceEngineKind kind() {
            return kind;
        }

        @Override
        public PriceEngineCapabilities capabilities() {
            return PriceEngineCapabilities.of(kind);
        }

        @Override
        public Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) {
            if (failure != null) {
                throw failure;
            }
            return price;
        }
    }

    private static final Engine ATRIUM = new Engine(PriceEngineKind.ATRIUM, new BigDecimal("3"), null);

    @Test
    void aFailedExternalLookupFallsBackToAtrium() {
        Engine broken = new Engine(PriceEngineKind.ECONOMYSHOPGUI, null, new PriceEngineException("boom"));
        PriceEngines.Quote quote = PriceEngines.quote(broken, ATRIUM, null, null);
        assertEquals(Optional.of(new BigDecimal("3")), quote.price());
        assertNotNull(quote.failure());
        assertEquals("boom", quote.failure().getMessage());
    }

    @Test
    void anItemTheExternalEngineDoesNotSellIsNotRepricedByAtrium() {
        Engine refuses = new Engine(PriceEngineKind.SHOPGUIPLUS, null, null);
        PriceEngines.Quote quote = PriceEngines.quote(refuses, ATRIUM, null, null);
        assertTrue(quote.price().isEmpty());
        assertNull(quote.failure());
    }

    @Test
    void theExternalPriceWinsWhenTheCallSucceeds() {
        Engine works = new Engine(PriceEngineKind.SHOPGUIPLUS, new BigDecimal("9.5"), null);
        assertEquals(Optional.of(new BigDecimal("9.5")), PriceEngines.quote(works, ATRIUM, null, null).price());
    }

    @Test
    void aFailingAtriumEngineIsReportedNotRetried() {
        Engine bothBroken = new Engine(PriceEngineKind.SHOPGUIPLUS, null, new PriceEngineException("shop down"));
        Engine atriumBroken = new Engine(PriceEngineKind.ATRIUM, null, new PriceEngineException("table down"));
        RuntimeException thrown = assertThrows(PriceEngineException.class,
                () -> PriceEngines.quote(bothBroken, atriumBroken, null, null));
        assertEquals(1, thrown.getSuppressed().length);
    }
}
