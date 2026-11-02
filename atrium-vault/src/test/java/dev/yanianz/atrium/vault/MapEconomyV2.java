package dev.yanianz.atrium.vault;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;

/** Minimal in-memory Vault v2 economy keyed by UUID, exact decimals with {@link #digits} places; can throw. */
@SuppressWarnings("deprecation")
class MapEconomyV2 implements Economy {
    final String name;
    final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    final AtomicInteger writes = new AtomicInteger();
    final List<String> plugins = new CopyOnWriteArrayList<>();
    final List<BigDecimal> amounts = new CopyOnWriteArrayList<>();
    final List<String> worlds = new CopyOnWriteArrayList<>();
    volatile RuntimeException failWith;
    volatile int digits = 2;

    MapEconomyV2(String name) {
        this.name = name;
    }

    private void touch(String plugin) {
        if (plugin != null) plugins.add(plugin);
        RuntimeException failure = failWith;
        if (failure != null) throw failure;
    }

    private BigDecimal get(UUID id) {
        return balances.getOrDefault(id, BigDecimal.ZERO.setScale(digits));
    }

    @Override public boolean isEnabled() { touch(null); return true; }
    @Override public String getName() { return name; }
    @Override public boolean hasSharedAccountSupport() { return false; }
    @Override public boolean hasMultiCurrencySupport() { return false; }
    @Override public int fractionalDigits(String pluginName) { return digits; }
    @Override public String format(BigDecimal amount) { return "$" + amount.toPlainString(); }
    @Override public String format(String pluginName, BigDecimal amount) { return format(amount); }
    @Override public String format(BigDecimal amount, String currency) { return format(amount); }
    @Override public String format(String pluginName, BigDecimal amount, String currency) { return format(amount); }
    @Override public boolean hasCurrency(String currency) { return "coins".equalsIgnoreCase(currency); }
    @Override public String getDefaultCurrency(String pluginName) { return "coins"; }
    @Override public String defaultCurrencyNamePlural(String pluginName) { return "coins"; }
    @Override public String defaultCurrencyNameSingular(String pluginName) { return "coin"; }
    @Override public Collection<String> currencies() { return List.of("coins"); }
    @Override public boolean createAccount(UUID accountID, String name) { return createAccount(accountID, name, true); }
    @Override public boolean createAccount(UUID accountID, String name, boolean player) {
        touch(null);
        writes.incrementAndGet();
        return balances.putIfAbsent(accountID, BigDecimal.ZERO.setScale(digits)) == null;
    }
    @Override public boolean createAccount(UUID accountID, String name, String worldName) { return createAccount(accountID, name); }
    @Override public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        worlds.add(worldName);
        return createAccount(accountID, name, player);
    }
    @Override public Map<UUID, String> getUUIDNameMap() { return Map.of(); }
    @Override public Optional<String> getAccountName(UUID accountID) { return Optional.empty(); }
    @Override public boolean hasAccount(UUID accountID) { touch(null); return balances.containsKey(accountID); }
    @Override public boolean hasAccount(UUID accountID, String worldName) { worlds.add(worldName); return hasAccount(accountID); }
    @Override public boolean renameAccount(UUID accountID, String name) { return false; }
    @Override public boolean renameAccount(String pluginName, UUID accountID, String name) { return false; }
    @Override public boolean deleteAccount(String pluginName, UUID accountID) { return false; }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) { return hasCurrency(currency); }
    @Override public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) { return hasCurrency(currency); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID) { touch(pluginName); return get(accountID); }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        worlds.add(world);
        return getBalance(pluginName, accountID);
    }
    @Override public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return getBalance(pluginName, accountID, world);
    }
    @Override public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        touch(pluginName);
        amounts.add(amount);
        return get(accountID).compareTo(amount) >= 0;
    }
    @Override public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) { return has(pluginName, accountID, amount); }
    @Override public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) { return has(pluginName, accountID, amount); }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        touch(pluginName);
        writes.incrementAndGet();
        amounts.add(amount);
        BigDecimal value = amount.setScale(digits, RoundingMode.UNNECESSARY);
        BigDecimal balance = get(accountID);
        if (balance.compareTo(value) < 0) return new EconomyResponse(BigDecimal.ZERO, balance, ResponseType.FAILURE, "Insufficient funds");
        BigDecimal after = balance.subtract(value);
        balances.put(accountID, after);
        return new EconomyResponse(value, after, ResponseType.SUCCESS, "");
    }

    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        worlds.add(worldName);
        return withdraw(pluginName, accountID, amount);
    }
    @Override public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return withdraw(pluginName, accountID, worldName, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        touch(pluginName);
        writes.incrementAndGet();
        amounts.add(amount);
        BigDecimal value = amount.setScale(digits, RoundingMode.UNNECESSARY);
        BigDecimal after = balances.merge(accountID, value, BigDecimal::add);
        return new EconomyResponse(value, after, ResponseType.SUCCESS, "");
    }

    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        worlds.add(worldName);
        return deposit(pluginName, accountID, amount);
    }
    @Override public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        return deposit(pluginName, accountID, worldName, amount);
    }

    @Override public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) { return false; }
    @Override public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean setOwner(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) { return false; }
    @Override public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) { return false; }
    @Override public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) { return false; }
    @Override public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) { return false; }
}
