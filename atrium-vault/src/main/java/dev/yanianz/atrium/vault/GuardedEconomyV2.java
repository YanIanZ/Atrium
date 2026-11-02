package dev.yanianz.atrium.vault;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.AsyncEconomy;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import net.milkbowl.vault2.economy.MultiEconomyResponse;

/**
 * The Vault v2 (VaultUnlocked, {@code net.milkbowl.vault2}) counterpart of {@link GuardedEconomy}: registered at the
 * highest service priority, it validates every call and forwards it to the best <em>other</em> registered v2 provider.
 *
 * <ul>
 *   <li>Amounts must be non-null and non-negative, have at most {@link DecimalLimits#maxScale()} decimal places
 *       (trailing zeros ignored) and {@link DecimalLimits#maxPrecision()} significant digits; write amounts
 *       (withdraw, deposit, set, transfer) must not exceed {@link DecimalLimits#maxAmount()}.</li>
 *   <li>Every parameter the API marks {@code @NotNull} (plugin name, account id, world, currency, ...) must be non-null;
 *       a violation is rejected and logged, rate-limited, instead of reaching the provider.</li>
 *   <li>A {@link RuntimeException} thrown by the provider, or an {@link AbstractMethodError} from a provider compiled
 *       against an older v2 API, becomes a {@code FAILURE}/{@code NOT_IMPLEMENTED} response (or a safe default) and is
 *       logged, rate-limited. A {@code null} response becomes {@code FAILURE}.</li>
 *   <li>Writes are audited with the exact amount; the caller column names the calling plugin (stack walk) and the
 *       plugin name the caller passed, when they differ.</li>
 *   <li>Calls that block a server tick thread (on Folia: a region thread) longer than the slow-call threshold are
 *       reported with the calling plugin.</li>
 *   <li>{@link #async()} is empty: the guard does not expose the provider's asynchronous API, so
 *       {@code EconomyFutures} and other async users fall back to these guarded calls.</li>
 * </ul>
 *
 * <p>Thread-safety: lock-free hot path (one volatile read and an atomic cache), like {@link GuardedEconomy}.
 */
@SuppressWarnings("deprecation") // the deprecated v2 overloads are part of the contract and are forwarded as-is
public final class GuardedEconomyV2 implements Economy {
    private final ProviderCache<Economy> cache;
    private final RateLimitedLog log;
    private final AuditSink audit;
    private final CallerResolver callers;
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final LongAdder slowTickCalls = new LongAdder();
    private volatile long slowCallNanos = TimeUnit.MILLISECONDS.toNanos(50);
    private volatile BooleanSupplier tickThread = GuardedEconomyV2::onTickThread;
    private volatile DecimalLimits limits;

    public GuardedEconomyV2(Supplier<? extends List<? extends Economy>> candidates, DecimalLimits limits,
                            RateLimitedLog log, AuditSink audit, CallerResolver callers) {
        this.cache = new ProviderCache<>(candidates, provider -> !(provider instanceof GuardedEconomyV2), log, "v2 economy");
        this.limits = limits;
        this.log = log;
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.callers = callers == null ? CallerResolver.NONE : callers;
    }

    // ------------------------------------------------------------------ state

    /** Provider calls are forwarded to, or {@code null}. Never a guard (and never this). */
    public Economy delegate() {
        return cache.get();
    }

    public void invalidate() {
        cache.invalidate();
    }

    public DecimalLimits limits() {
        return limits;
    }

    public void limits(DecimalLimits limits) {
        this.limits = limits;
    }

    public long rejectedCount() {
        return rejected.sum();
    }

    public long failureCount() {
        return failures.sum();
    }

    public long slowTickCallCount() {
        return slowTickCalls.sum();
    }

