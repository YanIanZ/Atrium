package dev.yanianz.atrium.module.economy;

import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.economy.EconomyStore.Key;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import org.bukkit.plugin.ServicePriority;

/**
 * Atrium's native Vault v2 (VaultUnlocked, {@code net.milkbowl.vault2.economy.Economy}) provider, registered next to the
 * v1 {@link VaultBridge} when the v2 API is on the class path (Atrium Vault 2.20+, or VaultUnlocked).
 *
 * <ul>
 *   <li>Exact decimals end to end: an amount with more decimal places than the economy's scale is refused, never
 *       rounded; null and negative amounts are refused.</li>
 *   <li>UUID accounts; player accounts only (non-player and shared accounts are refused).</li>
 *   <li>One currency, id {@value #CURRENCY}; the configured singular and plural names are accepted as aliases. Any
 *       other currency fails (reads answer 0).</li>
 *   <li>World groups: the v2 {@code world} parameter selects the group of that world, exactly like the v1 world
 *       overloads; a name that maps to no world but is a configured group name selects that group. Overloads without
 *       a world use the context group (current group of an online player, else the default group).</li>
 *   <li>Reads never wait on a tick thread or for an online player (the same {@link VaultReads} policy as v1); writes
 *       wait at most {@code vault-timeout-millis}, and a timed-out write reports FAILURE with an unknown outcome.</li>
 *   <li>The v2 {@code pluginName} is recorded in the ledger reason: {@code vault2:<operation>:<plugin>}.</li>
 *   <li>{@code set} and {@code transfer} are single ledger transactions (no withdraw-then-deposit window).</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // the deprecated v2 overloads are part of the contract
final class VaultUnlockedBridge implements Economy, AutoCloseable {
    static final String CURRENCY = "default";
    static final String NAME = "Atrium";

    /** Storage side, kept narrow so the provider is testable without a server or a database. */
    interface Backend {
        /** Balance key: the group of {@code world} (see {@link WorldGroups#keyOfWorldOrGroup}), or the context group. */
        Key key(UUID id, String world);

        /** Non-blocking read (cache, else 0 and a background load on a tick thread, else a bounded wait). */
        BigDecimal read(Key key);

        boolean isOnline(UUID id);

        CompletableFuture<Boolean> exists(Key key);

        CompletableFuture<BigDecimal> ensure(Key key);

        CompletableFuture<TransactionResult> deposit(Key key, BigDecimal amount, String reason);

        CompletableFuture<TransactionResult> withdraw(Key key, BigDecimal amount, String reason);

        CompletableFuture<TransactionResult> set(Key key, BigDecimal amount, String reason);

        CompletableFuture<TransactionResult> transfer(Key from, Key to, BigDecimal amount, String reason);

        /** Committed balance known without storage access, or {@code null}. */
        BigDecimal cached(Key key);

        /** Bounded wait; throws {@link IllegalStateException} on timeout, failure or when disabled. */
        <T> T await(CompletableFuture<T> future);

        int scale();

        String format(BigDecimal amount);

        String singular();

        String plural();

        /** Name of a loaded/online account, without storage access. */
        Optional<String> name(UUID id);

        /** Online accounts and their names. */
        Map<UUID, String> online();
    }

    private final Backend backend;
    private final Runnable unregister;
    private volatile boolean enabled = true;

    VaultUnlockedBridge(Backend backend, Runnable unregister) {
        this.backend = backend;
        this.unregister = unregister;
    }

    static VaultUnlockedBridge register(ModuleContext ctx, EconomyStore store, EconomyConfig config) {
        AtomicReference<VaultUnlockedBridge> self = new AtomicReference<>();
        VaultReads reads = new VaultReads(new VaultReads.Store() {
            @Override public BigDecimal cachedRead(Key account) { return store.cachedRead(account); }
            @Override public boolean isOnline(Key account) { return store.isOnline(account.id()); }
            @Override public CompletableFuture<BigDecimal> balance(Key account) { return store.balance(account); }
            @Override public void rememberRead(Key account, BigDecimal value) { store.rememberRead(account, value); }
        }, ctx.logger(), config.vaultTimeoutMillis, VaultBridge::onTickThread, () -> {
            VaultUnlockedBridge provider = self.get();
            return provider != null && provider.enabled;
        });
        Backend backend = new Backend() {
            @Override public Key key(UUID id, String world) {
                return world == null ? store.keyFor(id, null) : new Key(id, store.worldGroups().keyOfWorldOrGroup(world));
            }
            @Override public BigDecimal read(Key key) { return reads.balance(key); }
            @Override public boolean isOnline(UUID id) { return store.isOnline(id); }
            @Override public CompletableFuture<Boolean> exists(Key key) { return store.exists(key); }
            @Override public CompletableFuture<BigDecimal> ensure(Key key) { return store.ensure(key); }
            @Override public CompletableFuture<TransactionResult> deposit(Key key, BigDecimal amount, String reason) {
                return store.deposit(key, amount, reason);
            }
            @Override public CompletableFuture<TransactionResult> withdraw(Key key, BigDecimal amount, String reason) {
                return store.withdraw(key, amount, reason);
            }
            @Override public CompletableFuture<TransactionResult> set(Key key, BigDecimal amount, String reason) {
                return store.set(key, amount, reason);
            }
            @Override public CompletableFuture<TransactionResult> transfer(Key from, Key to, BigDecimal amount, String reason) {
                return store.transferTaxed(from, to, amount, BigDecimal.ZERO, reason);
            }
            @Override public BigDecimal cached(Key key) { return store.cachedRead(key); }
            @Override public <T> T await(CompletableFuture<T> future) { return reads.await(future); }
            @Override public int scale() { return store.money().scale; }
            @Override public String format(BigDecimal amount) { return store.format(amount); }
            @Override public String singular() { return store.currencyNameSingular(); }
            @Override public String plural() { return store.currencyNamePlural(); }
            @Override public Optional<String> name(UUID id) { return ctx.users().cached(id).map(AtriumUser::name); }
            @Override public Map<UUID, String> online() {
                Map<UUID, String> names = new LinkedHashMap<>();
                for (AtriumUser user : ctx.users().online()) names.put(user.uuid(), user.name());
                return names;
            }
        };
        var services = ctx.plugin().getServer().getServicesManager();
        VaultUnlockedBridge registered = new VaultUnlockedBridge(backend, () -> services.unregister(Economy.class, self.get()));
        self.set(registered);
        services.register(Economy.class, registered, ctx.plugin(), ServicePriority.Normal);
        ctx.logger().info("Vault v2 (VaultUnlocked) economy registered: exact decimals, currency '{}', world groups {}",
                CURRENCY, store.worldGroups().enabled() ? "via the world parameter" : "off");
        return registered;
    }

    @Override
    public void close() {
        enabled = false;
        unregister.run();
    }

    // ------------------------------------------------------------------ helpers

    /** Ledger reason; the plugin name is untrusted text, so only a short safe subset is kept. */
    static String reason(String operation, String pluginName) {
        StringBuilder plugin = new StringBuilder();
        if (pluginName != null) {
            for (int i = 0; i < pluginName.length() && plugin.length() < 64; i++) {
                char c = pluginName.charAt(i);
                if (c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.')) plugin.append(c);
            }
        }
        return "vault2:" + operation + ":" + (plugin.isEmpty() ? "unknown" : plugin);
    }

    /** Why an amount is refused, or {@code null}. More decimal places than the scale are refused, never rounded. */
    String invalid(BigDecimal amount) {
        if (amount == null) return "Amount must not be null";
        if (amount.signum() < 0) return "Amount must not be negative";
        if (amount.signum() != 0 && amount.stripTrailingZeros().scale() > backend.scale()) {
            return "Amount has more than " + backend.scale() + " decimal places";
        }
        return null;
    }

    boolean isCurrency(String currency) {
        if (currency == null) return false;
        String wanted = currency.trim().toLowerCase(Locale.ROOT);
        return wanted.equals(CURRENCY) || wanted.equals(lower(backend.singular())) || wanted.equals(lower(backend.plural()));
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static EconomyResponse failure(String message) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.FAILURE, message);
    }

    private static EconomyResponse response(TransactionResult result) {
        if (result == null) return failure("No result");
        BigDecimal balance = result.balance() == null ? BigDecimal.ZERO : result.balance();
        if (result.success()) {
            return new EconomyResponse(result.amount() == null ? BigDecimal.ZERO : result.amount(), balance,
                    ResponseType.SUCCESS, "");
        }
        return new EconomyResponse(BigDecimal.ZERO, balance, ResponseType.FAILURE, result.status().name());
    }

    private interface Change {
        CompletableFuture<TransactionResult> apply(Key key, BigDecimal amount, String reason);
    }

    private EconomyResponse write(String operation, String pluginName, UUID id, String world, String currency,
                                  BigDecimal amount, Change change) {
        if (!enabled) return failure("Economy disabled");
        if (id == null) return failure("Account must not be null");
        String problem = invalid(amount);
        if (problem != null) return failure(problem);
        if (currency != null && !isCurrency(currency)) return failure("Unknown currency: " + currency);
        try {
            return response(backend.await(change.apply(backend.key(id, world), amount, reason(operation, pluginName))));
        } catch (IllegalStateException e) {
            return failure(e.getMessage());
        }
    }

    private BigDecimal read(UUID id, String world, String currency) {
        if (!enabled || id == null) return BigDecimal.ZERO;
        if (currency != null && !isCurrency(currency)) return BigDecimal.ZERO;
        return backend.read(backend.key(id, world));
    }

    private boolean covers(UUID id, String world, String currency, BigDecimal amount) {
        if (amount == null || amount.signum() < 0) return false;
        if (currency != null && !isCurrency(currency)) return false;
        return read(id, world, null).compareTo(amount) >= 0;
    }

    private boolean exists(UUID id, String world) {
        if (!enabled || id == null) return false;
        if (world == null && backend.isOnline(id)) return true; // created at join in the current group
        try {
            return Boolean.TRUE.equals(backend.await(backend.exists(backend.key(id, world))));
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private boolean create(UUID id, String world, boolean player) {
        if (!enabled || id == null || !player) return false;
        try {
            backend.await(backend.ensure(backend.key(id, world)));
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private MultiEconomyResponse move(String pluginName, UUID from, UUID to, String world, String currency,
                                      BigDecimal amount) {
        BigDecimal requested = amount == null ? BigDecimal.ZERO : amount;
        String problem = !enabled ? "Economy disabled" : from == null || to == null ? "Account must not be null"
                : from.equals(to) ? "Source and target account are the same" : invalid(amount);
        if (problem == null && currency != null && !isCurrency(currency)) problem = "Unknown currency: " + currency;
        if (problem != null) return new MultiEconomyResponse(requested, ResponseType.FAILURE, problem);
        // Without a world both sides use the payer's context group, like EconomyService.transfer: never an implicit
        // move between groups.
        Key source = backend.key(from, world);
        Key target = new Key(to, source.group());
        try {
            TransactionResult result = backend.await(backend.transfer(source, target, amount, reason("transfer", pluginName)));
            if (result == null || !result.success()) {
                return new MultiEconomyResponse(requested, ResponseType.FAILURE,
                        result == null ? "No result" : result.status().name());
            }
            MultiEconomyResponse response = new MultiEconomyResponse(result.amount(), ResponseType.SUCCESS, "");
            response.addBalance(from, result.balance());
            BigDecimal received = backend.cached(target);
            if (received != null) response.addBalance(to, received);
            return response;
        } catch (IllegalStateException e) {
            return new MultiEconomyResponse(requested, ResponseType.FAILURE, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ metadata

    @Override public boolean isEnabled() { return enabled; }
    @Override public String getName() { return NAME; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return false; }
    @Override public int fractionalDigits(String pluginName) { return backend.scale(); }
    @Override public String format(BigDecimal amount) { return amount == null ? "" : backend.format(amount); }
    @Override public String format(String pluginName, BigDecimal amount) { return format(amount); }
    @Override public String format(BigDecimal amount, String currency) { return format(amount); }
    @Override public String format(String pluginName, BigDecimal amount, String currency) { return format(amount); }
    @Override public boolean hasCurrency(String currency) { return isCurrency(currency); }
    @Override public String getDefaultCurrency(String pluginName) { return CURRENCY; }
    @Override public String defaultCurrencyNamePlural(String pluginName) { return backend.plural(); }
    @Override public String defaultCurrencyNameSingular(String pluginName) { return backend.singular(); }
    @Override public Collection<String> currencies() { return List.of(CURRENCY); }

    // ------------------------------------------------------------------ accounts

    @Override public boolean createAccount(UUID accountID, String name) { return create(accountID, null, true); }
    @Override public boolean createAccount(UUID accountID, String name, boolean player) { return create(accountID, null, player); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName) { return create(accountID, worldName, true); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return create(accountID, worldName, player);
    }
    /** Online accounts only: listing every stored account would need a storage scan on the caller's thread. */
    @Override public Map<UUID, String> getUUIDNameMap() { return Map.copyOf(backend.online()); }
    @Override public Optional<String> getAccountName(UUID accountID) {
        return accountID == null ? Optional.empty() : backend.name(accountID);
    }
    @Override public boolean hasAccount(UUID accountID) { return exists(accountID, null); }
    @Override public boolean hasAccount(UUID accountID, String worldName) { return exists(accountID, worldName); }
    /** Account names are player names; they change with the player, not through Vault. */
    @Override public boolean renameAccount(UUID accountID, String name) { return false; }
    @Override public boolean renameAccount(String pluginName, UUID accountID, String name) { return false; }
    /** Accounts are never deleted through Vault: the ledger keeps their history. */
    @Override public boolean deleteAccount(String pluginName, UUID accountID) { return false; }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return isCurrency(currency);
    }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return isCurrency(currency);
    }

    // ------------------------------------------------------------------ balances

    @Override public BigDecimal getBalance(String pluginName, UUID accountID) { return read(accountID, null, null); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world) { return read(accountID, world, null); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return read(accountID, world, currency);
    }
    @Override public BigDecimal balance(String pluginName, UUID accountID) { return read(accountID, null, null); }
    @Override public BigDecimal balance(String pluginName, UUID accountID, String world) { return read(accountID, world, null); }
    @Override public BigDecimal balance(String pluginName, UUID accountID, String world, String currency) {
        return read(accountID, world, currency);
    }
    @Override public boolean has(String pluginName, UUID accountID, BigDecimal amount) { return covers(accountID, null, null, amount); }
    @Override public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return covers(accountID, worldName, null, amount);
    }
    @Override public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return covers(accountID, worldName, currency, amount);
    }

    // ------------------------------------------------------------------ writes

    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return write("withdraw", pluginName, accountID, null, null, amount, backend::withdraw);
    }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write("withdraw", pluginName, accountID, worldName, null, amount, backend::withdraw);
    }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return write("withdraw", pluginName, accountID, worldName, currency, amount, backend::withdraw);
    }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return write("deposit", pluginName, accountID, null, null, amount, backend::deposit);
    }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write("deposit", pluginName, accountID, worldName, null, amount, backend::deposit);
    }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return write("deposit", pluginName, accountID, worldName, currency, amount, backend::deposit);
    }
    @Override public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) {
        return write("set", pluginName, accountID, null, null, amount, backend::set);
    }
    @Override public EconomyResponse set(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write("set", pluginName, accountID, worldName, null, amount, backend::set);
    }
    @Override public EconomyResponse set(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return write("set", pluginName, accountID, worldName, currency, amount, backend::set);
    }
    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) {
        return move(pluginName, from, to, null, null, amount);
    }
    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, BigDecimal amount) {
        return move(pluginName, from, to, worldName, null, amount);
    }
    @Override public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, String worldName, String currency,
                                                   BigDecimal amount) {
        return move(pluginName, from, to, worldName, currency, amount);
    }

    // ------------------------------------------------------------------ shared accounts: not supported

    @Override public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) { return false; }
    @Override public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean setOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) {
        return false;
    }
    @Override public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) {
        return false;
    }
    @Override public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission,
                                                     boolean value) {
        return false;
    }
}
