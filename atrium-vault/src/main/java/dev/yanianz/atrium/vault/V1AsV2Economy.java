package dev.yanianz.atrium.vault;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * Exposes the best native Vault v1 economy as a Vault v2 ({@code net.milkbowl.vault2.economy.Economy}) provider, for
 * servers whose economy plugin only speaks v1. Registered by {@link BridgeManager} only while no native v2 provider
 * exists.
 *
 * <ul>
 *   <li>UUIDs become {@link OfflinePlayer}s ({@code Bukkit.getOfflinePlayer(UUID)} in production, which does not
 *       block). Only player accounts exist: non-player and shared accounts are refused.</li>
 *   <li>Balances (doubles) become exact decimals rounded HALF_EVEN to the provider's {@code fractionalDigits()}.</li>
 *   <li>An amount is passed to the v1 provider only when it is non-negative, has no more decimal places than the
 *       provider supports and is exactly representable as a double; otherwise the write fails and nothing moves.</li>
 *   <li>One currency: the provider's plural currency name (or {@code default}); world names go to the v1 world
 *       overloads unchanged.</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // v1 String overloads and deprecated v2 overloads are part of the contracts
public final class V1AsV2Economy implements net.milkbowl.vault2.economy.Economy, BridgedEconomy {
    static final String NO_PROVIDER = "No Vault v1 economy provider is registered";

    private final Supplier<Economy> source;
    private final Function<UUID, OfflinePlayer> players;

    public V1AsV2Economy(Supplier<Economy> source, Function<UUID, OfflinePlayer> players) {
        this.source = source;
        this.players = players;
    }

    /** The v1 provider currently bridged, or {@code null}. */
    public Economy source() {
        return source.get();
    }

    @Override
    public String bridgeDescription() {
        Economy economy = source.get();
        return "bridge v1->v2 of " + (economy == null ? "nothing" : name(economy));
    }

    private static String name(Economy economy) {
        try {
            return economy.getName();
        } catch (RuntimeException e) {
            return economy.getClass().getName();
        }
    }

    // ------------------------------------------------------------------ conversions

