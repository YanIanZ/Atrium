package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.module.worth.SellPlanner.Mode;
import dev.yanianz.atrium.module.worth.SellPlanner.Plan;
import dev.yanianz.atrium.module.worth.SellPlanner.Request;
import dev.yanianz.atrium.module.worth.SellPlanner.Slot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * An external engine quotes the unit prices; the planner still pays only for the items it takes. The fake
 * engine refuses dirt, so a sale must never deposit anything for it.
 */
class SellMoneyRuleTest {
    /** Prices items by material exactly like an external engine would, without needing a live ItemStack. */
    private static final class FakeEngine implements PriceEngine {
        private final Map<String, BigDecimal> prices;

        FakeEngine(Map<String, BigDecimal> prices) {
            this.prices = prices;
        }

        Optional<BigDecimal> price(ItemFacts facts) {
            return Optional.ofNullable(prices.get(facts.material()));
        }

        @Override
        public PriceEngineKind kind() {
            return PriceEngineKind.SHOPGUIPLUS;
        }

        @Override
        public PriceEngineCapabilities capabilities() {
            return PriceEngineCapabilities.shopGuiPlus();
        }

        @Override
        public Optional<BigDecimal> unitPrice(ItemStack stack, @Nullable Player player) {
            return Optional.empty();
        }
    }

    private static Slot slot(int index, ItemFacts facts, int amount, FakeEngine engine) {
        return new Slot(index, facts, amount, engine.price(facts).orElse(null));
    }

    private static final ItemFacts DIAMOND = ItemFacts.plain("diamond");
    private static final ItemFacts COBBLE = ItemFacts.plain("cobblestone");
    private static final ItemFacts DIRT = ItemFacts.plain("dirt");

    @Test
    void externalPricesPayOnlyForTheItemsThatAreTaken() {
        FakeEngine engine = new FakeEngine(Map.of(
                "diamond", new BigDecimal("12.5"),
                "cobblestone", new BigDecimal("0.5")));
        List<Slot> slots = List.of(slot(0, DIAMOND, 3, engine), slot(1, DIRT, 64, engine),
                slot(2, COBBLE, 10, engine));

        Plan plan = SellPlanner.plan(slots, new Request(Mode.ALL, 0, null, 0, null));
        assertEquals(List.of(0, 2), plan.takes().stream().map(SellPlanner.Take::slot).toList());
        assertEquals(new BigDecimal("42.5"), plan.total());
        assertEquals(13, plan.items());
        BigDecimal sum = plan.takes().stream()
                .map(take -> take.unitPrice().multiply(BigDecimal.valueOf(take.amount())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(plan.total()), "payment must equal the value of exactly what is removed");
    }

    @Test
    void anItemTheExternalEngineDoesNotSellIsRefused() {
        FakeEngine engine = new FakeEngine(Map.of());
        Plan plan = SellPlanner.plan(List.of(slot(0, DIAMOND, 9, engine)),
                new Request(Mode.ALL, 0, null, 0, null));
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.total().signum());
    }

    @Test
    void aNonPositiveExternalPriceIsNotPaid() {
        FakeEngine engine = new FakeEngine(Map.of(
                "diamond", BigDecimal.ZERO,
                "emerald", new BigDecimal("-2")));
        Plan plan = SellPlanner.plan(List.of(slot(0, DIAMOND, 1, engine),
                        slot(1, ItemFacts.plain("emerald"), 1, engine)),
                new Request(Mode.ALL, 0, null, 0, null));
        assertTrue(plan.isEmpty());
    }

    @Test
    void theBudgetUsesTheExternalUnitPrice() {
        FakeEngine engine = new FakeEngine(Map.of("diamond", new BigDecimal("10")));
        Plan plan = SellPlanner.plan(List.of(slot(0, DIAMOND, 5, engine)),
                new Request(Mode.ALL, 0, null, 0, new BigDecimal("25")));
        assertTrue(plan.limitedByBudget());
        assertEquals(2, plan.takes().getFirst().amount());
        assertEquals(new BigDecimal("20"), plan.total());
    }
}
