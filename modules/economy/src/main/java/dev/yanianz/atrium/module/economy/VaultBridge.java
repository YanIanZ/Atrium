package dev.yanianz.atrium.module.economy;

import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

/** Loaded only when Vault is present. The synchronous compatibility boundary is intentionally isolated. */
@SuppressWarnings("deprecation")
final class VaultBridge extends AbstractEconomy implements AutoCloseable {
    private final ModuleContext ctx;
    private final EconomyStore store;
    private final int timeout;
    private volatile boolean enabled = true;
    private VaultBridge(ModuleContext ctx, EconomyStore store, EconomyConfig config) {
        this.ctx = ctx; this.store = store; timeout = config.vaultTimeoutMillis;
    }
    static VaultBridge register(ModuleContext ctx, EconomyStore store, EconomyConfig config) {
        VaultBridge provider = new VaultBridge(ctx, store, config);
        ctx.plugin().getServer().getServicesManager().register(Economy.class, provider, ctx.plugin(), ServicePriority.Normal);
        ctx.logger().info("Vault economy registered; synchronous writes may wait up to {} ms", config.vaultTimeoutMillis);
        return provider;
    }
    @Override public void close() {
        enabled = false;
        ctx.plugin().getServer().getServicesManager().unregister(Economy.class, this);
    }
    private static final long WAIT_WARNING_INTERVAL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
    private final java.util.concurrent.atomic.AtomicLong lastWaitWarning = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong suppressedWaits = new java.util.concurrent.atomic.AtomicLong();

