package dev.yanianz.atrium.vault;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * Copies balances from one economy provider to another. {@link #convert(Economy, Economy, Collection, boolean)} is the
 * Vault v1 to v1 path (double arithmetic with a decimal delta); {@link #convert(Book, Book, Collection, boolean)} works
 * between any two {@link Book}s, v1 or v2, in exact decimal: exact end to end when both sides are v2.
 * The source is only ever read ({@code hasAccount},
 * {@code getBalance}); the target is moved onto the source balance with a single deposit or withdrawal of the
 * difference, then read back to verify. Must run off the tick threads: providers may block on storage.
 */
public final class EconomyConverter {
    private static final int MAX_REPORTED_FAILURES = 10;

    private EconomyConverter() {
    }

    /**
     * @param players      accounts considered
     * @param unchanged    target already equal to source
     * @param deposits     deposits planned (dry run) or done
     * @param withdrawals  withdrawals planned (dry run) or done
     * @param skipped      no account in the source economy
     * @param failed       errors (provider failure, exception, verification mismatch, non-finite balance)
     * @param failures     first few failures, "player: reason"
     */
    public record Report(int players, int unchanged, int deposits, int withdrawals, int skipped, int failed,
                         List<String> failures) {
    }

    public static Report convert(Economy from, Economy to, Collection<? extends OfflinePlayer> players, boolean apply) {
        int unchanged = 0, deposits = 0, withdrawals = 0, skipped = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        int digits = safeDigits(to);
        for (OfflinePlayer player : players) {
            String problem;
            try {
                if (!from.hasAccount(player)) {
                    skipped++;
                    continue;
                }
                double source = from.getBalance(player);
                boolean exists = to.hasAccount(player);
                if (!exists && apply && !to.createPlayerAccount(player)) {
                    problem = "cannot create target account";
                } else {
                    double target = exists ? to.getBalance(player) : apply ? to.getBalance(player) : 0.0;
                    ConvertMath.Plan plan = ConvertMath.plan(source, target, digits);
                    switch (plan.action()) {
                        case NONE -> {
                            unchanged++;
                            continue;
                        }
                        case INVALID -> problem = "non-finite balance (source " + source + ", target " + target + ")";
                        default -> {
                            boolean deposit = plan.action() == ConvertMath.Action.DEPOSIT;
                            if (!apply) {
                                if (deposit) deposits++;
                                else withdrawals++;
                                continue;
                            }
                            EconomyResponse response = deposit ? to.depositPlayer(player, plan.amount())
                                    : to.withdrawPlayer(player, plan.amount());
                            if (response == null || !response.transactionSuccess()) {
                                problem = (deposit ? "deposit " : "withdraw ") + plan.amount() + " failed: "
                                        + (response == null ? "no response" : response.errorMessage);
                            } else {
                                double after = to.getBalance(player);
                                if (!ConvertMath.matches(source, after, digits)) {
                                    problem = "balance after write is " + after + ", expected " + source;
                                } else {
                                    if (deposit) deposits++;
                                    else withdrawals++;
                                    continue;
                                }
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                problem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            failed++;
            if (failures.size() < MAX_REPORTED_FAILURES) failures.add(describe(player) + ": " + problem);
        }
        return new Report(players.size(), unchanged, deposits, withdrawals, skipped, failed, List.copyOf(failures));
    }

    /** One side of a conversion: a Vault v1 or v2 economy seen through exact decimals. */
    public interface Book {
        /** For example {@code Atrium:v2}. */
        String label();

        /** The provider object (for "same economy" checks). */
        Object provider();

        boolean hasAccount(OfflinePlayer player);

        /** Current balance; throws {@link ArithmeticException} for a non-finite v1 balance. */
        BigDecimal balance(OfflinePlayer player);

        boolean createAccount(OfflinePlayer player);

        /** Deposits or withdraws {@code amount}; returns {@code null} on success, else the reason. */
        String change(OfflinePlayer player, BigDecimal amount, boolean deposit);

        int fractionalDigits();

        /** Whether {@code actual} is {@code expected} as this economy stores it. */
        boolean matches(BigDecimal expected, BigDecimal actual);
    }

    /** A Vault v1 economy: balances via doubles, verified within half a unit of its last digit. */
    public static Book v1(Economy economy, String label) {
        return new Book() {
            @Override public String label() { return label; }
            @Override public Object provider() { return economy; }
            @Override public boolean hasAccount(OfflinePlayer player) { return economy.hasAccount(player); }
            @Override public BigDecimal balance(OfflinePlayer player) { return Decimals.fromDouble(economy.getBalance(player), -1); }
            @Override public boolean createAccount(OfflinePlayer player) { return economy.createPlayerAccount(player); }
            @Override public String change(OfflinePlayer player, BigDecimal amount, boolean deposit) {
                double value = amount.doubleValue();
                EconomyResponse response = deposit ? economy.depositPlayer(player, value) : economy.withdrawPlayer(player, value);
                if (response != null && response.transactionSuccess()) return null;
                return (deposit ? "deposit " : "withdraw ") + amount.toPlainString() + " failed: "
                        + (response == null ? "no response" : response.errorMessage);
            }
            @Override public int fractionalDigits() { return safeDigits(economy); }
            @Override public boolean matches(BigDecimal expected, BigDecimal actual) {
                return ConvertMath.matches(expected.doubleValue(), actual.doubleValue(), fractionalDigits());
            }
        };
    }

    /** A Vault v2 economy: exact balances; {@code pluginName} is passed as the v2 plugin name. */
    public static Book v2(net.milkbowl.vault2.economy.Economy economy, String label, String pluginName) {
        return new Book() {
            @Override public String label() { return label; }
            @Override public Object provider() { return economy; }
            @Override public boolean hasAccount(OfflinePlayer player) { return economy.hasAccount(player.getUniqueId()); }
            @Override public BigDecimal balance(OfflinePlayer player) {
                BigDecimal balance = economy.balance(pluginName, player.getUniqueId());
                if (balance == null) throw new ArithmeticException("no balance");
                return balance;
            }
            @Override public boolean createAccount(OfflinePlayer player) {
                String name = player.getName();
                return economy.createAccount(player.getUniqueId(), name == null ? player.getUniqueId().toString() : name, true);
            }
            @Override public String change(OfflinePlayer player, BigDecimal amount, boolean deposit) {
                net.milkbowl.vault2.economy.EconomyResponse response = deposit
                        ? economy.deposit(pluginName, player.getUniqueId(), amount)
                        : economy.withdraw(pluginName, player.getUniqueId(), amount);
                if (response != null && response.transactionSuccess()) return null;
                return (deposit ? "deposit " : "withdraw ") + amount.toPlainString() + " failed: "
                        + (response == null ? "no response" : response.errorMessage);
            }
            @Override public int fractionalDigits() {
                try {
                    return economy.fractionalDigits(pluginName);
                } catch (RuntimeException e) {
                    return -1;
                }
            }
            @Override public boolean matches(BigDecimal expected, BigDecimal actual) {
                return expected.compareTo(actual) == 0;
            }
        };
    }

    /**
     * Exact conversion between two books. The source is only read; the target is moved onto the source balance (rounded
     * HALF_EVEN to the target's fractional digits) with one deposit or withdrawal of the exact difference, then read
     * back and verified with the target's {@link Book#matches}.
     */
    public static Report convert(Book from, Book to, Collection<? extends OfflinePlayer> players, boolean apply) {
        int unchanged = 0, deposits = 0, withdrawals = 0, skipped = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        int digits = to.fractionalDigits();
        for (OfflinePlayer player : players) {
            String problem;
            try {
                if (!from.hasAccount(player)) {
                    skipped++;
                    continue;
                }
                BigDecimal source = from.balance(player);
                boolean exists = to.hasAccount(player);
                if (!exists && apply && !to.createAccount(player)) {
                    problem = "cannot create target account";
                } else {
                    BigDecimal target = exists || apply ? to.balance(player) : BigDecimal.ZERO;
                    ConvertMath.ExactPlan plan = ConvertMath.plan(source, target, digits);
                    if (plan.action() == ConvertMath.Action.NONE || to.matches(plan.expected(), target)) {
                        unchanged++;
                        continue;
                    }
                    boolean deposit = plan.action() == ConvertMath.Action.DEPOSIT;
                    if (!apply) {
                        if (deposit) deposits++;
                        else withdrawals++;
                        continue;
                    }
                    problem = to.change(player, plan.amount(), deposit);
                    if (problem == null) {
                        BigDecimal after = to.balance(player);
                        if (to.matches(plan.expected(), after)) {
                            if (deposit) deposits++;
                            else withdrawals++;
                            continue;
                        }
                        problem = "balance after write is " + after.toPlainString() + ", expected "
                                + plan.expected().toPlainString();
                    }
                }
            } catch (ArithmeticException e) {
                problem = "non-finite or missing balance (" + e.getMessage() + ")";
            } catch (RuntimeException e) {
                problem = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            failed++;
            if (failures.size() < MAX_REPORTED_FAILURES) failures.add(describe(player) + ": " + problem);
        }
        return new Report(players.size(), unchanged, deposits, withdrawals, skipped, failed, List.copyOf(failures));
    }

    private static int safeDigits(Economy economy) {
        try {
            return economy.fractionalDigits();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String describe(OfflinePlayer player) {
        String name = null;
        try {
            name = player.getName();
        } catch (RuntimeException ignored) {
            // fall back to the UUID below
        }
        return name != null ? name : String.valueOf(player.getUniqueId());
    }
}
