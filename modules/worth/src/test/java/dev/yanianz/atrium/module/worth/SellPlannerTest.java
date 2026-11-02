package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.module.worth.SellPlanner.Mode;
import dev.yanianz.atrium.module.worth.SellPlanner.Plan;
import dev.yanianz.atrium.module.worth.SellPlanner.Request;
import dev.yanianz.atrium.module.worth.SellPlanner.Slot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SellPlannerTest {
    private final PriceTable prices = new PriceTable(List.of(
            PriceEntry.plain("diamond", new BigDecimal("100")),
            PriceEntry.plain("cobblestone", new BigDecimal("0.1")),
            PriceEntry.plain("diamond_helmet", new BigDecimal("200"))),
            Map.of(), PriceTable.Options.defaults());

    private static final ItemFacts DIAMOND = ItemFacts.plain("diamond");
    private static final ItemFacts COBBLE = ItemFacts.plain("cobblestone").withBlock(true);
    private static final ItemFacts HELMET = ItemFacts.plain("diamond_helmet");

    private static Request req(Mode mode, int held) {
        return new Request(mode, held, null, 0, null);
    }

    @Test
    void sameNeverTakesArmorSlots() {
        // /sell same must never pay for worn armor (slot 39) that is not removed.
        List<Slot> slots = List.of(new Slot(0, HELMET, 1), new Slot(39, HELMET, 1), new Slot(36, HELMET, 1));
        Plan plan = SellPlanner.plan(prices, slots, req(Mode.SAME, 0));
        assertEquals(1, plan.takes().size());
        assertEquals(0, plan.takes().getFirst().slot());
        assertEquals(new BigDecimal("200.00"), plan.total());
        assertEquals(1, plan.items());
    }

    @Test
    void allTakesStorageAndOffhandOnly() {
        List<Slot> slots = List.of(new Slot(5, DIAMOND, 3), new Slot(SellPlanner.OFFHAND, DIAMOND, 2),
                new Slot(38, HELMET, 1), new Slot(20, COBBLE, 64), new Slot(-1, DIAMOND, 1), new Slot(41, DIAMOND, 1));
        Plan plan = SellPlanner.plan(prices, slots, req(Mode.ALL, 0));
        assertEquals(List.of(5, 20, SellPlanner.OFFHAND), plan.takes().stream().map(SellPlanner.Take::slot).toList());
        assertEquals(new BigDecimal("506.40"), plan.total());
        assertEquals(69, plan.items());
        // Total equals the sum of exactly what is taken.
        BigDecimal sum = plan.takes().stream().map(t -> t.unitPrice().multiply(BigDecimal.valueOf(t.amount())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(plan.total()));
    }

    @Test
    void handOnlyTakesHeldSlotWithLimit() {
        List<Slot> slots = List.of(new Slot(2, DIAMOND, 10), new Slot(3, DIAMOND, 10));
        Plan plan = SellPlanner.plan(prices, slots, new Request(Mode.HAND, 3, null, 4, null));
        assertEquals(1, plan.takes().size());
        assertEquals(3, plan.takes().getFirst().slot());
        assertEquals(4, plan.takes().getFirst().amount());
        assertEquals(new BigDecimal("400.00"), plan.total());
    }

    @Test
    void sameStartsWithHandAndMatchesIdenticalFactsOnly() {
        ItemFacts named = DIAMOND.withName("Gem");
        List<Slot> slots = List.of(new Slot(0, DIAMOND, 5), new Slot(4, DIAMOND, 5), new Slot(7, named, 5));
        Plan plan = SellPlanner.plan(prices, slots, new Request(Mode.SAME, 4, null, 7, null));
        assertEquals(List.of(4, 0), plan.takes().stream().map(SellPlanner.Take::slot).toList());
        assertEquals(List.of(5, 2), plan.takes().stream().map(SellPlanner.Take::amount).toList());
    }

    @Test
    void blocksAndMaterialModes() {
        List<Slot> slots = List.of(new Slot(0, DIAMOND, 5), new Slot(1, COBBLE, 64));
        assertEquals(List.of(1), SellPlanner.plan(prices, slots, req(Mode.BLOCKS, 0)).takes().stream().map(SellPlanner.Take::slot).toList());
        Plan material = SellPlanner.plan(prices, slots, new Request(Mode.MATERIAL, 8, "diamond", 0, null));
        assertEquals(List.of(0), material.takes().stream().map(SellPlanner.Take::slot).toList());
    }

    @Test
    void unsellableItemsAreSkipped() {
        List<Slot> slots = List.of(new Slot(0, ItemFacts.plain("dirt"), 64), new Slot(1, DIAMOND.withName("x"), 1));
        Plan plan = SellPlanner.plan(prices, slots, req(Mode.ALL, 0));
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.total().signum());
    }

    @Test
    void budgetLimitsItemsTakenAndFlagsIt() {
        List<Slot> slots = List.of(new Slot(0, DIAMOND, 10), new Slot(1, COBBLE, 64));
        Plan plan = SellPlanner.plan(prices, slots, new Request(Mode.ALL, 0, null, 0, new BigDecimal("350")));
        assertTrue(plan.limitedByBudget());
        assertEquals(3, plan.takes().getFirst().amount());
        // Remaining 50 buys cobblestone: 64 * 0.1 = 6.4 fits.
        assertEquals(new BigDecimal("306.40"), plan.total());
        assertTrue(plan.total().compareTo(new BigDecimal("350")) <= 0);

        Plan none = SellPlanner.plan(prices, slots, new Request(Mode.ALL, 0, null, 0, BigDecimal.ZERO));
        assertTrue(none.isEmpty());
        assertTrue(none.limitedByBudget());
    }

    @Test
    void detachedPlanUsesListIndices() {
        List<Slot> stacks = List.of(new Slot(0, DIAMOND, 2), new Slot(40, COBBLE, 10), new Slot(50, ItemFacts.plain("dirt"), 3));
        Plan plan = SellPlanner.planDetached(prices, stacks, null);
        assertEquals(List.of(0, 40), plan.takes().stream().map(SellPlanner.Take::slot).toList());
        assertEquals(new BigDecimal("201.00"), plan.total());
    }

    @Test
    void detailSummarisesByMaterial() {
        List<Slot> slots = List.of(new Slot(0, DIAMOND, 2), new Slot(1, DIAMOND, 3), new Slot(2, COBBLE, 1));
        assertEquals("cobblestone x1, diamond x5", SellService.detail(SellPlanner.plan(prices, slots, req(Mode.ALL, 0))));
    }
}
