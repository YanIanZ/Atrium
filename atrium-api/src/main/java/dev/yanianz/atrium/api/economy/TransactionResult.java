package dev.yanianz.atrium.api.economy;

import java.math.BigDecimal;

/** Outcome of a balance change. {@code balance} is the balance after the attempt. */
public record TransactionResult(Status status, BigDecimal amount, BigDecimal balance) {
    public enum Status { SUCCESS, INSUFFICIENT_FUNDS, LIMIT_REACHED, NO_ACCOUNT, INVALID_AMOUNT, FAILED }

    public boolean success() {
        return status == Status.SUCCESS;
    }
}
