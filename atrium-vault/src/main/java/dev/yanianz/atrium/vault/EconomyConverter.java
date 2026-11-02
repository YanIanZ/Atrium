package dev.yanianz.atrium.vault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * Copies balances from one economy provider to another. The source is only ever read ({@code hasAccount},
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
