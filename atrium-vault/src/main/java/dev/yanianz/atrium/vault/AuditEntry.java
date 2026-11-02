package dev.yanianz.atrium.vault;

/**
 * One withdraw/deposit (v1) or withdraw/deposit/set/transfer (v2) attempt. {@code account} is a {@link String} name or an {@link org.bukkit.OfflinePlayer};
 * it is rendered on the writer thread so the calling thread only allocates this record.
 *
 * @param epochMillis wall-clock time of the call
 * @param operation   e.g. {@code withdrawPlayer}
 * @param account     the account argument as passed by the caller
 * @param world       the world argument, or {@code null}
 * @param amount      the requested amount: a {@link Double} (Vault v1) or an exact {@link java.math.BigDecimal} (v2)
 * @param result      SUCCESS, FAILURE, NOT_IMPLEMENTED or REJECTED
 * @param balance     balance reported by the provider (0 when rejected), Double or BigDecimal
 * @param caller      calling plugin, or {@code null}
 * @param message     provider error message or rejection reason, or {@code null}
 */
public record AuditEntry(long epochMillis, String operation, Object account, String world, Number amount,
                         String result, Number balance, String caller, String message) {
}
