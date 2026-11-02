package dev.yanianz.atrium.module.proxy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bounded FIFO of encoded envelopes waiting for a carrier player.
 *
 * <p>When the queue is full the oldest entry is dropped, because stale network state is less useful than
 * the message that just arrived. Entries also expire after {@code ttlMillis}. Synchronised: publishes
 * come from any thread and the flush runs on a player's region thread.
 */
final class OutboundQueue {
    private final int maxSize;
    private final long ttlMillis;
    private final Deque<Pending> queue = new ArrayDeque<>();
    private long dropped;
    private long expired;

    record Pending(byte[] data, long expiresAt) {
        Pending {
            data = data.clone();
        }
    }

    OutboundQueue(int maxSize, long ttlMillis) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        this.maxSize = maxSize;
        this.ttlMillis = ttlMillis;
    }

    /** Adds one message, evicting the oldest entry when the queue is full. */
    synchronized void offer(byte[] data, long now) {
        prune(now);
        while (queue.size() >= maxSize) {
            queue.removeFirst();
            dropped++;
        }
        queue.addLast(new Pending(data, now + ttlMillis));
    }

    /** Removes and returns every message that has not expired yet. */
    synchronized List<byte[]> drain(long now) {
        prune(now);
        List<byte[]> out = new ArrayList<>(queue.size());
        for (Pending pending : queue) {
            out.add(pending.data().clone());
        }
        queue.clear();
        return out;
    }

    synchronized int size(long now) {
        prune(now);
        return queue.size();
    }

    synchronized long dropped() {
        return dropped;
    }

    synchronized long expired() {
        return expired;
    }

    private void prune(long now) {
        while (!queue.isEmpty() && queue.peekFirst().expiresAt() <= now) {
            queue.removeFirst();
            expired++;
        }
    }
}
