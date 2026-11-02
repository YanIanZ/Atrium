package dev.yanianz.atrium.vault;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;

/** Minimal in-memory economy keyed by player name; can be told to throw. */
@SuppressWarnings("deprecation")
class MapEconomy extends AbstractEconomy {
    final String name;
    final Map<String, Double> balances = new ConcurrentHashMap<>();
    final AtomicInteger writes = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    volatile RuntimeException failWith;
    volatile boolean allowNegative;
    volatile int digits = 2;
    volatile long delayMillis;

    MapEconomy(String name) {
        this.name = name;
    }

    private void touch() {
        calls.incrementAndGet();
        RuntimeException failure = failWith;
        if (failure != null) throw failure;
        if (delayMillis > 0) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override public boolean isEnabled() { touch(); return true; }
    @Override public String getName() { return name; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return digits; }
    @Override public String format(double amount) { touch(); return "$" + amount; }
    @Override public String currencyNamePlural() { return "dollars"; }
    @Override public String currencyNameSingular() { return "dollar"; }
    @Override public boolean hasAccount(String player) { touch(); return balances.containsKey(player); }
    @Override public boolean hasAccount(String player, String world) { return hasAccount(player); }
    @Override public double getBalance(String player) { touch(); return balances.getOrDefault(player, 0.0); }
    @Override public double getBalance(String player, String world) { return getBalance(player); }
    @Override public boolean has(String player, double amount) { return getBalance(player) >= amount; }
    @Override public boolean has(String player, String world, double amount) { return has(player, amount); }

    @Override
    public EconomyResponse withdrawPlayer(String player, double amount) {
        touch();
        writes.incrementAndGet();
        double balance = balances.getOrDefault(player, 0.0);
        if (!allowNegative && balance < amount) return new EconomyResponse(0, balance, ResponseType.FAILURE, "Insufficient funds");
        double after = balance - amount;
        balances.put(player, after);
        return new EconomyResponse(amount, after, ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse withdrawPlayer(String player, String world, double amount) { return withdrawPlayer(player, amount); }

    @Override
    public EconomyResponse depositPlayer(String player, double amount) {
        touch();
        writes.incrementAndGet();
        double after = balances.merge(player, amount, Double::sum);
        return new EconomyResponse(amount, after, ResponseType.SUCCESS, null);
    }

    @Override public EconomyResponse depositPlayer(String player, String world, double amount) { return depositPlayer(player, amount); }

    private static EconomyResponse noBanks() { return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "no banks"); }
    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override
    public boolean createPlayerAccount(String player) {
        touch();
        writes.incrementAndGet();
        return balances.putIfAbsent(player, 0.0) == null;
    }

    @Override public boolean createPlayerAccount(String player, String world) { return createPlayerAccount(player); }
}