    private static int digits(Economy economy) {
        try {
            return economy.fractionalDigits();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private OfflinePlayer player(UUID id) {
        return players.apply(id);
    }

    private static BigDecimal decimal(double value, int digits) {
        return Double.isFinite(value) ? Decimals.fromDouble(value, digits) : BigDecimal.ZERO;
    }

    static EconomyResponse convert(net.milkbowl.vault.economy.EconomyResponse response, int digits) {
        if (response == null || response.type == null) {
            return failure("Economy provider returned no response");
        }
        ResponseType type = switch (response.type) {
            case SUCCESS -> ResponseType.SUCCESS;
            case NOT_IMPLEMENTED -> ResponseType.NOT_IMPLEMENTED;
            default -> ResponseType.FAILURE;
        };
        return new EconomyResponse(decimal(response.amount, digits), decimal(response.balance, digits), type,
                response.errorMessage == null ? "" : response.errorMessage);
    }

    private static EconomyResponse failure(String message) {
        return new EconomyResponse(BigDecimal.ZERO, BigDecimal.ZERO, ResponseType.FAILURE, message);
    }

    private String currencyId(Economy economy) {
        String plural = economy.currencyNamePlural();
        if (plural != null && !plural.isBlank()) return plural;
        String singular = economy.currencyNameSingular();
        return singular != null && !singular.isBlank() ? singular : "default";
    }

    private boolean isCurrency(Economy economy, String currency) {
        if (currency == null) return false;
        String wanted = currency.trim().toLowerCase(Locale.ROOT);
        if (wanted.equals("default")) return true;
        for (String name : new String[] {economy.currencyNamePlural(), economy.currencyNameSingular()}) {
            if (name != null && name.trim().toLowerCase(Locale.ROOT).equals(wanted)) return true;
        }
        return false;
    }

    /** {@code world == null}: the overload without a world. {@code currency == null}: no currency was named. */
    private EconomyResponse write(UUID id, String world, String currency, BigDecimal amount, boolean deposit) {
        Economy economy = source.get();
        if (economy == null) return failure(NO_PROVIDER);
        if (id == null) return failure("Account must not be null");
        if (amount == null) return failure("Amount must not be null");
        if (amount.signum() < 0) return failure("Amount must not be negative");
        if (currency != null && !isCurrency(economy, currency)) return failure("Unknown currency: " + currency);
        int digits = digits(economy);
        Double value = Decimals.toDouble(amount, digits);
        if (value == null) return failure(Decimals.notRepresentable(amount, digits));
        OfflinePlayer player = player(id);
        net.milkbowl.vault.economy.EconomyResponse response;
        if (deposit) {
            response = world == null ? economy.depositPlayer(player, value) : economy.depositPlayer(player, world, value);
        } else {
            response = world == null ? economy.withdrawPlayer(player, value) : economy.withdrawPlayer(player, world, value);
        }
        return convert(response, digits);
    }

    private BigDecimal read(UUID id, String world, String currency) {
        Economy economy = source.get();
        if (economy == null || id == null) return BigDecimal.ZERO;
        if (currency != null && !isCurrency(economy, currency)) return BigDecimal.ZERO;
        OfflinePlayer player = player(id);
        double balance = world == null ? economy.getBalance(player) : economy.getBalance(player, world);
        return decimal(balance, digits(economy));
    }

    private boolean covers(UUID id, String world, String currency, BigDecimal amount) {
        Economy economy = source.get();
        if (economy == null || id == null || amount == null || amount.signum() < 0) return false;
        if (currency != null && !isCurrency(economy, currency)) return false;
        Double value = Decimals.toDouble(amount, -1);
        if (value == null) return read(id, world, null).compareTo(amount) >= 0;
        OfflinePlayer player = player(id);
        return world == null ? economy.has(player, value) : economy.has(player, world, value);
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public boolean isEnabled() {
        Economy economy = source.get();
        return economy != null && economy.isEnabled();
    }

    @Override
    public String getName() {
        Economy economy = source.get();
        return economy == null ? "None" : name(economy);
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return false;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        Economy economy = source.get();
        return economy == null ? -1 : digits(economy);
    }

    @Override
    public String format(BigDecimal amount) {
        Economy economy = source.get();
        if (amount == null) return "null";
        return economy == null ? amount.toPlainString() : economy.format(amount.doubleValue());
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return format(amount);
    }

    @Override
    public boolean hasCurrency(String currency) {
        Economy economy = source.get();
        return economy != null && isCurrency(economy, currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        Economy economy = source.get();
        return economy == null ? "default" : currencyId(economy);
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        Economy economy = source.get();
        return economy == null ? "" : String.valueOf(economy.currencyNamePlural());
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        Economy economy = source.get();
        return economy == null ? "" : String.valueOf(economy.currencyNameSingular());
    }

    @Override
    public Collection<String> currencies() {
        Economy economy = source.get();
        return economy == null ? List.of() : List.of(currencyId(economy));
    }

    // ------------------------------------------------------------------ accounts

    @Override
    public boolean createAccount(UUID accountID, String name) {
        return createAccount(accountID, name, true);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        Economy economy = source.get();
        if (economy == null || accountID == null || !player) return false;
        return economy.createPlayerAccount(player(accountID));
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return createAccount(accountID, name, worldName, true);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        Economy economy = source.get();
        if (economy == null || accountID == null || !player) return false;
        return worldName == null ? economy.createPlayerAccount(player(accountID))
                : economy.createPlayerAccount(player(accountID), worldName);
    }

    /** Vault v1 cannot list accounts. */
    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return Map.of();
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        if (accountID == null) return Optional.empty();
        return Optional.ofNullable(player(accountID).getName());
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        Economy economy = source.get();
        return economy != null && accountID != null && economy.hasAccount(player(accountID));
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        Economy economy = source.get();
        if (economy == null || accountID == null) return false;
        return worldName == null ? economy.hasAccount(player(accountID)) : economy.hasAccount(player(accountID), worldName);
    }

    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return false;
    }

    @Override
    public boolean renameAccount(String pluginName, UUID accountID, String name) {
        return false;
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID accountID) {
        return false;
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return hasCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return hasCurrency(currency);
    }

    // ------------------------------------------------------------------ balances and writes

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        return read(accountID, null, null);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return read(accountID, world, null);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return read(accountID, world, currency);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        return covers(accountID, null, null, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return covers(accountID, worldName, null, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return covers(accountID, worldName, currency, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        return write(accountID, null, null, amount, false);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write(accountID, worldName, null, amount, false);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return write(accountID, worldName, currency, amount, false);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        return write(accountID, null, null, amount, true);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return write(accountID, worldName, null, amount, true);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return write(accountID, worldName, currency, amount, true);
    }

    // ------------------------------------------------------------------ shared accounts: not supported by v1

    @Override
    public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
        return false;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid,
                                    net.milkbowl.vault2.economy.AccountPermission... initialPermissions) {
        return false;
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return false;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid,
                                        net.milkbowl.vault2.economy.AccountPermission permission) {
        return false;
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid,
                                           net.milkbowl.vault2.economy.AccountPermission permission, boolean value) {
        return false;
    }
}
