package dev.yanianz.atrium.module.teleport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Pending /tpa and /tpahere requests. Thread-safe (all methods synchronized; the book is small).
 *
 * <ul>
 *   <li>At most one request per (sender, receiver) pair; a new one replaces the old one.</li>
 *   <li>Accepting or denying <em>by name</em> only ever touches that sender's request; there is no
 *       fallback to someone else's request.</li>
 *   <li>Only the request that is acted upon is removed; other requests of the same sender stay.</li>
 *   <li>Expired requests are never returned, and are handed out once by {@link #expire()}.</li>
 * </ul>
 */
final class RequestBook {
    enum Type {
        /** The sender wants to go to the receiver. */
        TPA,
        /** The sender wants the receiver to come to them. */
        TPAHERE
    }

    record Request(UUID sender, UUID receiver, Type type, Instant created, Instant expires) {
        /** Who moves when the request is accepted. */
        UUID traveller() {
            return type == Type.TPA ? sender : receiver;
        }

        /** Whose location is the destination. */
        UUID destination() {
            return type == Type.TPA ? receiver : sender;
        }
    }

    private final List<Request> requests = new ArrayList<>();
    private final Clock clock;
    private final Supplier<Duration> lifetime;

    RequestBook(Clock clock, Supplier<Duration> lifetime) {
        this.clock = clock;
        this.lifetime = lifetime;
    }

    /** Adds (or replaces) the request from sender to receiver. Returns the stored request. */
    synchronized Request add(UUID sender, UUID receiver, Type type) {
        requests.removeIf(r -> r.sender().equals(sender) && r.receiver().equals(receiver));
        Instant now = clock.instant();
        Request request = new Request(sender, receiver, type, now, now.plus(lifetime.get()));
        requests.add(request);
        return request;
    }

    /** True when sender has an unexpired request of the same type to receiver. */
    synchronized boolean hasPending(UUID sender, UUID receiver, Type type) {
        Instant now = clock.instant();
        return requests.stream().anyMatch(r -> r.sender().equals(sender) && r.receiver().equals(receiver)
                && r.type() == type && r.expires().isAfter(now));
    }

    /**
     * Removes and returns the receiver's request from {@code sender}, or, when {@code sender} is empty,
     * the newest request to the receiver.
     */
    synchronized Optional<Request> take(UUID receiver, Optional<UUID> sender) {
        Instant now = clock.instant();
        Optional<Request> found = requests.stream()
                .filter(r -> r.receiver().equals(receiver) && r.expires().isAfter(now))
                .filter(r -> sender.isEmpty() || r.sender().equals(sender.get()))
                .max(Comparator.comparing(Request::created));
        found.ifPresent(requests::remove);
        return found;
    }

    /** Removes and returns the sender's outgoing request to {@code receiver}, or the newest one. */
    synchronized Optional<Request> cancel(UUID sender, Optional<UUID> receiver) {
        Instant now = clock.instant();
        Optional<Request> found = requests.stream()
                .filter(r -> r.sender().equals(sender) && r.expires().isAfter(now))
                .filter(r -> receiver.isEmpty() || r.receiver().equals(receiver.get()))
                .max(Comparator.comparing(Request::created));
        found.ifPresent(requests::remove);
        return found;
    }

    /** Unexpired requests addressed to the receiver, newest first. */
    synchronized List<Request> incoming(UUID receiver) {
        Instant now = clock.instant();
        return requests.stream()
                .filter(r -> r.receiver().equals(receiver) && r.expires().isAfter(now))
                .sorted(Comparator.comparing(Request::created).reversed())
                .toList();
    }

    /** Unexpired requests sent by the sender, newest first. */
    synchronized List<Request> outgoing(UUID sender) {
        Instant now = clock.instant();
        return requests.stream()
                .filter(r -> r.sender().equals(sender) && r.expires().isAfter(now))
                .sorted(Comparator.comparing(Request::created).reversed())
                .toList();
    }

    /** Removes expired requests and returns them (each exactly once) so both sides can be told. */
    synchronized List<Request> expire() {
        Instant now = clock.instant();
        List<Request> expired = requests.stream().filter(r -> !r.expires().isAfter(now)).toList();
        requests.removeAll(expired);
        return expired;
    }

    /** Removes every request from or to the player (quit). */
    synchronized List<Request> removeAll(UUID player) {
        List<Request> removed = requests.stream()
                .filter(r -> r.sender().equals(player) || r.receiver().equals(player))
                .toList();
        requests.removeAll(removed);
        return removed;
    }

    synchronized int size() {
        return requests.size();
    }
}
