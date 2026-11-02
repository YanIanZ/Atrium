package dev.yanianz.atrium.vault;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs at most one message per key and interval; the rest are counted and reported with the next message.
 * Lock-free apart from the first insertion of a key. Keys must come from a small, fixed vocabulary
 * (operation + error class), never from player names.
 */
public final class RateLimitedLog {
    private static final long NEVER = Long.MIN_VALUE;

    private final Logger logger;
    private final LongSupplier nanoClock;
    private final long intervalNanos;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        final AtomicLong last = new AtomicLong(NEVER);
        final AtomicLong suppressed = new AtomicLong();
    }

    public RateLimitedLog(Logger logger, long intervalMillis) {
        this(logger, intervalMillis, System::nanoTime);
    }

    RateLimitedLog(Logger logger, long intervalMillis, LongSupplier nanoClock) {
        this.logger = logger;
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, intervalMillis));
        this.nanoClock = nanoClock;
    }

    /** Logs {@code message} (and {@code error}, if any) unless the same key was logged within the interval. */
    public boolean log(Level level, String key, String message, Throwable error) {
        Window window = windows.computeIfAbsent(key, k -> new Window());
        long now = nanoClock.getAsLong();
        long last = window.last.get();
        if ((last != NEVER && now - last < intervalNanos) || !window.last.compareAndSet(last, now)) {
            window.suppressed.incrementAndGet();
            return false;
        }
        long suppressed = window.suppressed.getAndSet(0);
        String text = suppressed > 0 ? message + " (" + suppressed + " similar message(s) suppressed)" : message;
        if (error == null) logger.log(level, text);
        else logger.log(level, text, error);
        return true;
    }
}
