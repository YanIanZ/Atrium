package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

/**
 * Executes sales. Order is always: plan and remove the exact stacks on the player's thread in one go, then
 * deposit exactly the value of what was removed; if the deposit fails the removed items are handed back.
 * One sale per player at a time.
 */
final class SellService {
    private final ModuleContext ctx;
    private final Supplier<PriceTable> prices;
    private final Supplier<ItemFactsReader> reader;
    private final Supplier<WorthConfig> config;
    private final EconomyService economy;
    private final WorthStore store;
    private final ItemReturns returns;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    SellService(ModuleContext ctx, Supplier<PriceTable> prices, Supplier<ItemFactsReader> reader,
            Supplier<WorthConfig> config, EconomyService economy, WorthStore store, ItemReturns returns) {
        this.ctx = ctx;
        this.prices = prices;
        this.reader = reader;
        this.config = config;
        this.economy = economy;
        this.store = store;
        this.returns = returns;
    }

    String format(BigDecimal amount) {
        return economy.format(amount);
    }

    boolean creativeBlocked(Player player) {
        GameMode mode = player.getGameMode();
        return config.get().denyInCreative && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR);
    }

    /** Snapshot of storage slots 0-35 plus (optionally) the offhand. Armor is never included. */
    List<SellPlanner.Slot> slots(Player player, boolean includeOffhand) {
        PlayerInventory inventory = player.getInventory();
        ItemFactsReader facts = reader.get();
        List<SellPlanner.Slot> slots = new ArrayList<>();
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < Math.min(storage.length, SellPlanner.STORAGE_SIZE); i++) {
            ItemStack item = storage[i];
            if (item != null && !item.isEmpty()) {
                slots.add(new SellPlanner.Slot(i, facts.read(item), item.getAmount()));
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (includeOffhand && !offhand.isEmpty()) {
            slots.add(new SellPlanner.Slot(SellPlanner.OFFHAND, facts.read(offhand), offhand.getAmount()));
        }
        return slots;
    }

    /** Value preview without removing anything. Player thread. */
    SellPlanner.Plan preview(Player player, SellPlanner.Mode mode, @Nullable String material) {
        boolean offhand = mode != SellPlanner.Mode.HAND && config.get().includeOffhand;
        return SellPlanner.plan(prices.get(), slots(player, offhand),
                new SellPlanner.Request(mode, player.getInventory().getHeldItemSlot(), material, 0, null));
    }

    /** Daily budget left, or {@code null} for unlimited. */
    private CompletableFuture<@Nullable BigDecimal> budget(Player player) {
        long base = config.get().dailyLimit;
        if (base <= 0) {
            return CompletableFuture.completedFuture(null);
        }
        int limit = Permissions.limit(player, "atrium.sell.limit", (int) Math.min(Integer.MAX_VALUE, base));
        if (limit == Integer.MAX_VALUE) {
            return CompletableFuture.completedFuture(null);
        }
        return store.soldOn(player.getUniqueId(), LocalDate.now())
                .thenApply(sold -> BigDecimal.valueOf(limit).subtract(sold).max(BigDecimal.ZERO));
    }

    /** Sells from the player's inventory. Call on the player's thread. */
    void sell(Player player, SellPlanner.Mode mode, @Nullable String material, int limit) {
        if (creativeBlocked(player)) {
            send(player, "creative");
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            send(player, "busy");
            return;
        }
        budget(player).whenComplete((budget, failure) -> {
            if (failure != null) {
                ctx.logger().warn("Cannot read the daily sell total of {}", uuid, failure);
                inFlight.remove(uuid);
                message(player, "failed");
                return;
            }
            ctx.scheduler().entity(player, () -> {
                try {
                    sellNow(player, mode, material, limit, budget);
                } catch (RuntimeException e) {
                    inFlight.remove(uuid);
                    ctx.logger().error("Selling for {} failed before payment", player.getName(), e);
                    send(player, "failed");
                }
            }, () -> inFlight.remove(uuid));
        });
    }

    private void sellNow(Player player, SellPlanner.Mode mode, @Nullable String material, int limit,
            @Nullable BigDecimal budget) {
        UUID uuid = player.getUniqueId();
        if (!player.isOnline() || player.isDead() || creativeBlocked(player)) {
            inFlight.remove(uuid);
            return;
        }
        PlayerInventory inventory = player.getInventory();
        boolean offhand = mode != SellPlanner.Mode.HAND && config.get().includeOffhand;
        // Snapshot, plan and remove in the same task: nothing can change the inventory in between.
        SellPlanner.Plan plan = SellPlanner.plan(prices.get(), slots(player, offhand),
                new SellPlanner.Request(mode, inventory.getHeldItemSlot(), material, limit, budget));
        if (plan.isEmpty()) {
            inFlight.remove(uuid);
            send(player, plan.limitedByBudget() || (budget != null && budget.signum() == 0) ? "limit-reached" : "nothing");
            return;
        }
        List<ItemStack> removed = new ArrayList<>();
        List<Undo> undo = new ArrayList<>();
        ItemFactsReader facts = reader.get();
        for (SellPlanner.Take take : plan.takes()) {
            ItemStack live = take.slot() == SellPlanner.OFFHAND ? inventory.getItemInOffHand() : inventory.getItem(take.slot());
            if (live == null || live.isEmpty() || live.getAmount() < take.amount() || !facts.read(live).equals(take.facts())) {
                // Cannot happen within one task; refuse rather than pay for something not removed.
                restore(inventory, undo);
                inFlight.remove(uuid);
                ctx.logger().warn("Inventory of {} changed while selling; sale aborted", player.getName());
                send(player, "failed");
                return;
            }
            ItemStack original = live.clone();
            int left = live.getAmount() - take.amount();
            ItemStack rest = left > 0 ? live.asQuantity(left) : null;
            if (take.slot() == SellPlanner.OFFHAND) {
                inventory.setItemInOffHand(rest);
            } else {
                inventory.setItem(take.slot(), rest);
            }
            undo.add(new Undo(take.slot(), original));
            removed.add(original.asQuantity(take.amount()));
        }
        pay(player, plan, removed, mode.name().toLowerCase(Locale.ROOT), () -> inFlight.remove(uuid));
    }

    private record Undo(int slot, ItemStack original) {}

    private static void restore(PlayerInventory inventory, List<Undo> undo) {
        for (Undo entry : undo) {
            if (entry.slot() == SellPlanner.OFFHAND) inventory.setItemInOffHand(entry.original());
            else inventory.setItem(entry.slot(), entry.original());
        }
    }

    /**
     * Sells detached stacks (sell GUI contents, already taken out of the GUI). Unsold stacks and, on payment
     * failure, sold stacks are handed back. Call on the player's thread.
     */
    void sellDetached(Player player, List<ItemStack> stacks) {
        List<ItemStack> items = stacks.stream().filter(i -> i != null && !i.isEmpty()).map(ItemStack::clone).toList();
        if (items.isEmpty()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (creativeBlocked(player) || !inFlight.add(uuid)) {
            returns.giveNow(player, items);
            send(player, creativeBlocked(player) ? "creative" : "busy");
            return;
        }
        budget(player).whenComplete((budget, failure) -> {
            if (failure != null) {
                ctx.logger().warn("Cannot read the daily sell total of {}", uuid, failure);
                inFlight.remove(uuid);
                returns.give(player, items);
                message(player, "failed");
                return;
            }
            ctx.scheduler().entity(player, () -> {
                try {
                    sellDetachedNow(player, items, budget);
                } catch (RuntimeException e) {
                    inFlight.remove(uuid);
                    ctx.logger().error("Selling GUI items for {} failed before payment", player.getName(), e);
                    returns.giveNow(player, items);
                    send(player, "failed");
                }
            }, () -> {
                inFlight.remove(uuid);
                returns.persist(uuid, items);
            });
        });
    }

    private void sellDetachedNow(Player player, List<ItemStack> items, @Nullable BigDecimal budget) {
        UUID uuid = player.getUniqueId();
        ItemFactsReader facts = reader.get();
        List<SellPlanner.Slot> slots = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            slots.add(new SellPlanner.Slot(i, facts.read(items.get(i)), items.get(i).getAmount()));
        }
        SellPlanner.Plan plan = SellPlanner.planDetached(prices.get(), slots, budget);
        int[] taken = new int[items.size()];
        List<ItemStack> sold = new ArrayList<>();
        for (SellPlanner.Take take : plan.takes()) {
            taken[take.slot()] += take.amount();
            sold.add(items.get(take.slot()).asQuantity(take.amount()));
        }
        List<ItemStack> unsold = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            int left = items.get(i).getAmount() - taken[i];
            if (left > 0) unsold.add(items.get(i).asQuantity(left));
        }
        returns.giveNow(player, unsold);
        if (!unsold.isEmpty()) {
            send(player, plan.limitedByBudget() ? "limit-reached" : "gui-returned",
                    Msg.number("count", unsold.stream().mapToInt(ItemStack::getAmount).sum()));
        }
        if (plan.isEmpty()) {
            inFlight.remove(uuid);
            return;
        }
        pay(player, plan, sold, "gui", () -> inFlight.remove(uuid));
    }

    /** Deposits exactly the plan total; on failure gives {@code removed} back. */
    private void pay(Player player, SellPlanner.Plan plan, List<ItemStack> removed, String mode, Runnable done) {
        UUID uuid = player.getUniqueId();
        String detail = detail(plan);
        CompletableFuture<TransactionResult> deposit;
        try {
            deposit = economy.deposit(uuid, plan.total(), "sell:" + mode);
        } catch (RuntimeException e) {
            deposit = CompletableFuture.failedFuture(e);
        }
        deposit.whenComplete((result, failure) -> {
            if (failure != null || result == null || !result.success()) {
                if (failure != null) {
                    ctx.logger().warn("Sell deposit of {} for {} failed; returning items", plan.total(), uuid, failure);
                }
                returns.give(player, removed);
                done.run();
                message(player, result != null && result.status() == TransactionResult.Status.LIMIT_REACHED
                        ? "deposit-limit" : "deposit-failed");
                return;
            }
            Consumer<Void> finish = v -> {
                done.run();
                message(player, plan.limitedByBudget() ? "sold-limited" : "sold",
                        Msg.number("items", plan.items()),
                        Msg.unparsed("amount", economy.format(result.amount())),
                        Msg.unparsed("balance", economy.format(result.balance())));
            };
            if (config.get().logSales || config.get().dailyLimit > 0) {
                store.logSale(uuid, LocalDate.now(), System.currentTimeMillis(), mode, plan.items(), result.amount(), detail)
                        .whenComplete((v, logFailure) -> {
                            if (logFailure != null) {
                                ctx.logger().warn("Could not log sale of {} by {} ({})", result.amount(), uuid, detail, logFailure);
                            }
                            finish.accept(null);
                        });
            } else {
                finish.accept(null);
            }
        });
    }

    static String detail(SellPlanner.Plan plan) {
        Map<String, Integer> counts = new TreeMap<>();
        for (SellPlanner.Take take : plan.takes()) {
            counts.merge(take.facts().material(), take.amount(), Integer::sum);
        }
        StringBuilder out = new StringBuilder();
        counts.forEach((material, amount) -> {
            if (!out.isEmpty()) out.append(", ");
            out.append(material).append(" x").append(amount);
        });
        return out.toString();
    }

    private void send(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        ctx.messages().send(player, "worth." + key, tags);
    }

    /** From any thread. */
    private void message(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        ctx.scheduler().entity(player, () -> send(player, key, tags), null);
    }
}
