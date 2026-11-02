package dev.yanianz.atrium.api.moderation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Applying, lifting and querying punishments. Published by the {@code moderation} module. Safe to call from
 * any thread; every method that touches storage returns a future.
 *
 * <p>{@link #punish} and the {@code revoke*} methods fire their cancellable event ({@link PunishmentEvent},
 * {@link PunishmentRevokeEvent}) <em>before</em> anything is stored or enforced, and do nothing when it is
 * cancelled. The event is synchronous when called from a server thread and asynchronous otherwise.
 */
public interface PunishmentService {
    /**
     * Stores and enforces a punishment (kicks banned players, teleports jailed players, ...). An active
     * punishment of the same group (see {@link PunishmentType#group()}) is replaced.
     */
    CompletableFuture<PunishResult> punish(PunishmentRequest request);

    /** Lifts the active punishments of the given kinds of an account (e.g. {@link PunishmentType#BANS}). */
    CompletableFuture<RevokeResult> revoke(UUID target, Set<PunishmentType> types, @Nullable UUID actor, String actorName,
            boolean silent);

    /**
     * Lifts active IP bans whose address/range equals {@code ipOrRange} (after normalisation). An account ban of
     * the same player is not touched, and unbanning an account never lifts IP bans.
     */
    CompletableFuture<RevokeResult> revokeIp(String ipOrRange, @Nullable UUID actor, String actorName, boolean silent);

    /** Lifts one record by id (e.g. a single warning). */
    CompletableFuture<RevokeResult> revokeById(long id, @Nullable UUID actor, String actorName, boolean silent);

    /** Every record of an account, newest first. */
    CompletableFuture<List<Punishment>> history(UUID target, int offset, int limit);

    CompletableFuture<Integer> historyCount(UUID target);

    /** Active records of an account, read from storage (exact also for offline players). */
    CompletableFuture<List<Punishment>> active(UUID target);

    /** The active IP ban matching an address (exact address or a banned range containing it). */
    CompletableFuture<Optional<Punishment>> activeIpBan(String address);

    /**
     * Active record of one of the kinds from memory, without storage access. Covers online players and
     * players punished on this server recently.
     */
    Optional<Punishment> cachedActive(UUID player, Set<PunishmentType> types);
}