    public void slowCallThresholdMillis(long millis) {
        this.slowCallNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, millis));
    }

    void tickThreadProbe(BooleanSupplier probe) {
        this.tickThread = probe;
    }

    private static boolean onTickThread() {
        try {
            return org.bukkit.Bukkit.isPrimaryThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void reportIfSlow(String operation, Economy delegate, long startNanos) {
        long threshold = slowCallNanos;
        if (threshold <= 0) return;
        long elapsed = System.nanoTime() - startNanos;
        if (elapsed < threshold || !tickThread.getAsBoolean()) return;
        slowTickCalls.increment();
        log.log(Level.WARNING, "slow2:" + operation,
                "Vault v2 " + operation + " took " + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms on tick thread '"
                        + Thread.currentThread().getName() + "' (caller: " + callers.caller() + ", provider: "
                        + safeName(delegate) + "). The calling plugin should not use Vault on a region thread for"
                        + " players whose data is not loaded.", null);
    }

    static String nulls(Object... arguments) {
        for (Object argument : arguments) {
            if (argument == null) return "Argument must not be null";
        }
        return null;
    }

    private void reject(String operation, String reason) {
        rejected.increment();
        String caller = callers.caller();
        log.log(Level.WARNING, "reject2:" + operation,
                "Rejected Vault v2 " + operation + (caller != null ? " from " + caller : "") + ": " + reason, null);
    }

    private void failure(String operation, Economy delegate, Throwable e) {
        failures.increment();
        String what = e instanceof AbstractMethodError ? "does not implement" : "threw during";
        log.log(Level.SEVERE, "error2:" + operation + ':' + e.getClass().getName(),
                "Economy v2 provider '" + safeName(delegate) + "' " + what + " " + operation + "; returned a failure instead",
                e instanceof AbstractMethodError ? null : e);
    }

    static String safeName(Economy economy) {
        try {
            return economy.getName();
        } catch (RuntimeException | AbstractMethodError e) {
            return economy.getClass().getName();
        }
    }

    private static EconomyResponse fail(String message) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.FAILURE, message);
    }

    private static EconomyResponse fail(ResponseType type, String message) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, type, message);
    }

    /** Stack caller, plus the plugin name the caller passed when it says something else. */
    private String caller(String pluginName) {
        String caller = callers.caller();
        if (caller == null) return pluginName;
        if (pluginName == null || pluginName.equalsIgnoreCase(caller)) return caller;
        return caller + " (as " + pluginName + ")";
    }

    private static String scope(String world, String currency) {
        if (currency == null) return world;
        return (world == null ? "" : world + " ") + "currency=" + currency;
    }

    private <T> T call(String operation, T fallback, Function<Economy, T> call) {
        Economy delegate = delegate();
        if (delegate == null) return fallback;
        long start = System.nanoTime();
        try {
            T value = call.apply(delegate);
            return value != null ? value : fallback;
        } catch (RuntimeException | AbstractMethodError e) {
            failure(operation, delegate, e);
            return fallback;
        } finally {
            reportIfSlow(operation, delegate, start);
        }
    }

    private <T> T read(String operation, T fallback, Function<Economy, T> call, Object... required) {
        String problem = nulls(required);
        if (problem != null) {
            reject(operation, problem);
            return fallback;
        }
        return call(operation, fallback, call);
    }

    private boolean check(String operation, BigDecimal amount, Function<Economy, Boolean> call, Object... required) {
        String problem = nulls(required);
        if (problem == null) problem = limits.check(amount, false);
        if (problem != null) {
            reject(operation, problem);
            return false;
        }
        return call(operation, Boolean.FALSE, call);
    }

    private EconomyResponse respond(String operation, Function<Economy, EconomyResponse> call) {
        Economy delegate = delegate();
        if (delegate == null) return fail(GuardedEconomy.NO_PROVIDER);
        long start = System.nanoTime();
        try {
            EconomyResponse response = call.apply(delegate);
            if (response == null || response.type == null) {
                failures.increment();
                log.log(Level.SEVERE, "null2:" + operation,
                        "Economy v2 provider '" + safeName(delegate) + "' returned no response from " + operation, null);
                return fail("Economy provider returned no response");
            }
            return response;
        } catch (AbstractMethodError e) {
            failure(operation, delegate, e);
            return fail(ResponseType.NOT_IMPLEMENTED, "Economy provider does not implement " + operation);
        } catch (RuntimeException e) {
            failure(operation, delegate, e);
            return fail("Economy provider error: " + e.getClass().getSimpleName());
        } finally {
            reportIfSlow(operation, delegate, start);
        }
    }

    /** canWithdraw/canDeposit: validated like a read, answered as a response. */
    private EconomyResponse ask(String operation, BigDecimal amount, Function<Economy, EconomyResponse> call,
                                Object... required) {
        String problem = nulls(required);
        if (problem == null) problem = limits.check(amount, false);
        if (problem != null) {
            reject(operation, problem);
            return fail(problem);
        }
        return respond(operation, call);
    }

    private EconomyResponse write(String operation, String pluginName, UUID account, String world, String currency,
                                  BigDecimal amount, Function<Economy, EconomyResponse> call, Object... required) {
        String problem = nulls(required);
        if (problem == null) problem = limits.check(amount, true);
        if (problem != null) {
            reject(operation, problem);
            audit(operation, pluginName, account, world, currency, amount, "REJECTED", BigDecimal.ZERO, problem);
            return fail(problem);
        }
        EconomyResponse response = respond(operation, call);
        audit(operation, pluginName, account, world, currency, amount, String.valueOf(response.type), response.balance,
                response.errorMessage);
        return response;
    }

    private MultiEconomyResponse move(String operation, String pluginName, UUID from, UUID to, String world,
                                          String currency, BigDecimal amount,
                                          Function<Economy, MultiEconomyResponse> call, Object... required) {
        String problem = nulls(required);
        if (problem == null) problem = limits.check(amount, true);
        if (problem == null && from.equals(to)) problem = "Source and target account are the same";
        String account = from + "->" + to;
        BigDecimal requested = amount == null ? BigDecimal.ZERO : amount;
        if (problem != null) {
            reject(operation, problem);
            audit(operation, pluginName, account, world, currency, amount, "REJECTED", BigDecimal.ZERO, problem);
            return new MultiEconomyResponse(requested, ResponseType.FAILURE, problem);
        }
        Economy delegate = delegate();
        MultiEconomyResponse response;
        if (delegate == null) {
            response = new MultiEconomyResponse(requested, ResponseType.FAILURE, GuardedEconomy.NO_PROVIDER);
        } else {
            long start = System.nanoTime();
            try {
                response = call.apply(delegate);
                if (response == null || response.type == null) {
                    failures.increment();
                    log.log(Level.SEVERE, "null2:" + operation, "Economy v2 provider '" + safeName(delegate)
                            + "' returned no response from " + operation, null);
                    response = new MultiEconomyResponse(requested, ResponseType.FAILURE, "Economy provider returned no response");
                }
            } catch (AbstractMethodError e) {
                failure(operation, delegate, e);
                response = new MultiEconomyResponse(requested, ResponseType.NOT_IMPLEMENTED,
                        "Economy provider does not implement " + operation);
            } catch (RuntimeException e) {
                failure(operation, delegate, e);
                response = new MultiEconomyResponse(requested, ResponseType.FAILURE,
                        "Economy provider error: " + e.getClass().getSimpleName());
            } finally {
                reportIfSlow(operation, delegate, start);
            }
        }
        audit(operation, pluginName, account, world, currency, amount, String.valueOf(response.type),
                response.balance(from).orElse(null), response.errorMessage);
        return response;
    }

    private void audit(String operation, String pluginName, Object account, String world, String currency,
                       BigDecimal amount, String result, BigDecimal balance, String message) {
        if (audit == AuditSink.NONE) return;
        audit.record(new AuditEntry(System.currentTimeMillis(), "v2:" + operation, account, scope(world, currency),
                amount, result, balance, caller(pluginName), message == null || message.isEmpty() ? null : message));
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public boolean isEnabled() {
        return call("isEnabled", Boolean.FALSE, Economy::isEnabled);
    }

    @Override
    public String getName() {
        return delegate() == null ? "None" : call("getName", "Unknown", Economy::getName);
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return call("hasSharedAccountSupport", Boolean.FALSE, Economy::hasSharedAccountSupport);
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return call("hasMultiCurrencySupport", Boolean.FALSE, Economy::hasMultiCurrencySupport);
    }

    @Override
    public boolean supportsAsync() {
        return false;
    }

    @Override
    public Optional<AsyncEconomy> async() {
        return Optional.empty();
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return read("fractionalDigits", -1, e -> e.fractionalDigits(pluginName), pluginName);
    }

    @Override
    public int fractionalDigits(String pluginName, String currency) {
        return read("fractionalDigits", -1, e -> e.fractionalDigits(pluginName, currency), pluginName, currency);
    }

    private static String plain(BigDecimal amount) {
        return amount == null ? "null" : amount.toPlainString();
    }

    @Override
    public String format(BigDecimal amount) {
        return read("format", plain(amount), e -> e.format(amount), amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return read("format", plain(amount), e -> e.format(pluginName, amount), pluginName, amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return read("format", plain(amount), e -> e.format(amount, currency), amount, currency);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return read("format", plain(amount), e -> e.format(pluginName, amount, currency), pluginName, amount, currency);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return read("hasCurrency", Boolean.FALSE, e -> e.hasCurrency(currency), currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return read("getDefaultCurrency", "", e -> e.getDefaultCurrency(pluginName), pluginName);
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return read("defaultCurrencyNamePlural", "", e -> e.defaultCurrencyNamePlural(pluginName), pluginName);
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return read("defaultCurrencyNameSingular", "", e -> e.defaultCurrencyNameSingular(pluginName), pluginName);
    }

    @Override
    public Collection<String> currencies() {
        return call("currencies", List.of(), Economy::currencies);
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public boolean createAccount(UUID accountID, String name) {
        return read("createAccount", Boolean.FALSE, e -> e.createAccount(accountID, name), accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        return read("createAccount", Boolean.FALSE, e -> e.createAccount(accountID, name, player), accountID, name);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return read("createAccount", Boolean.FALSE, e -> e.createAccount(accountID, name, worldName), accountID, name,
                worldName);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return read("createAccount", Boolean.FALSE, e -> e.createAccount(accountID, name, worldName, player), accountID,
                name, worldName);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return call("getUUIDNameMap", Map.of(), Economy::getUUIDNameMap);
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        return read("getAccountName", Optional.empty(), e -> e.getAccountName(accountID), accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        return read("hasAccount", Boolean.FALSE, e -> e.hasAccount(accountID), accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        return read("hasAccount", Boolean.FALSE, e -> e.hasAccount(accountID, worldName), accountID, worldName);
    }

    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return read("renameAccount", Boolean.FALSE, e -> e.renameAccount(accountID, name), accountID, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID accountID, String name) {
        return read("renameAccount", Boolean.FALSE, e -> e.renameAccount(pluginName, accountID, name), pluginName,
                accountID, name);
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID accountID) {
        return read("deleteAccount", Boolean.FALSE, e -> e.deleteAccount(pluginName, accountID), pluginName, accountID);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return read("accountSupportsCurrency", Boolean.FALSE, e -> e.accountSupportsCurrency(pluginName, accountID, currency),
                pluginName, accountID, currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return read("accountSupportsCurrency", Boolean.FALSE,
                e -> e.accountSupportsCurrency(pluginName, accountID, currency, world), pluginName, accountID, currency, world);
    }

    // ------------------------------------------------------------------ balances

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        return read("getBalance", BigDecimal.ZERO, e -> e.getBalance(pluginName, accountID), pluginName, accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return read("getBalance", BigDecimal.ZERO, e -> e.getBalance(pluginName, accountID, world), pluginName, accountID,
                world);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return read("getBalance", BigDecimal.ZERO, e -> e.getBalance(pluginName, accountID, world, currency), pluginName,
                accountID, world, currency);
    }

    @Override
    public BigDecimal balance(String pluginName, UUID accountID) {
        return read("balance", BigDecimal.ZERO, e -> e.balance(pluginName, accountID), pluginName, accountID);
    }

    @Override
    public BigDecimal balance(String pluginName, UUID accountID, String world) {
        return read("balance", BigDecimal.ZERO, e -> e.balance(pluginName, accountID, world), pluginName, accountID, world);
    }

    @Override
    public BigDecimal balance(String pluginName, UUID accountID, String world, String currency) {
        return read("balance", BigDecimal.ZERO, e -> e.balance(pluginName, accountID, world, currency), pluginName,
                accountID, world, currency);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        return check("has", amount, e -> e.has(pluginName, accountID, amount), pluginName, accountID);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return check("has", amount, e -> e.has(pluginName, accountID, worldName, amount), pluginName, accountID, worldName);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return check("has", amount, e -> e.has(pluginName, accountID, worldName, currency, amount), pluginName, accountID,
                worldName, currency);
    }

    // ------------------------------------------------------------------ writes

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) {
        return write("set", pluginName, accountID, null, null, amount, e -> e.set(pluginName, accountID, amount),
                pluginName, accountID);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write("set", pluginName, accountID, worldName, null, amount,
                e -> e.set(pluginName, accountID, worldName, amount), pluginName, accountID, worldName);
    }

    @Override
    public EconomyResponse set(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return write("set", pluginName, accountID, worldName, currency, amount,
                e -> e.set(pluginName, accountID, worldName, currency, amount), pluginName, accountID, worldName, currency);
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) {
        return move("transfer", pluginName, from, to, null, null, amount, e -> e.transfer(pluginName, from, to, amount),
                pluginName, from, to);
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, BigDecimal amount) {
        return move("transfer", pluginName, from, to, worldName, null, amount,
                e -> e.transfer(pluginName, from, to, worldName, amount), pluginName, from, to, worldName);
    }

    @Override
    public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, String currency,
                                         BigDecimal amount) {
        return move("transfer", pluginName, from, to, worldName, currency, amount,
                e -> e.transfer(pluginName, from, to, worldName, currency, amount), pluginName, from, to, worldName, currency);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return ask("canWithdraw", amount, e -> e.canWithdraw(pluginName, accountID, amount), pluginName, accountID);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return ask("canWithdraw", amount, e -> e.canWithdraw(pluginName, accountID, worldName, amount), pluginName,
                accountID, worldName);
    }

    @Override
    public EconomyResponse canWithdraw(String pluginName, UUID accountID, String worldName, String currency,
                                       BigDecimal amount) {
        return ask("canWithdraw", amount, e -> e.canWithdraw(pluginName, accountID, worldName, currency, amount),
                pluginName, accountID, worldName, currency);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return write("withdraw", pluginName, accountID, null, null, amount, e -> e.withdraw(pluginName, accountID, amount),
                pluginName, accountID);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write("withdraw", pluginName, accountID, worldName, null, amount,
                e -> e.withdraw(pluginName, accountID, worldName, amount), pluginName, accountID, worldName);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency,
                                    BigDecimal amount) {
        return write("withdraw", pluginName, accountID, worldName, currency, amount,
                e -> e.withdraw(pluginName, accountID, worldName, currency, amount), pluginName, accountID, worldName,
                currency);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, BigDecimal amount) {
        return ask("canDeposit", amount, e -> e.canDeposit(pluginName, accountID, amount), pluginName, accountID);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return ask("canDeposit", amount, e -> e.canDeposit(pluginName, accountID, worldName, amount), pluginName,
                accountID, worldName);
    }

    @Override
    public EconomyResponse canDeposit(String pluginName, UUID accountID, String worldName, String currency,
                                      BigDecimal amount) {
        return ask("canDeposit", amount, e -> e.canDeposit(pluginName, accountID, worldName, currency, amount),
                pluginName, accountID, worldName, currency);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return write("deposit", pluginName, accountID, null, null, amount, e -> e.deposit(pluginName, accountID, amount),
                pluginName, accountID);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write("deposit", pluginName, accountID, worldName, null, amount,
                e -> e.deposit(pluginName, accountID, worldName, amount), pluginName, accountID, worldName);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency,
                                   BigDecimal amount) {
        return write("deposit", pluginName, accountID, worldName, currency, amount,
                e -> e.deposit(pluginName, accountID, worldName, currency, amount), pluginName, accountID, worldName,
                currency);
    }

    // ------------------------------------------------------------------ shared accounts

    @Override
    public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
        return read("createSharedAccount", Boolean.FALSE, e -> e.createSharedAccount(pluginName, accountID, name, owner),
                pluginName, accountID, name, owner);
    }

    @Override
    public List<String> accountsOwnedBy(String pluginName, UUID accountID) {
        return read("accountsOwnedBy", List.of(), e -> e.accountsOwnedBy(pluginName, accountID), pluginName, accountID);
    }

    @Override
    public List<UUID> accountsWithOwnerOf(String pluginName, UUID accountID) {
        return read("accountsWithOwnerOf", List.of(), e -> e.accountsWithOwnerOf(pluginName, accountID), pluginName,
                accountID);
    }

    @Override
    public List<String> accountsMemberOf(String pluginName, UUID accountID) {
        return read("accountsMemberOf", List.of(), e -> e.accountsMemberOf(pluginName, accountID), pluginName, accountID);
    }

    @Override
    public List<UUID> accountsWithMembershipTo(String pluginName, UUID accountID) {
        return read("accountsWithMembershipTo", List.of(), e -> e.accountsWithMembershipTo(pluginName, accountID),
                pluginName, accountID);
    }

    @Override
    public List<String> accountsAccessTo(String pluginName, UUID accountID, AccountPermission... permissions) {
        return read("accountsAccessTo", List.of(), e -> e.accountsAccessTo(pluginName, accountID, permissions), pluginName,
                accountID, permissions);
    }

    @Override
    public List<UUID> accountsWithAccessTo(String pluginName, UUID accountID, AccountPermission... permissions) {
        return read("accountsWithAccessTo", List.of(), e -> e.accountsWithAccessTo(pluginName, accountID, permissions),
                pluginName, accountID, permissions);
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
        return read("isAccountOwner", Boolean.FALSE, e -> e.isAccountOwner(pluginName, accountID, uuid), pluginName,
                accountID, uuid);
    }

    @Override
    public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
        return read("setOwner", Boolean.FALSE, e -> e.setOwner(pluginName, accountID, uuid), pluginName, accountID, uuid);
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return read("isAccountMember", Boolean.FALSE, e -> e.isAccountMember(pluginName, accountID, uuid), pluginName,
                accountID, uuid);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return read("addAccountMember", Boolean.FALSE, e -> e.addAccountMember(pluginName, accountID, uuid), pluginName,
                accountID, uuid);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) {
        return read("addAccountMember", Boolean.FALSE,
                e -> e.addAccountMember(pluginName, accountID, uuid, initialPermissions), pluginName, accountID, uuid,
                initialPermissions);
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return read("removeAccountMember", Boolean.FALSE, e -> e.removeAccountMember(pluginName, accountID, uuid),
                pluginName, accountID, uuid);
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) {
        return read("hasAccountPermission", Boolean.FALSE,
                e -> e.hasAccountPermission(pluginName, accountID, uuid, permission), pluginName, accountID, uuid, permission);
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission,
                                           boolean value) {
        return read("updateAccountPermission", Boolean.FALSE,
                e -> e.updateAccountPermission(pluginName, accountID, uuid, permission, value), pluginName, accountID, uuid,
                permission);
    }
}
