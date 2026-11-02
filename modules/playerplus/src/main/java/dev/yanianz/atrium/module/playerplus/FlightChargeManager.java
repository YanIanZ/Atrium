package dev.yanianz.atrium.module.playerplus;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/**
 * Charged flight: a player claims flight and pays in stored seconds while actually flying.
 *
 * <p>A purchase always moves money in exactly one {@link EconomyService} transaction and only adds charge
 * after that transaction reported success. A failed or rejected withdrawal adds nothing. Purchases are
 * self-only; staff change charge through the admin sub commands instead of spending somebody else's money.
 */
final class FlightChargeManager implements Listener {
    static final String OWNER = "playerplus:charge";

    private final ModuleContext ctx;
    private final ConfigHandle<PlayerPlusConfig> config;
    private final PlayerPlusStore store;
    private final @Nullable FlightService flight;
    private final Set<UUID> purchases = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastAutoAttempt = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> timers = new ConcurrentHashMap<>();

    FlightChargeManager(ModuleContext ctx, ConfigHandle<PlayerPlusConfig> config, PlayerPlusStore store,
            @Nullable FlightService flight) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.flight = flight;
    }

    boolean enabled() {
        return config.get().flightCharge.enabled;
    }

    long maxSeconds() {
        return Math.max(0, config.get().flightCharge.maxSeconds);
    }

    long consumePerSecond() {
        return Math.max(0, config.get().flightCharge.consumePerSecond);
    }

    boolean active(Player player) {
        return flight != null && flight.claims(player).contains(OWNER);
    }

    long charge(AtriumUser user) {
        return Math.min(Math.max(0, user.get(store.component).flightChargeSeconds()), maxSeconds());
    }

    /** Player's thread: switch charged flight on or off. */
    void toggle(Player player) {
        if (!enabled()) {
            tell(player, "charge-disabled");
            return;
        }
        if (flight == null) {
            tell(player, "no-flight-service");
            return;
        }
        if (active(player)) {
            stop(player, false);
            tell(player, "charge-flight-disabled");
            return;
        }
        AtriumUser user = ctx.users().user(player);
        long current = charge(user);
        if (current <= 0) {
            tell(player, "charge-empty");
            maybeAutoRecharge(player);
            return;
        }
        flight.claim(player, OWNER);
        ensureTimer(player);
        tell(player, "charge-flight-enabled", Msg.number("charge", current));
    }

    /** Player's thread: starts a purchase, or explains why it cannot happen. */
    void purchase(Player player, long requested, PlayerPlusConfig.Currency currency, boolean automatic) {
        if (!enabled() || flight == null) {
            if (!automatic) {
                tell(player, enabled() ? "no-flight-service" : "charge-disabled");
            }
            return;
        }
        UUID uuid = player.getUniqueId();
        AtriumUser user = ctx.users().user(player);
        long current = charge(user);
        if (FlightChargeMath.space(current, maxSeconds()) <= 0) {
            if (!automatic) {
                tell(player, "charge-full", Msg.number("max", maxSeconds()));
            }
            return;
        }
        PlayerPlusConfig.FlightCharge cfg = config.get().flightCharge;
        double price = currency == PlayerPlusConfig.Currency.EXP ? cfg.expPerSecond : cfg.moneyPerSecond;
        if (!Double.isFinite(price) || price <= 0) {
            if (!automatic) {
                tell(player, "purchase-failed");
            }
            return;
        }
        if (!purchases.add(uuid)) {
            if (!automatic) {
                tell(player, "purchase-busy");
            }
            return;
        }
        if (currency == PlayerPlusConfig.Currency.EXP) {
            purchaseWithExp(player, user, requested, automatic);
        } else {
            purchaseWithMoney(player, user, requested, automatic);
        }
    }

    private void purchaseWithMoney(Player player, AtriumUser user, long requested, boolean automatic) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        UUID uuid = player.getUniqueId();
        if (economy == null) {
            purchases.remove(uuid);
            if (!automatic) {
                tell(player, "no-economy");
            }
            return;
        }
        BigDecimal unitCost = BigDecimal.valueOf(config.get().flightCharge.moneyPerSecond);
        if (unitCost.signum() <= 0) {
            purchases.remove(uuid);
            if (!automatic) {
                tell(player, "purchase-failed");
            }
            return;
        }
        CompletableFuture<BigDecimal> balance;
        try {
            balance = economy.balance(uuid);
        } catch (RuntimeException e) {
            purchases.remove(uuid);
            ctx.logger().warn("Reading the balance of {} for flight charge failed", player.getName(), e);
            tell(player, "purchase-failed");
            return;
        }
        balance.whenComplete((available, error) -> {
            if (error != null) {
                purchases.remove(uuid);
                ctx.logger().warn("Reading the balance of {} for flight charge failed", player.getName(), error);
                tell(player, "purchase-failed");
                return;
            }
            long units = FlightChargeMath.affordable(available, unitCost, requested, charge(user), maxSeconds());
            if (units <= 0) {
                purchases.remove(uuid);
                if (FlightChargeMath.space(charge(user), maxSeconds()) <= 0) {
                    if (!automatic) {
                        tell(player, "charge-full", Msg.number("max", maxSeconds()));
                    }
                } else {
                    tell(player, automatic ? "charge-auto-failed" : "insufficient-funds",
                            Msg.unparsed("cost", economy.format(unitCost.multiply(BigDecimal.valueOf(
                                    Math.max(1, requested))))));
                }
                return;
            }
            BigDecimal cost = FlightChargeMath.cost(units, unitCost);
            CompletableFuture<TransactionResult> withdraw;
            try {
                withdraw = economy.withdraw(uuid, cost, "playerplus:flightcharge");
            } catch (RuntimeException e) {
                purchases.remove(uuid);
                ctx.logger().warn("Withdrawing {} for flight charge failed", cost, e);
                tell(player, "purchase-failed");
                return;
            }
            withdraw.whenComplete((result, failure) -> {
                if (failure != null || result == null || !result.success()) {
                    purchases.remove(uuid);
                    if (failure != null) {
                        ctx.logger().warn("Withdrawing {} for flight charge failed", cost, failure);
                    }
                    boolean poor = result != null && result.status() == TransactionResult.Status.INSUFFICIENT_FUNDS;
                    tell(player, poor ? (automatic ? "charge-auto-failed" : "insufficient-funds") : "purchase-failed",
                            Msg.unparsed("cost", economy.format(cost)));
                    return;
                }
                // Money has left the account exactly once. Add the seconds now (relative, atomic), refund the
                // part that no longer fits, and refund everything if storing fails. The live user object is
                // looked up again: the player may have quit while the withdrawal ran.
                credit(player, economy, uuid, units, unitCost, cost, automatic);
            });
        });
    }

    private void credit(Player player, EconomyService economy, UUID uuid, long units, BigDecimal unitCost,
            BigDecimal cost, boolean automatic) {
        CompletableFuture<java.util.Optional<AtriumUser>> holder;
        try {
            holder = ctx.users().load(uuid);
        } catch (RuntimeException e) {
            holder = CompletableFuture.failedFuture(e);
        }
        holder.whenComplete((found, loadError) -> {
            if (loadError != null || found == null || found.isEmpty()) {
                purchases.remove(uuid);
                ctx.logger().error("Adding flight charge for {} failed (user not available); refunding {}",
                        player.getName(), cost, loadError);
                refund(economy, uuid, cost);
                tell(player, "purchase-failed");
                return;
            }
            AtriumUser user = found.get();
            FlightChargeMath.Credit credit;
            try {
                credit = addCharge(user, units);
            } catch (RuntimeException e) {
                purchases.remove(uuid);
                ctx.logger().error("Adding flight charge for {} failed; refunding {}", player.getName(), cost, e);
                refund(economy, uuid, cost);
                tell(player, "purchase-failed");
                return;
            }
            long overflow = FlightChargeMath.overflow(units, credit.added());
            BigDecimal overflowRefund = FlightChargeMath.cost(overflow, unitCost);
            BigDecimal kept = FlightChargeMath.cost(credit.added(), unitCost);
            if (overflow > 0) {
                ctx.logger().info("Flight charge of {} filled up during a purchase; refunding {} for {} second(s)",
                        player.getName(), overflowRefund, overflow);
                refund(economy, uuid, overflowRefund);
            }
            if (credit.added() <= 0) {
                purchases.remove(uuid);
                tell(player, "charge-overflow-refunded", Msg.number("amount", overflow),
                        Msg.unparsed("cost", economy.format(overflowRefund)));
                return;
            }
            ctx.users().saveNow(uuid).whenComplete((ignored, saveError) -> {
                purchases.remove(uuid);
                if (saveError != null) {
                    ctx.logger().warn("Saving the flight charge of {} failed; refunding {}",
                            player.getName(), kept, saveError);
                    try {
                        subtractCharge(user, credit.added());
                    } catch (RuntimeException e) {
                        ctx.logger().error("Removing the unsaved flight charge of {} failed", player.getName(), e);
                    }
                    refund(economy, uuid, kept);
                    tell(player, automatic ? "charge-auto-failed" : "purchase-failed",
                            Msg.unparsed("cost", economy.format(cost)));
                    return;
                }
                if (automatic) {
                    actionBar(player, "charge-auto-bought",
                            Msg.number("amount", credit.added()), Msg.unparsed("cost", economy.format(kept)));
                } else {
                    tell(player, "charge-bought",
                            Msg.number("amount", credit.added()), Msg.unparsed("cost", economy.format(kept)));
                }
                if (overflow > 0) {
                    tell(player, "charge-overflow-refunded", Msg.number("amount", overflow),
                            Msg.unparsed("cost", economy.format(overflowRefund)));
                }
            });
        });
    }

    /** Adds purchased seconds in one atomic, relative update; returns what was actually added. */
    private FlightChargeMath.Credit addCharge(AtriumUser user, long units) {
        long max = maxSeconds();
        FlightChargeMath.Credit[] result = new FlightChargeMath.Credit[1];
        user.update(store.component, state -> {
            FlightChargeMath.Credit credit = FlightChargeMath.credit(state.flightChargeSeconds(), units, max);
            result[0] = credit;
            return credit.stored() == state.flightChargeSeconds() ? state : state.withFlightCharge(credit.stored());
        });
        return result[0];
    }

    private void purchaseWithExp(Player player, AtriumUser user, long requested, boolean automatic) {
        double expPerSecond = config.get().flightCharge.expPerSecond;
        UUID uuid = player.getUniqueId();
        ctx.scheduler().supplyOnEntity(player, () -> {
            if (!player.isOnline()) {
                return new ExpPurchase(ExpStatus.OFFLINE, 0, 0);
            }
            long current = charge(user);
            long units = FlightChargeMath.expAffordable(player.calculateTotalExperiencePoints(), expPerSecond,
                    requested, current, maxSeconds());
            if (units <= 0) {
                return new ExpPurchase(FlightChargeMath.space(current, maxSeconds()) <= 0
                        ? ExpStatus.FULL : ExpStatus.POOR, 0, 0);
            }
            long cost = FlightChargeMath.expCost(units, expPerSecond);
            if (cost <= 0 || cost > player.calculateTotalExperiencePoints()) {
                return new ExpPurchase(ExpStatus.POOR, 0, 0);
            }
            player.giveExp((int) -cost);
            FlightChargeMath.Credit credit;
            try {
                credit = addCharge(user, units);
            } catch (RuntimeException e) {
                player.giveExp((int) cost);
                throw e;
            }
            long refund = FlightChargeMath.expRefund(cost, credit.added(), expPerSecond);
            if (refund > 0) {
                // Charge filled up meanwhile: give back the points of the seconds that did not fit.
                player.giveExp((int) refund);
                ctx.messages().send(player, "playerplus.charge-overflow-refunded-exp",
                        Msg.number("amount", FlightChargeMath.overflow(units, credit.added())),
                        Msg.number("cost", refund));
            }
            if (credit.added() <= 0) {
                return new ExpPurchase(ExpStatus.FULL, 0, 0);
            }
            return new ExpPurchase(ExpStatus.OK, credit.added(), cost - refund);
        }).whenComplete((result, error) -> {
            if (error != null) {
                purchases.remove(uuid);
                if (!(error instanceof Scheduler.EntityRetiredException)) {
                    ctx.logger().warn("Buying flight charge with experience for {} failed", player.getName(), error);
                }
                tell(player, "purchase-failed");
                return;
            }
            if (result == null) {
                purchases.remove(uuid);
                tell(player, "purchase-failed");
                return;
            }
            switch (result.status()) {
                case OK -> ctx.users().saveNow(uuid).whenComplete((ignored, saveError) -> {
                    purchases.remove(uuid);
                    if (saveError != null) {
                        ctx.logger().warn("Saving the flight charge of {} failed; refunding {} experience",
                                player.getName(), result.cost(), saveError);
                        refundExperience(player, user, result.units(), result.cost());
                        tell(player, automatic ? "charge-auto-failed" : "purchase-failed",
                                Msg.number("cost", result.cost()));
                        return;
                    }
                    if (automatic) {
                        actionBar(player, "charge-auto-bought", Msg.number("amount", result.units()),
                                Msg.number("cost", result.cost()));
                    } else {
                        tell(player, "charge-bought-exp", Msg.number("amount", result.units()),
                                Msg.number("cost", result.cost()));
                    }
                });
                case FULL -> {
                    purchases.remove(uuid);
                    if (!automatic) {
                        tell(player, "charge-full", Msg.number("max", maxSeconds()));
                    }
                }
                case POOR -> {
                    purchases.remove(uuid);
                    tell(player, automatic ? "charge-auto-failed" : "insufficient-exp",
                            Msg.number("cost", FlightChargeMath.expCost(requested, expPerSecond)));
                }
                case OFFLINE -> {
                    purchases.remove(uuid);
                    ctx.logger().debug("Flight charge purchase for {} skipped: player left", player.getName());
                }
            }
        });
    }

    /** Retries an automatic top-up when the player is below the threshold and enabled it. */
    void maybeAutoRecharge(Player player) {
        PlayerPlusConfig.FlightCharge cfg = config.get().flightCharge;
        if (!cfg.enabled || cfg.autoRechargeBelowSeconds <= 0) {
            return;
        }
        AtriumUser user = ctx.users().user(player);
        PlayerPlusState state = user.get(store.component);
        if (!state.autoRecharge() || state.flightChargeSeconds() > cfg.autoRechargeBelowSeconds) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastAutoAttempt.get(player.getUniqueId());
        if (previous != null && now - previous < Math.max(1000, cfg.autoRechargeCooldownMillis)) {
            return;
        }
        lastAutoAttempt.put(player.getUniqueId(), now);
        purchase(player, Math.max(1, cfg.defaultPurchaseSeconds), cfg.currency, true);
    }

    /** Player's thread: one second of the charged-flight timer. */
    private void tick(Player player) {
        UUID uuid = player.getUniqueId();
        if (!active(player)) {
            cancelTimer(uuid);
            return;
        }
        AtriumUser user = ctx.users().user(player);
        long current = charge(user);
        if (player.isFlying() && consumePerSecond() > 0) {
            long consume = consumePerSecond();
            long max = maxSeconds();
            // Relative and atomic: a purchase credited on another thread in the meantime is kept.
            PlayerPlusState after = user.update(store.component, state -> state.withFlightCharge(
                    FlightChargeMath.consume(state.flightChargeSeconds(), consume, max)));
            long next = Math.min(Math.max(0, after.flightChargeSeconds()), max);
            current = next;
            if (next <= 0) {
                stop(player, true);
                tell(player, "charge-empty-flight");
                return;
            }
        }
        if (current <= 0) {
            // Charge was emptied by an admin or while not flying: never leave flight claimable for free.
            stop(player, false);
            tell(player, "charge-empty-flight");
            return;
        }
        if (current <= config.get().flightCharge.autoRechargeBelowSeconds) {
            maybeAutoRecharge(player);
        }
    }

    private void ensureTimer(Player player) {
        UUID uuid = player.getUniqueId();
        TaskHandle existing = timers.get(uuid);
        if (existing != null && !existing.isDone()) {
            return;
        }
        TaskHandle task = ctx.scheduler().entityTimer(player, () -> tick(player), () -> cancelTimer(uuid), 20, 20);
        timers.put(uuid, ctx.track(task));
    }

    private void cancelTimer(UUID uuid) {
        TaskHandle task = timers.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    /** Player's thread: drops the charged-flight claim (the stored charge stays). */
    void stop(Player player, boolean grace) {
        cancelTimer(player.getUniqueId());
        if (flight != null) {
            flight.release(player, OWNER);
        }
        if (grace) {
            SafeLanding.apply(player, config.get().tfly.graceSeconds);
        }
    }

    /**
     * Admin change of stored seconds ({@code add|take|set}), applied relative to the stored value inside one atomic
     * update so a concurrent purchase or flight tick is never overwritten. Player's thread; saves before reporting
     * success.
     */
    void changeCharge(Player target, String operation, long amount, java.util.function.Consumer<Long> applied,
            java.util.function.Consumer<Throwable> failed) {
        AtriumUser user = ctx.users().user(target);
        long max = maxSeconds();
        PlayerPlusState after = user.update(store.component, state -> state.withFlightCharge(
                FlightChargeMath.adminApply(operation, state.flightChargeSeconds(), amount, max)));
        long value = Math.min(Math.max(0, after.flightChargeSeconds()), max);
        ctx.users().saveNow(target.getUniqueId()).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().warn("Saving flight charge of {} failed", target.getName(), error);
                tell(target, "save-failed");
                failed.accept(error);
                return;
            }
            applied.accept(value);
        });
    }

    void setAutoRecharge(Player player, boolean value) {
        ctx.users().user(player).update(store.component, state -> state.withAutoRecharge(value));
    }

    /** Price of one second, formatted for feedback. Never contains untrusted text. */
    String priceLabel() {
        PlayerPlusConfig.FlightCharge cfg = config.get().flightCharge;
        if (cfg.currency == PlayerPlusConfig.Currency.EXP) {
            return cfg.expPerSecond + " exp/s";
        }
        return cfg.moneyPerSecond + " money/s";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cancelTimer(uuid);
        lastAutoAttempt.remove(uuid);
        // The purchase guard stays while a withdrawal is pending: it is released by that purchase itself, so a
        // quick rejoin cannot start a second purchase that is computed from the same free space.
    }

    void disable() {
        for (TaskHandle task : timers.values()) {
            task.cancel();
        }
        timers.clear();
        purchases.clear();
        lastAutoAttempt.clear();
    }

    private void subtractCharge(AtriumUser user, long units) {
        user.update(store.component,
                state -> state.withFlightCharge(Math.max(0, state.flightChargeSeconds() - Math.max(0, units))));
    }

    private void refundExperience(Player player, AtriumUser user, long units, long points) {
        ctx.scheduler().supplyOnEntity(player, () -> {
            if (player.isOnline() && points > 0 && points <= Integer.MAX_VALUE) {
                player.giveExp((int) points);
            } else if (!player.isOnline()) {
                ctx.logger().error("Cannot refund {} experience to {} after a flight-charge save failure: "
                        + "the player is offline", points, player.getName());
            }
            subtractCharge(user, units);
            return null;
        }).exceptionally(error -> {
            if (!(error instanceof Scheduler.EntityRetiredException)) {
                ctx.logger().error("Refunding flight charge to {} failed", player.getName(), error);
            }
            return null;
        });
    }

    private void refund(EconomyService economy, UUID uuid, BigDecimal amount) {
        try {
            economy.deposit(uuid, amount, "playerplus:flightcharge-refund").whenComplete((result, error) -> {
                if (error != null || result == null || !result.success()) {
                    ctx.logger().error("Refunding {} to {} after a flight-charge failure failed", amount, uuid,
                            error);
                }
            });
        } catch (RuntimeException e) {
            ctx.logger().error("Refunding {} to {} after a flight-charge failure failed", amount, uuid, e);
        }
    }

    private void tell(Player player, String key, TagResolver... tags) {
        String full = "playerplus." + key;
        ctx.scheduler().supplyOnEntity(player, () -> {
            ctx.messages().send(player, full, tags);
            return null;
        }).exceptionally(error -> {
            if (!(error instanceof Scheduler.EntityRetiredException)) {
                ctx.logger().warn("Sending a flight-charge message to {} failed", player.getName(), error);
            }
            return null;
        });
    }

    private void actionBar(Player player, String key, TagResolver... tags) {
        String full = "playerplus." + key;
        ctx.scheduler().supplyOnEntity(player, () -> {
            ctx.messages().actionBar(player, full, tags);
            return null;
        }).exceptionally(error -> {
            if (!(error instanceof Scheduler.EntityRetiredException)) {
                ctx.logger().warn("Sending a flight-charge action bar to {} failed", player.getName(), error);
            }
            return null;
        });
    }

    private enum ExpStatus { OK, POOR, FULL, OFFLINE }

    private record ExpPurchase(ExpStatus status, long units, long cost) {}
}
