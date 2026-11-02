package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Computes exactly which slots and how many items a sale takes. Pure logic: the caller snapshots the
 * inventory, plans, and removes exactly {@link Plan#takes()} in the same tick on the player's thread,
 * then pays {@link Plan#total()}.
 *
 * <p>Only storage slots {@code 0..35} and the offhand ({@link #OFFHAND}) are ever considered. Armor slots
 * are rejected even if a caller passes them, so worn armor is never paid for.
 */
public final class SellPlanner {
    public static final int STORAGE_SIZE = 36;
    /** Pseudo slot index for the offhand. */
    public static final int OFFHAND = 40;

    public enum Mode { HAND, ALL, SAME, BLOCKS, MATERIAL }

    /** A non-empty slot of the player's inventory. */
    public record Slot(int index, ItemFacts facts, int amount) {}

    /**
     * @param heldSlot  main-hand slot (0-8)
     * @param material  material for {@link Mode#MATERIAL}
     * @param limit     maximum number of items to sell, 0 = no limit
     * @param budget    maximum total value (daily sell limit), {@code null} = unlimited
     */
    public record Request(Mode mode, int heldSlot, @Nullable String material, int limit, @Nullable BigDecimal budget) {}

    public record Take(int slot, int amount, BigDecimal unitPrice, BigDecimal value, ItemFacts facts) {}

    /**
     * @param limitedByBudget true when sellable items were left because of the budget
     */
    public record Plan(List<Take> takes, BigDecimal total, int items, boolean limitedByBudget) {
        public boolean isEmpty() {
            return takes.isEmpty();
        }
    }

    private SellPlanner() {}

    public static boolean allowedSlot(int index) {
        return (index >= 0 && index < STORAGE_SIZE) || index == OFFHAND;
    }

    public static Plan plan(PriceTable prices, List<Slot> slots, Request request) {
        List<Slot> usable = new ArrayList<>();
        Slot held = null;
        for (Slot slot : slots) {
            if (!allowedSlot(slot.index()) || slot.amount() <= 0) {
                continue;
            }
            usable.add(slot);
            if (slot.index() == request.heldSlot()) {
                held = slot;
            }
        }
        // Main hand first, then storage in slot order, offhand last.
        final int heldIndex = request.heldSlot();
        usable.sort(Comparator.comparingInt((Slot s) -> s.index() == heldIndex ? -1 : s.index()));
        final Slot heldSlot = held;
        return compute(prices, usable, slot -> selected(request, slot, heldSlot), request.limit(), request.budget());
    }

    /**
     * Plans the sale of detached stacks (e.g. the contents of the sell GUI, indexed by list position).
     * Everything sellable is taken, within the budget.
     */
    public static Plan planDetached(PriceTable prices, List<Slot> stacks, @Nullable BigDecimal budget) {
        List<Slot> usable = stacks.stream().filter(s -> s.amount() > 0).toList();
        return compute(prices, usable, slot -> true, 0, budget);
    }

    private static Plan compute(PriceTable prices, List<Slot> ordered, java.util.function.Predicate<Slot> selector,
            int limit, @Nullable BigDecimal budget) {
        List<Take> takes = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int items = 0;
        boolean limitedByBudget = false;
        int remainingLimit = limit > 0 ? limit : Integer.MAX_VALUE;

        for (Slot slot : ordered) {
            if (remainingLimit <= 0) {
                break;
            }
            if (!selector.test(slot)) {
                continue;
            }
            Optional<BigDecimal> unit = prices.unitPrice(slot.facts());
            if (unit.isEmpty()) {
                continue;
            }
            int amount = Math.min(slot.amount(), remainingLimit);
            if (budget != null) {
                BigDecimal left = budget.subtract(total);
                int affordable = left.signum() <= 0 ? 0
                        : left.divide(unit.get(), 0, RoundingMode.DOWN).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
                if (affordable < amount) {
                    limitedByBudget = true;
                    amount = affordable;
                }
            }
            if (amount <= 0) {
                continue;
            }
            BigDecimal value = unit.get().multiply(BigDecimal.valueOf(amount));
            takes.add(new Take(slot.index(), amount, unit.get(), value, slot.facts()));
            total = total.add(value);
            items += amount;
            remainingLimit -= amount;
        }
        return new Plan(List.copyOf(takes), total, items, limitedByBudget);
    }

    private static boolean selected(Request request, Slot slot, @Nullable Slot held) {
        return switch (request.mode()) {
            case HAND -> slot.index() == request.heldSlot();
            case ALL -> true;
            case SAME -> held != null && slot.facts().equals(held.facts());
            case BLOCKS -> slot.facts().block();
            case MATERIAL -> request.material() != null && slot.facts().material().equals(request.material());
        };
    }
}
