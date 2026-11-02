package dev.yanianz.atrium.api.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only account lookup published by the {@code economy} module next to {@link EconomyService}.
 *
 * <p>{@link EconomyService#balance(UUID)} reports the starting balance for an account that was never
 * created, so it cannot tell "no account" from "an account holding the starting balance". Import and
 * maintenance tools use this to decide on account existence instead of on the balance value.
 */
public interface EconomyAccounts {
    /** Whether an account row is stored for this UUID. Never creates one. */
    CompletableFuture<Boolean> hasAccount(UUID account);
}
