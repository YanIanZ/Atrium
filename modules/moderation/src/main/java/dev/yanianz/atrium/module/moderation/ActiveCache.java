package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import dev.yanianz.atrium.api.moderation.PunishmentType;

/**
 * In-memory copy of the not-yet-revoked punishments of loaded players (filled before login, dropped at quit).
 * Lists are immutable and replaced atomically. Expiry is never "processed": readers compare
 * {@link Punishment#expires()} with the current time, so a record stops counting at the exact instant it expires.
 */
final class ActiveCache {
    private final Map<UUID, List<Punishment>> byPlayer = new ConcurrentHashMap<>();

    /** Replaces everything known about a player (login). Kicks and warnings are not kept. */
    void put(UUID player, Collection<Punishment> punishments) {
        List<Punishment> kept = punishments.stream()
                .filter(p -> player.equals(p.target()) && !p.type().isInstant() && p.type() != PunishmentType.WARN
                        && p.type() != PunishmentType.IPBAN && !p.isRevoked())
                .toList();
        byPlayer.put(player, kept);
    }

    boolean contains(UUID player) {
        return byPlayer.containsKey(player);
    }

    /** Adds a new record, dropping the records of the same group it replaced. */
    void add(Punishment punishment) {
        UUID player = punishment.target();
        if (player == null || punishment.type().isInstant() || punishment.type() == PunishmentType.WARN
                || punishment.type() == PunishmentType.IPBAN) {
            return;
        }
        Set<PunishmentType> group = punishment.type().group();
        byPlayer.compute(player, (k, old) -> {
            List<Punishment> next = new ArrayList<>();
            if (old != null) {
                old.stream().filter(p -> !group.contains(p.type())).forEach(next::add);
            }
            next.add(punishment);
            return List.copyOf(next);
        });
    }

    void remove(UUID player, Collection<Long> ids) {
        byPlayer.computeIfPresent(player, (k, old) -> old.stream().filter(p -> !ids.contains(p.id())).toList());
    }

    /** Drops every held record of the given kinds (remote unban / unmute). */
    void removeTypes(UUID player, Set<PunishmentType> types) {
        byPlayer.computeIfPresent(player, (k, old) -> old.stream().filter(p -> !types.contains(p.type())).toList());
    }

    void evict(UUID player) {
        byPlayer.remove(player);
    }

    /**
     * Pushes the expiry of every timed jail record of a player forward. Used when a sentence must not run
     * while the prisoner is offline or AFK: the in-memory copy moves with the stored row, so the next
     * movement check cannot release the player before the new deadline.
     */
    void extendJail(UUID player, java.time.Duration extra) {
        if (extra == null || extra.isZero() || extra.isNegative()) {
            return;
        }
        byPlayer.computeIfPresent(player, (k, old) -> old.stream()
                .map(p -> p.type() == PunishmentType.JAIL && p.expires() != null
                        ? new Punishment(p.id(), p.type(), p.target(), p.targetName(), p.ip(), p.actor(),
                                p.actorName(), p.reason(), p.created(), p.expires().plus(extra), p.revokedBy(),
                                p.revokedByName(), p.revokedAt(), p.context())
                        : p)
                .toList());
    }

    /** Snapshot of every record kept for a player (used for one-time expiry decisions). */
    List<Punishment> records(UUID player) {
        List<Punishment> list = byPlayer.get(player);
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Newest record of the kinds that is in effect at {@code now}. */
    Optional<Punishment> active(UUID player, Set<PunishmentType> types, Instant now) {
        List<Punishment> list = byPlayer.get(player);
        if (list == null) {
            return Optional.empty();
        }
        Punishment best = null;
        for (Punishment p : list) {
            if (types.contains(p.type()) && p.isActive(now) && (best == null || p.id() > best.id())) {
                best = p;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Records of the kinds that have expired by {@code now} but are still held (for one-time "expired" handling). */
    List<Punishment> expired(UUID player, Set<PunishmentType> types, Instant now) {
        List<Punishment> list = byPlayer.get(player);
        if (list == null) {
            return List.of();
        }
        return list.stream().filter(p -> types.contains(p.type()) && p.isExpired(now)).toList();
    }
}
