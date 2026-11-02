package dev.yanianz.atrium.module.proxy;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, expiring set of nonces used to reject replayed envelopes. Entries expire after
 * {@code ttlMillis}; when the cache is full the oldest entry is evicted, so memory stays bounded even
 * under a flood. Synchronised: the plugin-message listener and the async dispatcher may both touch it.
 *
 * <p>Evicting an unexpired nonce would make that envelope replayable again, so the eviction raises a
 * watermark to the evicted envelope's timestamp and every older envelope is refused from then on. The
 * cache therefore never forgets a nonce while its message is still inside the trust window, even when a
 * flood pushes entries out.
 */
final class ReplayGuard {
    /** Extra slack on top of twice the trust window for clock granularity and scheduling delay. */
    private static final long TTL_MARGIN_MILLIS = 5_000L;

    private final int maxEntries;
    private final long ttlMillis;
    private final LinkedHashMap<String, Entry> seen = new LinkedHashMap<>();
    private long minimumTimestamp = Long.MIN_VALUE;

    ReplayGuard(int maxEntries, long ttlMillis) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttlMillis = ttlMillis;
    }

    /**
     * The nonce TTL must cover the whole interval in which an envelope is still accepted: an envelope
     * first seen at {@code timestamp - window} stays valid until {@code timestamp + window}, which is
     * {@code 2 * window} later. A shorter TTL would let it be replayed after its nonce was forgotten.
     */
    static long effectiveTtlMillis(long configuredTtlMillis, long trustWindowMillis) {
        long minimum = 2L * Math.max(0L, trustWindowMillis) + TTL_MARGIN_MILLIS;
        return Math.max(Math.max(1L, configuredTtlMillis), minimum);
    }

    /**
     * Records the nonce.
     *
     * @param timestamp envelope timestamp, used as the eviction watermark
     * @param now       current epoch millis
     * @return {@code true} when it is new, {@code false} when it is a replay (still inside the TTL) or
     *     older than an evicted nonce
     */
    synchronized boolean accept(byte[] nonce, long timestamp, long now) {
        if (nonce == null || nonce.length != ProxyEnvelope.NONCE_BYTES) {
            return false;
        }
        prune(now);
        if (timestamp <= minimumTimestamp) {
            return false;
        }
        String key = ProxyEnvelope.hex(nonce);
        Entry known = seen.get(key);
        if (known != null && known.expiresAt() > now) {
            return false;
        }
        seen.put(key, new Entry(timestamp, now + ttlMillis));
        while (seen.size() > maxEntries) {
            Iterator<Map.Entry<String, Entry>> it = seen.entrySet().iterator();
            Map.Entry<String, Entry> evicted = it.next();
            it.remove();
            minimumTimestamp = Math.max(minimumTimestamp, evicted.getValue().timestamp());
        }
        return true;
    }

    synchronized int size(long now) {
        prune(now);
        return seen.size();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Entry>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt() <= now) {
                it.remove();
            }
        }
    }

    private record Entry(long timestamp, long expiresAt) {}
}
