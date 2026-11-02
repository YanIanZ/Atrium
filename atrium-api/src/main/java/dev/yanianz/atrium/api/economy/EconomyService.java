package dev.yanianz.atrium.api.economy;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Atrium's economy. Amounts are exact decimals; every change is a single atomic storage operation,
 * so a failed write never leaves the cached balance changed.
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
}
