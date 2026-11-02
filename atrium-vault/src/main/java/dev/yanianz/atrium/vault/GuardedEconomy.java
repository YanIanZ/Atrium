package dev.yanianz.atrium.vault;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.logging.Level;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * Economy registered at the highest service priority that validates every call and forwards it to the best
 * <em>other</em> registered provider.
 *
 * <ul>
 *   <li>Amounts must be finite and non-negative; write amounts must also not exceed {@link GuardSettings#maxAmount()}.</li>
 *   <li>Account names/players must be non-null (names also non-blank).</li>
 *   <li>A {@link RuntimeException} thrown by the provider becomes a {@code FAILURE} response (or a safe default for
 *       non-response methods: {@code false}, {@code 0}, empty) and is logged, rate-limited.</li>
 *   <li>Without a provider the guard behaves as "no economy": {@link #isEnabled()} is {@code false} and every write
 *       fails.</li>
 * </ul>
 *
 * <p>Thread-safety: Vault calls can arrive from any thread on Folia. The hot path only reads a volatile field and an
 * {@link AtomicReference}; there are no locks. The resolved delegate is cached and dropped by {@link #invalidate()},
 * which the plugin calls on every economy {@code ServiceRegisterEvent}/{@code ServiceUnregisterEvent}. A resolution that
 * races with an invalidation is discarded, so a stale provider is never re-cached.
 */
@SuppressWarnings("deprecation") // the String-name overloads are part of the Vault contract
public final class GuardedEconomy implements Economy {
    public static final String NO_PROVIDER = "No economy provider is registered";

    /** Cache cell; a fresh unresolved instance is created on each invalidation so CAS detects races. */
    private record Resolved(Economy delegate, boolean valid) {
    }

    private final EconomyCandidates candidates;
    private final RateLimitedLog log;
    private final AuditSink audit;
    private final CallerResolver callers;
    private final AtomicReference<Resolved> cache = new AtomicReference<>(new Resolved(null, false));
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private volatile GuardSettings settings;

    public GuardedEconomy(EconomyCandidates candidates, GuardSettings settings, RateLimitedLog log,
                          AuditSink audit, CallerResolver callers) {
        this.candidates = candidates;
        this.settings = settings;
        this.log = log;
        this.audit = audit == null ? AuditSink.NONE : audit;
        this.callers = callers == null ? CallerResolver.NONE : callers;
    }

    // ------------------------------------------------------------------ delegate resolution

    /** Returns the provider calls are forwarded to, or {@code null} when there is none. Never returns a guard. */
    public Economy delegate() {
        Resolved current = cache.get();
        if (current.valid()) return current.delegate();
        Economy resolved = resolve();
        cache.compareAndSet(current, new Resolved(resolved, true));
        return resolved;
    }

    /** Drops the cached delegate; the next call resolves it again. */
    public void invalidate() {
        cache.set(new Resolved(null, false));
    }

    private Economy resolve() {
        try {
            List<Economy> list = candidates.candidates();
            if (list == null) return null;
            for (Economy economy : list) {
                if (economy != null && economy != this && !(economy instanceof GuardedEconomy)) return economy;
            }
        } catch (RuntimeException e) {
            failures.increment();
            log.log(Level.SEVERE, "resolve:" + e.getClass().getName(), "Cannot list the registered economy providers", e);
        }
        return null;
    }

    public GuardSettings settings() {
        return settings;
    }

    public void settings(GuardSettings settings) {
        this.settings = settings;
    }

    public long rejectedCount() {
        return rejected.sum();
    }

    public long failureCount() {
        return failures.sum();
    }

    // ------------------------------------------------------------------ validation

    static String checkAccount(Object account) {
        if (account == null) return "Account must not be null";
        if (account instanceof String name && name.isBlank()) return "Account name must not be blank";
        return null;
    }

    static String checkAmount(double amount, double max) {
        if (Double.isNaN(amount)) return "Amount is NaN";
        if (Double.isInfinite(amount)) return "Amount is infinite";
        if (amount < 0) return "Amount must not be negative";
        if (amount > max) return "Amount exceeds the configured maximum of " + String.format(Locale.ROOT, "%.2f", max);
        return null;
    }

    private String checkWrite(Object account, double amount) {
        String problem = checkAccount(account);
        return problem != null ? problem : checkAmount(amount, settings.maxAmount());
    }

    private String checkRead(Object account, double amount) {
        String problem = checkAccount(account);
        return problem != null ? problem : checkAmount(amount, Double.MAX_VALUE);
    }

    private void reject(String operation, String reason) {
        rejected.increment();
        String caller = callers.caller();
        log.log(Level.WARNING, "reject:" + operation,
                "Rejected Vault " + operation + (caller != null ? " from " + caller : "") + ": " + reason, null);
    }

    private void failure(String operation, Economy delegate, RuntimeException e) {
        failures.increment();
        log.log(Level.SEVERE, "error:" + operation + ':' + e.getClass().getName(),
                "Economy provider '" + safeName(delegate) + "' threw during " + operation + "; returned a failure instead", e);
    }

    private static String safeName(Economy economy) {
        try {
            return economy.getName();
        } catch (RuntimeException e) {
            return economy.getClass().getName();
        }
    }

    private static EconomyResponse fail(String message) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, message);
    }

    // ------------------------------------------------------------------ call helpers

    private <T> T query(String operation, T fallback, Function<Economy, T> call) {
        Economy delegate = delegate();
        if (delegate == null) return fallback;
        try {
            T value = call.apply(delegate);
            return value != null ? value : fallback;
        } catch (RuntimeException e) {
            failure(operation, delegate, e);
            return fallback;
        }
    }

    private boolean check(String operation, Object account, double amount, Function<Economy, Boolean> call) {
        String problem = checkRead(account, amount);
        if (problem != null) {
            reject(operation, problem);
            return false;
        }
        return query(operation, Boolean.FALSE, call);
    }

    private <T> T read(String operation, Object account, T fallback, Function<Economy, T> call) {
        String problem = checkAccount(account);
        if (problem != null) {
            reject(operation, problem);
            return fallback;
        }
        return query(operation, fallback, call);
    }

    private EconomyResponse respond(String operation, Function<Economy, EconomyResponse> call) {
        Economy delegate = delegate();
        if (delegate == null) return fail(NO_PROVIDER);
        try {
            EconomyResponse response = call.apply(delegate);
            if (response == null) {
                failures.increment();
                log.log(Level.SEVERE, "null:" + operation,
                        "Economy provider '" + safeName(delegate) + "' returned null from " + operation, null);
                return fail("Economy provider returned no response");
            }
            return response;
        } catch (RuntimeException e) {
            failure(operation, delegate, e);
            return fail("Economy provider error: " + e.getClass().getSimpleName());
        }
    }

    private EconomyResponse write(String operation, Object account, String world, double amount,
                                  Function<Economy, EconomyResponse> call) {
        String problem = checkWrite(account, amount);
        if (problem != null) {
            reject(operation, problem);
            audit(operation, account, world, amount, "REJECTED", 0, problem);
            return fail(problem);
        }
        EconomyResponse response = respond(operation, call);
        audit(operation, account, world, amount, String.valueOf(response.type), response.balance, response.errorMessage);
        return response;
    }

    private EconomyResponse bank(String operation, String bank, Function<Economy, EconomyResponse> call) {
        String problem = checkAccount(bank);
        if (problem != null) {
            reject(operation, problem);
            return fail(problem);
        }
        return respond(operation, call);
    }

    private EconomyResponse bank(String operation, String bank, Object member, Function<Economy, EconomyResponse> call) {
        String problem = checkAccount(member);
        if (problem != null) {
            reject(operation, problem);
            return fail(problem);
        }
        return bank(operation, bank, call);
    }

    private void audit(String operation, Object account, String world, double amount, String result, double balance,
                       String message) {
        if (audit == AuditSink.NONE) return;
        audit.record(new AuditEntry(System.currentTimeMillis(), operation, account, world, amount, result, balance,
                callers.caller(), message));
    }

    // ------------------------------------------------------------------ Economy: metadata

    @Override
    public boolean isEnabled() {
        return query("isEnabled", Boolean.FALSE, Economy::isEnabled);
    }

    @Override
    public String getName() {
        Economy delegate = delegate();
        return delegate == null ? "None" : query("getName", "Unknown", Economy::getName);
    }

    @Override
    public boolean hasBankSupport() {
        return query("hasBankSupport", Boolean.FALSE, Economy::hasBankSupport);
    }

    @Override
    public int fractionalDigits() {
        return query("fractionalDigits", -1, Economy::fractionalDigits);
    }

    @Override
    public String format(double amount) {
        String fallback = Double.isFinite(amount) ? String.format(Locale.ROOT, "%.2f", amount) : String.valueOf(amount);
        return query("format", fallback, e -> e.format(amount));
    }

    @Override
    public String currencyNamePlural() {
        return query("currencyNamePlural", "", Economy::currencyNamePlural);
    }

    @Override
    public String currencyNameSingular() {
        return query("currencyNameSingular", "", Economy::currencyNameSingular);
    }

    // ------------------------------------------------------------------ Economy: accounts and balances

    @Override
    public boolean hasAccount(String name) {
        return read("hasAccount", name, Boolean.FALSE, e -> e.hasAccount(name));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return read("hasAccount", player, Boolean.FALSE, e -> e.hasAccount(player));
    }

    @Override
    public boolean hasAccount(String name, String world) {
        return read("hasAccount", name, Boolean.FALSE, e -> e.hasAccount(name, world));
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return read("hasAccount", player, Boolean.FALSE, e -> e.hasAccount(player, world));
    }

    @Override
    public double getBalance(String name) {
        return read("getBalance", name, 0.0, e -> e.getBalance(name));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return read("getBalance", player, 0.0, e -> e.getBalance(player));
    }

    @Override
    public double getBalance(String name, String world) {
        return read("getBalance", name, 0.0, e -> e.getBalance(name, world));
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return read("getBalance", player, 0.0, e -> e.getBalance(player, world));
    }

    @Override
    public boolean has(String name, double amount) {
        return check("has", name, amount, e -> e.has(name, amount));
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return check("has", player, amount, e -> e.has(player, amount));
    }

    @Override
    public boolean has(String name, String world, double amount) {
        return check("has", name, amount, e -> e.has(name, world, amount));
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return check("has", player, amount, e -> e.has(player, world, amount));
    }

    @Override
    public boolean createPlayerAccount(String name) {
        return read("createPlayerAccount", name, Boolean.FALSE, e -> e.createPlayerAccount(name));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return read("createPlayerAccount", player, Boolean.FALSE, e -> e.createPlayerAccount(player));
    }

    @Override
    public boolean createPlayerAccount(String name, String world) {
        return read("createPlayerAccount", name, Boolean.FALSE, e -> e.createPlayerAccount(name, world));
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return read("createPlayerAccount", player, Boolean.FALSE, e -> e.createPlayerAccount(player, world));
    }

    // ------------------------------------------------------------------ Economy: writes

    @Override
    public EconomyResponse withdrawPlayer(String name, double amount) {
        return write("withdrawPlayer", name, null, amount, e -> e.withdrawPlayer(name, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return write("withdrawPlayer", player, null, amount, e -> e.withdrawPlayer(player, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(String name, String world, double amount) {
        return write("withdrawPlayer", name, world, amount, e -> e.withdrawPlayer(name, world, amount));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return write("withdrawPlayer", player, world, amount, e -> e.withdrawPlayer(player, world, amount));
    }

    @Override
    public EconomyResponse depositPlayer(String name, double amount) {
        return write("depositPlayer", name, null, amount, e -> e.depositPlayer(name, amount));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return write("depositPlayer", player, null, amount, e -> e.depositPlayer(player, amount));
    }

    @Override
    public EconomyResponse depositPlayer(String name, String world, double amount) {
        return write("depositPlayer", name, world, amount, e -> e.depositPlayer(name, world, amount));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return write("depositPlayer", player, world, amount, e -> e.depositPlayer(player, world, amount));
    }

    // ------------------------------------------------------------------ Economy: banks

    @Override
    public EconomyResponse createBank(String name, String player) {
        return bank("createBank", name, player, e -> e.createBank(name, player));
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        return bank("createBank", name, player, e -> e.createBank(name, player));
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return bank("deleteBank", name, e -> e.deleteBank(name));
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return bank("bankBalance", name, e -> e.bankBalance(name));
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        String problem = checkRead(name, amount);
        if (problem != null) {
            reject("bankHas", problem);
            return fail(problem);
        }
        return respond("bankHas", e -> e.bankHas(name, amount));
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return write("bankWithdraw", bankAccount(name), null, amount, e -> e.bankWithdraw(name, amount));
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return write("bankDeposit", bankAccount(name), null, amount, e -> e.bankDeposit(name, amount));
    }

    private static String bankAccount(String name) {
        return name == null || name.isBlank() ? name : "bank:" + name;
    }

    @Override
    public EconomyResponse isBankOwner(String name, String player) {
        return bank("isBankOwner", name, player, e -> e.isBankOwner(name, player));
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bank("isBankOwner", name, player, e -> e.isBankOwner(name, player));
    }

    @Override
    public EconomyResponse isBankMember(String name, String player) {
        return bank("isBankMember", name, player, e -> e.isBankMember(name, player));
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bank("isBankMember", name, player, e -> e.isBankMember(name, player));
    }

    @Override
    public List<String> getBanks() {
        return query("getBanks", List.of(), Economy::getBanks);
    }
}