    private <T> T await(CompletableFuture<T> future) {
        if (!enabled) throw new IllegalStateException("Economy disabled");
        if (!future.isDone()) {
            // Rate-limited (once a minute, with a count) so a bulk caller such as /vault-convert cannot flood the log.
            long now = System.nanoTime();
            long last = lastWaitWarning.get();
            long waits = suppressedWaits.incrementAndGet();
            if ((last == 0L || now - last >= WAIT_WARNING_INTERVAL_NANOS) && lastWaitWarning.compareAndSet(last, now)) {
                suppressedWaits.addAndGet(-waits);
                ctx.logger().warn("{} synchronous Vault wait(s), latest on thread '{}' (limit {} ms). Tick-thread callers can "
                        + "stall; prefer EconomyService futures.", waits, Thread.currentThread().getName(), timeout);
            }
        }
        try { return future.get(timeout, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ctx.logger().warn("Vault operation interrupted; write outcome may be unknown", e);
            throw new IllegalStateException("Interrupted; outcome unknown", e);
        } catch (ExecutionException | TimeoutException e) {
            ctx.logger().warn("Vault operation did not complete synchronously; do not blindly retry a timed-out write", e);
            throw new IllegalStateException("Storage error or timeout; outcome unknown", e);
        }
    }
    private <T> CompletableFuture<T> byName(String name, Function<UUID, CompletableFuture<T>> work) {
        return ctx.users().loadByName(name).thenCompose(user -> user.isPresent() ? work.apply(user.get().uuid())
                : CompletableFuture.failedFuture(new IllegalArgumentException("Unknown Atrium player")));
    }
    private double balance(UUID id) {
        BigDecimal cached = store.cachedBalance(id);
        return (cached != null ? cached : await(store.balance(id))).doubleValue();
    }
    private EconomyResponse invalid(String error) { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, error); }
    private EconomyResponse response(TransactionResult result) {
        return new EconomyResponse(result.success() ? result.amount().doubleValue() : 0, result.balance().doubleValue(),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.success() ? null : result.status().name());
    }
    private EconomyResponse write(UUID id, double value, boolean deposit) {
        if (!Double.isFinite(value) || value < 0) return invalid("Invalid amount");
        try { return response(await(deposit ? store.deposit(id, BigDecimal.valueOf(value), "vault:deposit")
                : store.withdraw(id, BigDecimal.valueOf(value), "vault:withdraw"))); }
        catch (IllegalStateException e) { return invalid(e.getMessage()); }
    }
    private EconomyResponse write(String name, double value, boolean deposit) {
        if (!Double.isFinite(value) || value < 0) return invalid("Invalid amount");
        try { return response(await(byName(name, id -> deposit ? store.deposit(id, BigDecimal.valueOf(value), "vault:deposit")
                : store.withdraw(id, BigDecimal.valueOf(value), "vault:withdraw")))); }
        catch (IllegalStateException e) { return invalid(e.getMessage()); }
    }
    @Override public boolean isEnabled() { return enabled; }
    @Override public String getName() { return "Atrium"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return store.money().scale; }
    @Override public String format(double amount) {
        if (!Double.isFinite(amount)) throw new IllegalArgumentException("Non-finite amount");
        return store.format(BigDecimal.valueOf(amount));
    }
    @Override public String currencyNamePlural() { return store.currencyNamePlural(); }
    @Override public String currencyNameSingular() { return store.currencyNameSingular(); }
    @Override public boolean hasAccount(String name) {
        return await(ctx.users().loadByName(name).thenCompose(user -> user.isPresent()
                ? store.exists(user.get().uuid()) : CompletableFuture.completedFuture(false)));
    }
    @Override public boolean hasAccount(OfflinePlayer player) { return await(store.exists(player.getUniqueId())); }
    private BigDecimal namedBalance(String name) {
        return await(byName(name, id -> {
            BigDecimal cached = store.cachedBalance(id);
            return cached == null ? store.balance(id) : CompletableFuture.completedFuture(cached);
        }));
    }
    @Override public double getBalance(String name) { return namedBalance(name).doubleValue(); }
    @Override public double getBalance(OfflinePlayer player) { return balance(player.getUniqueId()); }
    @Override public boolean has(String name, double amount) {
        return Double.isFinite(amount) && amount >= 0 && namedBalance(name).compareTo(BigDecimal.valueOf(amount)) >= 0;
    }
    @Override public boolean has(OfflinePlayer player, double amount) {
        if (!Double.isFinite(amount) || amount < 0) return false;
        BigDecimal cached = store.cachedBalance(player.getUniqueId());
        BigDecimal value = cached != null ? cached : await(store.balance(player.getUniqueId()));
        return value.compareTo(BigDecimal.valueOf(amount)) >= 0;
    }
    @Override public EconomyResponse depositPlayer(String name, double amount) { return write(name, amount, true); }
    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return write(name, amount, false); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) { return write(player.getUniqueId(), amount, true); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) { return write(player.getUniqueId(), amount, false); }
    @Override public boolean createPlayerAccount(String name) {
        try { await(byName(name, store::ensure)); return true; } catch (IllegalStateException e) { return false; }
    }
    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        try { await(store.ensure(player.getUniqueId())); return true; } catch (IllegalStateException e) { return false; }
    }
    private EconomyResponse unsupported() { return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported"); }
    @Override public List<String> getBanks() { return List.of(); }
    @Override public EconomyResponse createBank(String name, String player) { return unsupported(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return unsupported(); }
    @Override public EconomyResponse deleteBank(String name) { return unsupported(); }
    @Override public EconomyResponse bankBalance(String name) { return unsupported(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return unsupported(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return unsupported(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return unsupported(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return unsupported(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return unsupported(); }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public boolean createPlayerAccount(String name, String world) { return createPlayerAccount(name); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public boolean hasAccount(OfflinePlayer player, String world) { return hasAccount(player); }
    @Override public double getBalance(OfflinePlayer player, String world) { return getBalance(player); }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String world) { return createPlayerAccount(player); }
    @Override public boolean has(OfflinePlayer player, String world, double amount) { return has(player, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) { return withdrawPlayer(player, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) { return depositPlayer(player, amount); }
}
