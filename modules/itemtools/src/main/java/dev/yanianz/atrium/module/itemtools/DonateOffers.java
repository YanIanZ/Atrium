package dev.yanianz.atrium.module.itemtools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory offer registry for {@code /donate}. Pure state machine over a generic payload, so it can be
 * unit tested without a running server.
 *
 * <p>Offers are keyed by receiver and donor. A new offer with the same fingerprint and amount refreshes
 * the existing offer; a different item is refused instead of silently replacing what the receiver was
 * told about.
 */
final class DonateOffers<T> {
    enum Status {
        CREATED,
        REFRESHED,
        PENDING,
        FULL
    }

    record Offer<T>(UUID donor, UUID receiver, T item, String fingerprint, int amount, long expiresAt) {
        Offer {
            Objects.requireNonNull(donor, "donor");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (amount <= 0) {
                throw new IllegalArgumentException("Donation amount must be positive");
            }
        }

        boolean expired(long now) {
            return expiresAt <= now;
        }
    }

    private final int maxPerReceiver;
    private final Map<UUID, Map<UUID, Offer<T>>> byReceiver = new HashMap<>();

    DonateOffers(int maxPerReceiver) {
        this.maxPerReceiver = Math.max(1, maxPerReceiver);
    }

    synchronized Status offer(UUID donor, UUID receiver, T item, String fingerprint, int amount,
            long now, long expiresAt) {
        Map<UUID, Offer<T>> offers = byReceiver.computeIfAbsent(receiver, ignored -> new HashMap<>());
        purge(offers, now);
        Offer<T> existing = offers.get(donor);
        if (existing != null) {
            if (existing.fingerprint().equals(fingerprint) && existing.amount() == amount) {
                offers.put(donor, new Offer<>(donor, receiver, item, fingerprint, amount, expiresAt));
                return Status.REFRESHED;
            }
            return Status.PENDING;
        }
        if (offers.size() >= maxPerReceiver) {
            return Status.FULL;
        }
        offers.put(donor, new Offer<>(donor, receiver, item, fingerprint, amount, expiresAt));
        return Status.CREATED;
    }

    synchronized List<Offer<T>> pending(UUID receiver, long now) {
        Map<UUID, Offer<T>> offers = byReceiver.get(receiver);
        if (offers == null) {
            return List.of();
        }
        purge(offers, now);
        if (offers.isEmpty()) {
            byReceiver.remove(receiver);
            return List.of();
        }
        return List.copyOf(offers.values());
    }

    /** Removes and returns one offer, or empty when it expired or does not exist. */
    synchronized Optional<Offer<T>> remove(UUID receiver, UUID donor, long now) {
        Map<UUID, Offer<T>> offers = byReceiver.get(receiver);
        if (offers == null) {
            return Optional.empty();
        }
        purge(offers, now);
        Offer<T> removed = offers.remove(donor);
        if (offers.isEmpty()) {
            byReceiver.remove(receiver);
        }
        return Optional.ofNullable(removed);
    }

    /** Removes every offer involving the player (as donor or receiver). */
    synchronized List<Offer<T>> removePlayer(UUID player) {
        List<Offer<T>> removed = new ArrayList<>();
        var entries = byReceiver.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getKey().equals(player)) {
                removed.addAll(entry.getValue().values());
                entries.remove();
                continue;
            }
            entry.getValue().values().removeIf(offer -> {
                if (offer.donor().equals(player)) {
                    removed.add(offer);
                    return true;
                }
                return false;
            });
            if (entry.getValue().isEmpty()) {
                entries.remove();
            }
        }
        return List.copyOf(removed);
    }

    synchronized int removeExpired(long now) {
        int[] removed = {0};
        byReceiver.values().removeIf(offers -> {
            int before = offers.size();
            offers.values().removeIf(offer -> offer.expired(now));
            removed[0] += before - offers.size();
            return offers.isEmpty();
        });
        return removed[0];
    }

    synchronized int pendingCount(UUID receiver, long now) {
        return pending(receiver, now).size();
    }

    synchronized void clear() {
        byReceiver.clear();
    }

    private static <T> void purge(Map<UUID, Offer<T>> offers, long now) {
        offers.values().removeIf(offer -> offer.expired(now));
    }
}
