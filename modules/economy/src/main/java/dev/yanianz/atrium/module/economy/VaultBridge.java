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
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;

/**
 * Loaded only when Vault is present. The synchronous compatibility boundary is intentionally isolated.
 *
 * <p>Reads are answered by {@link VaultReads}: a balance for an online player comes from the cache that
 * is warmed before the player is in game, so a scoreboard plugin calling Vault from its own thread on
 * join cannot stall. Writes keep their transactional semantics and are the only path that waits, bounded
 * by {@code vaultTimeoutMillis}, because Vault has no asynchronous result.
 */
@SuppressWarnings("deprecation")
final class VaultBridge extends AbstractEconomy implements AutoCloseable {
    private final ModuleContext ctx;
    private final EconomyStore store;
    private final VaultReads reads;
    private final Set<String> resolving = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled = true;

    private VaultBridge(ModuleContext ctx, EconomyStore store, EconomyConfig config) {
        this.ctx = ctx;
        this.store = store;
        this.reads = new VaultReads(new VaultReads.Store() {
            @Override public BigDecimal cachedRead(UUID account) { return store.cachedRead(account); }
            @Override public boolean isOnline(UUID account) { return store.isOnline(account); }
            @Override public CompletableFuture<BigDecimal> balance(UUID account) { return store.balance(account); }
            @Override public void rememberRead(UUID account, BigDecimal value) { store.rememberRead(account, value); }
        }, ctx.logger(), config.vaultTimeoutMillis, VaultBridge::onTickThread, () -> enabled);
    }

    static VaultBridge register(ModuleContext ctx, EconomyStore store, EconomyConfig config) {
        VaultBridge provider = new VaultBridge(ctx, store, config);
        ctx.plugin().getServer().getServicesManager().register(Economy.class, provider, ctx.plugin(), ServicePriority.Normal);
        ctx.logger().info("Vault economy registered; writes may wait up to {} ms, reads never wait on a tick thread",
                config.vaultTimeoutMillis);
        return provider;
    }

    @Override public void close() {
        enabled = false;
        ctx.plugin().getServer().getServicesManager().unregister(Economy.class, this);
    }

    /**
     * True on the global tick thread or on a region tick thread. Foreign plugin pools (for example a
     * scoreboard plugin's own thread) are not tick threads and may still wait briefly.
     */
    private static boolean onTickThread() {
        // Folia implements isPrimaryThread as TickThread.isTickThread, which covers region and global
        // tick threads; isGlobalTickThread keeps that true on builds that only implement the global part.
        if (Bukkit.isPrimaryThread() || Bukkit.isGlobalTickThread()) return true;
        // Fallback for forks that leave isPrimaryThread alone: Folia names region threads
        // "Folia Region Scheduler Thread #n". No API identifies a region thread without an entity or
        // location, and this check must work for offline accounts too.
        String name = Thread.currentThread().getName();
        return name.startsWith("Folia Region Scheduler Thread") || name.startsWith("Region Scheduler Thread");
    }

    private <T> T await(CompletableFuture<T> future) { return reads.await(future); }

    private BigDecimal balance(UUID id) { return reads.balance(id); }

    /**
     * Names are resolved through the online list first (no storage), then through the account store.
     * Resolving a name needs storage, so a tick thread gets a background lookup and {@code 0}.
     */
    private BigDecimal namedBalance(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return reads.balance(online.getUniqueId());
        if (reads.onTickThread()) {
            deferName(name);
            return BigDecimal.ZERO;
        }
        return await(ctx.users().loadByName(name)).map(user -> reads.balance(user.uuid())).orElse(BigDecimal.ZERO);
    }

    private void deferName(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!resolving.add(key)) return;
        reads.warnDeferred("name '" + name + "'");
        ctx.users().loadByName(name).whenComplete((user, failure) -> {
            resolving.remove(key);
            if (failure != null) {
                ctx.logger().warn("Deferred Vault name lookup failed for '{}'", name, failure);
                return;
            }
            user.ifPresent(found -> reads.defer(found.uuid()));
        });
    }

    private <T> CompletableFuture<T> byName(String name, Function<UUID, CompletableFuture<T>> work) {
        return ctx.users().loadByName(name).thenCompose(user -> user.isPresent() ? work.apply(user.get().uuid())
                : CompletableFuture.failedFuture(new IllegalArgumentException("Unknown Atrium player")));
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
        if (Bukkit.getPlayerExact(name) != null) return true;
        return await(ctx.users().loadByName(name).thenCompose(user -> user.isPresent()
                ? store.exists(user.get().uuid()) : CompletableFuture.completedFuture(false)));
    }
    @Override public boolean hasAccount(OfflinePlayer player) {
        // A warmed online account was created at join, so existence needs no storage round trip.
        return store.isOnline(player.getUniqueId()) || await(store.exists(player.getUniqueId()));
    }
    @Override public double getBalance(String name) { return namedBalance(name).doubleValue(); }
    @Override public double getBalance(OfflinePlayer player) { return balance(player.getUniqueId()).doubleValue(); }
    @Override public boolean has(String name, double amount) {
        return Double.isFinite(amount) && amount >= 0 && namedBalance(name).compareTo(BigDecimal.valueOf(amount)) >= 0;
    }
    @Override public boolean has(OfflinePlayer player, double amount) {
        return Double.isFinite(amount) && amount >= 0 && balance(player.getUniqueId()).compareTo(BigDecimal.valueOf(amount)) >= 0;
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
