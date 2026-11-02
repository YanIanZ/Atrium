package dev.yanianz.atrium.vault;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;

/**
 * Exposes the best native Vault v2 economy as a Vault v1 ({@code net.milkbowl.vault.economy.Economy}) provider, for
 * servers whose economy plugin only speaks v2. Registered by {@link BridgeManager} only while no native v1 provider
 * exists.
 *
 * <ul>
 *   <li>{@link OfflinePlayer}s are used by UUID. Legacy name overloads resolve the name without blocking (online
 *       players, then the server's profile cache); an unknown name has no account.</li>
 *   <li>Double amounts must be finite and non-negative and are rounded HALF_EVEN to the provider's
 *       {@code fractionalDigits}: a double such as {@code 0.1 + 0.2} is meant as 0.30, not 0.30000000000000004.</li>
 *   <li>The v2 {@code pluginName} parameter is the calling plugin (stack walk), or {@code Vault} when unknown.</li>
 *   <li>Banks are not bridged.</li>
 * </ul>
 */
@SuppressWarnings("deprecation") // v1 String overloads and deprecated v2 overloads are part of the contracts
public final class V2AsV1Economy implements Economy, BridgedEconomy {
    static final String NO_PROVIDER = "No Vault v2 economy provider is registered";
    static final String FALLBACK_PLUGIN = "Vault";

    private final Supplier<net.milkbowl.vault2.economy.Economy> source;
    private final Function<String, UUID> names;
    private final CallerResolver callers;

    public V2AsV1Economy(Supplier<net.milkbowl.vault2.economy.Economy> source, Function<String, UUID> names,
                         CallerResolver callers) {
        this.source = source;
        this.names = names;
        this.callers = callers == null ? CallerResolver.NONE : callers;
    }

    public net.milkbowl.vault2.economy.Economy source() {
        return source.get();
    }

    @Override
    public String bridgeDescription() {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        return "bridge v2->v1 of " + (economy == null ? "nothing" : GuardedEconomyV2.safeName(economy));
    }

    // ------------------------------------------------------------------ conversions

    private String plugin() {
        String caller = callers.caller();
        return caller == null || caller.isBlank() ? FALLBACK_PLUGIN : caller;
    }

    private static int digits(net.milkbowl.vault2.economy.Economy economy, String plugin) {
        try {
            return economy.fractionalDigits(plugin);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String invalid(double amount) {
        if (Double.isNaN(amount)) return "Amount is NaN";
        if (Double.isInfinite(amount)) return "Amount is infinite";
        if (amount < 0) return "Amount must not be negative";
        return null;
    }

    private static EconomyResponse failure(String message) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, message);
    }

    static EconomyResponse convert(net.milkbowl.vault2.economy.EconomyResponse response) {
        if (response == null || response.type == null) return failure("Economy provider returned no response");
        ResponseType type = switch (response.type) {
            case SUCCESS -> ResponseType.SUCCESS;
            case NOT_IMPLEMENTED -> ResponseType.NOT_IMPLEMENTED;
            default -> ResponseType.FAILURE;
        };
        String message = response.errorMessage == null || response.errorMessage.isEmpty() ? null : response.errorMessage;
        return new EconomyResponse(response.amount == null ? 0 : response.amount.doubleValue(),
                response.balance == null ? 0 : response.balance.doubleValue(), type, message);
    }

    private UUID uuid(String name) {
        return name == null || name.isBlank() ? null : names.apply(name);
    }

    private static String nameOf(OfflinePlayer player) {
        String name = player.getName();
        return name != null ? name : player.getUniqueId().toString();
    }

    private EconomyResponse write(UUID id, String world, double amount, boolean deposit) {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        if (economy == null) return failure(NO_PROVIDER);
        if (id == null) return failure("Unknown player");
        String problem = invalid(amount);
        if (problem != null) return failure(problem);
        String plugin = plugin();
        BigDecimal value = Decimals.fromDouble(amount, digits(economy, plugin));
        net.milkbowl.vault2.economy.EconomyResponse response;
        if (deposit) {
            response = world == null ? economy.deposit(plugin, id, value) : economy.deposit(plugin, id, world, value);
        } else {
            response = world == null ? economy.withdraw(plugin, id, value) : economy.withdraw(plugin, id, world, value);
        }
        return convert(response);
    }

    private double balance(UUID id, String world) {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        if (economy == null || id == null) return 0;
        String plugin = plugin();
        BigDecimal balance = world == null ? economy.balance(plugin, id) : economy.balance(plugin, id, world);
        return balance == null ? 0 : balance.doubleValue();
    }

