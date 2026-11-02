package dev.yanianz.atrium.api.economy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Atrium's economy. Amounts are exact decimals; every change is a single atomic storage operation,
 * so a failed write never leaves the cached balance changed.
 *
 * <p><b>World groups.</b> A server may keep separate balances per group of worlds. With groups disabled
 * (the default) there is exactly one group and every method behaves as it always did. With groups enabled
 * the methods without a group parameter use the account's <em>context group</em>: the group of the world
 * the player is in while online, otherwise the default group. {@link #transfer(UUID, UUID, BigDecimal, String)}
 * moves money inside the payer's context group (both sides in that group), so money never crosses groups
 * implicitly. Use the overloads with a {@code group} parameter to choose a group explicitly. Group names are
 * case-insensitive; an unknown group fails a read with {@link IllegalArgumentException} and a write with
 * {@link TransactionResult.Status#NO_ACCOUNT}.
 */
public interface EconomyService {
    CompletableFuture<BigDecimal> balance(UUID account);

    /** Balance cached for an online player, or {@code null} if not cached. */
    BigDecimal cachedBalance(UUID account);

    CompletableFuture<TransactionResult> deposit(UUID account, BigDecimal amount, String reason);

    CompletableFuture<TransactionResult> withdraw(UUID account, BigDecimal amount, String reason);

    CompletableFuture<TransactionResult> set(UUID account, BigDecimal amount, String reason);

    /** Moves money in one transaction: either both sides change or neither does. */
    CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount, String reason);

    String format(BigDecimal amount);

    String currencyNameSingular();

    String currencyNamePlural();

    // ---- world groups (additive; the defaults describe a server with a single group) ----

    /** Whether separate balances per world group are enabled. */
    default boolean groupsEnabled() { return false; }

    /** Name of the default group, used for unlisted worlds and offline players. */
    default String defaultGroup() { return "default"; }

    /** Configured group names, default group first. */
    default List<String> groups() { return List.of(defaultGroup()); }

    /** Group a world belongs to (the default group for unlisted worlds or when groups are disabled). */
    default String groupOfWorld(String world) { return defaultGroup(); }

    /** Context group of an account: its current world's group while online, otherwise the default group. */
    default String currentGroup(UUID account) { return defaultGroup(); }

    default CompletableFuture<BigDecimal> balance(UUID account, String group) { return balance(account); }

    /** Balance cached for an online player in a group, or {@code null} if not cached. */
    default BigDecimal cachedBalance(UUID account, String group) { return cachedBalance(account); }

    default CompletableFuture<TransactionResult> deposit(UUID account, String group, BigDecimal amount, String reason) {
        return deposit(account, amount, reason);
    }

    default CompletableFuture<TransactionResult> withdraw(UUID account, String group, BigDecimal amount, String reason) {
        return withdraw(account, amount, reason);
    }

    default CompletableFuture<TransactionResult> set(UUID account, String group, BigDecimal amount, String reason) {
        return set(account, amount, reason);
    }

    /** Moves money between two accounts inside one group, in one transaction. */
    default CompletableFuture<TransactionResult> transfer(UUID from, UUID to, String group, BigDecimal amount, String reason) {
        return transfer(from, to, amount, reason);
    }
}