    private boolean has(UUID id, String world, double amount) {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        if (economy == null || id == null || invalid(amount) != null) return false;
        String plugin = plugin();
        BigDecimal value = Decimals.fromDouble(amount, digits(economy, plugin));
        return world == null ? economy.has(plugin, id, value) : economy.has(plugin, id, world, value);
    }

    private boolean exists(UUID id, String world) {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        if (economy == null || id == null) return false;
        return world == null ? economy.hasAccount(id) : economy.hasAccount(id, world);
    }

    private boolean create(UUID id, String name, String world) {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        if (economy == null || id == null) return false;
        String account = name == null ? id.toString() : name;
        return world == null ? economy.createAccount(id, account, true) : economy.createAccount(id, account, world, true);
    }

    // ------------------------------------------------------------------ metadata

    @Override
    public boolean isEnabled() {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        return economy != null && economy.isEnabled();
    }

    @Override
    public String getName() {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        return economy == null ? "None" : GuardedEconomyV2.safeName(economy);
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        return economy == null ? -1 : digits(economy, plugin());
    }

    @Override
    public String format(double amount) {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        if (economy == null || !Double.isFinite(amount)) return String.valueOf(amount);
        return economy.format(plugin(), BigDecimal.valueOf(amount));
    }

    @Override
    public String currencyNamePlural() {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        return economy == null ? "" : economy.defaultCurrencyNamePlural(plugin());
    }

    @Override
    public String currencyNameSingular() {
        net.milkbowl.vault2.economy.Economy economy = source.get();
        return economy == null ? "" : economy.defaultCurrencyNameSingular(plugin());
    }

    // ------------------------------------------------------------------ accounts and balances

    @Override public boolean hasAccount(String playerName) { return exists(uuid(playerName), null); }
    @Override public boolean hasAccount(OfflinePlayer player) { return player != null && exists(player.getUniqueId(), null); }
    @Override public boolean hasAccount(String playerName, String worldName) { return exists(uuid(playerName), worldName); }
    @Override public boolean hasAccount(OfflinePlayer player, String worldName) {
        return player != null && exists(player.getUniqueId(), worldName);
    }

    @Override public double getBalance(String playerName) { return balance(uuid(playerName), null); }
    @Override public double getBalance(OfflinePlayer player) { return player == null ? 0 : balance(player.getUniqueId(), null); }
    @Override public double getBalance(String playerName, String world) { return balance(uuid(playerName), world); }
    @Override public double getBalance(OfflinePlayer player, String world) {
        return player == null ? 0 : balance(player.getUniqueId(), world);
    }

    @Override public boolean has(String playerName, double amount) { return has(uuid(playerName), null, amount); }
    @Override public boolean has(OfflinePlayer player, double amount) { return player != null && has(player.getUniqueId(), null, amount); }
    @Override public boolean has(String playerName, String worldName, double amount) { return has(uuid(playerName), worldName, amount); }
    @Override public boolean has(OfflinePlayer player, String worldName, double amount) {
        return player != null && has(player.getUniqueId(), worldName, amount);
    }

    @Override public EconomyResponse withdrawPlayer(String playerName, double amount) { return write(uuid(playerName), null, amount, false); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        return write(player == null ? null : player.getUniqueId(), null, amount, false);
    }
    @Override public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return write(uuid(playerName), worldName, amount, false);
    }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return write(player == null ? null : player.getUniqueId(), worldName, amount, false);
    }

    @Override public EconomyResponse depositPlayer(String playerName, double amount) { return write(uuid(playerName), null, amount, true); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        return write(player == null ? null : player.getUniqueId(), null, amount, true);
    }
    @Override public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return write(uuid(playerName), worldName, amount, true);
    }
    @Override public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return write(player == null ? null : player.getUniqueId(), worldName, amount, true);
    }

    @Override public boolean createPlayerAccount(String playerName) { return create(uuid(playerName), playerName, null); }
    @Override public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null && create(player.getUniqueId(), nameOf(player), null);
    }
    @Override public boolean createPlayerAccount(String playerName, String worldName) {
        return create(uuid(playerName), playerName, worldName);
    }
    @Override public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return player != null && create(player.getUniqueId(), nameOf(player), worldName);
    }

    // ------------------------------------------------------------------ banks: not bridged

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "Banks are not supported by the Vault v2 bridge");
    }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }
}
