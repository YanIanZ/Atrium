package dev.yanianz.atrium.module.discord;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One worker and one bounded queue per webhook URL. The worker is a virtual thread from the core I/O executor:
 * a send never blocks a game thread. Messages keep their order; a 429 re-queues the message at the front and a
 * 5xx/connection error is retried with exponential backoff.
 */
final class WebhookQueue implements Runnable {
    private static final String USER_AGENT = "Atrium-DiscordRelay/0.1 (+https://github.com/yanianz)";

    record WebhookMessage(String content, @Nullable String username, @Nullable String avatarUrl) {}

    record Stats(int queued, int capacity, long sent, long dropped, long failed, long retries,
            @Nullable String lastError) {}

    private final WebhookTarget target;
    private final HttpClient http;
    private final Logger logger;
    private final int capacity;
    private final int maxRetries;
    private final int maxRateLimitRetries;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;
    private final long maxRateLimitWaitMillis;
    private final Duration requestTimeout;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private final ArrayDeque<WebhookMessage> queue = new ArrayDeque<>();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();

    private volatile boolean open = true;
    private volatile Thread worker;
    private volatile @Nullable String lastError;
    private long nextAllowedAt;

    WebhookQueue(WebhookTarget target, HttpClient http, Logger logger, int capacity, int maxRetries,
            int maxRateLimitRetries, long baseBackoffMillis, long maxBackoffMillis, long maxRateLimitWaitMillis,
            Duration requestTimeout, LongSupplier clock) {
        this.target = target;
        this.http = http;
        this.logger = logger;
        this.capacity = Math.max(1, Math.min(1000, capacity));
        this.maxRetries = Math.max(0, Math.min(10, maxRetries));
        this.maxRateLimitRetries = Math.max(1, Math.min(20, maxRateLimitRetries));
        this.baseBackoffMillis = Math.max(100L, baseBackoffMillis);
        this.maxBackoffMillis = Math.max(this.baseBackoffMillis, maxBackoffMillis);
        this.maxRateLimitWaitMillis = Math.max(1000L, Math.min(900_000L, maxRateLimitWaitMillis));
        this.requestTimeout = requestTimeout;
        this.clock = clock;
    }

    WebhookTarget target() {
        return target;
    }

    /** Starts the worker on the given executor (the core I/O executor). */
    void start(Executor executor) {
        executor.execute(this);
    }

    /** Non-blocking: the event thread only appends to the deque and notifies the worker. */
    void enqueue(WebhookMessage message) {
        synchronized (lock) {
            if (!open) {
                dropped.incrementAndGet();
                return;
            }
            if (queue.size() >= capacity) {
                queue.removeFirst();
                dropped.incrementAndGet();
            }
            queue.addLast(message);
            lock.notifyAll();
        }
    }

    Stats stats() {
        int size;
        synchronized (lock) {
            size = queue.size();
        }
        return new Stats(size, capacity, sent.get(), dropped.get(), failed.get(), retries.get(), lastError);
    }

    /** Stops the worker and counts queued messages as dropped. Idempotent. */
    void close() {
        Thread thread;
        synchronized (lock) {
            if (!open) {
                return;
            }
            open = false;
            dropped.addAndGet(queue.size());
            queue.clear();
            lock.notifyAll();
            thread = worker;
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        try {
            while (true) {
                WebhookMessage message;
                synchronized (lock) {
                    while (open && queue.isEmpty()) {
                        lock.wait();
                    }
                    if (queue.isEmpty()) {
                        return;
                    }
                    message = queue.pollFirst();
                }
                try {
                    deliver(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // Never log the throwable: URI-carrying exceptions can include the webhook token.
                    failed.incrementAndGet();
                    lastError = "internal " + e.getClass().getSimpleName();
                    logger.error("Discord webhook {} worker recovered from {}", target.masked(),
                            e.getClass().getName());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            lastError = "worker " + e.getClass().getSimpleName();
            logger.error("Discord webhook {} worker stopped ({})", target.masked(), e.getClass().getName());
        } finally {
            synchronized (lock) {
                open = false;
                dropped.addAndGet(queue.size());
                queue.clear();
            }
            worker = null;
        }
    }

    private void deliver(WebhookMessage message) throws InterruptedException {
        int attempt = 0;
        int limited = 0;
        while (true) {
            if (!open) {
                dropped.incrementAndGet();
                return;
            }
            awaitRateLimit();
            Attempt result = send(message);
            if (result.success()) {
                sent.incrementAndGet();
                lastError = null;
                return;
            }
            lastError = result.error();
            if (result.rateLimited()) {
                limited++;
                if (limited > maxRateLimitRetries) {
                    failed.incrementAndGet();
                    logger.warn("Discord webhook {} still rate limited after {} attempts", target.masked(), limited);
                    return;
                }
                nextAllowedAt = Math.max(nextAllowedAt,
                        RateLimit.nextAllowedAt(clock.getAsLong(), result.delayMillis()));
                retries.incrementAndGet();
                // Stay with this message so order is kept; awaitRateLimit at the top of the loop waits.
                continue;
            }
            if (result.retryable() && attempt < maxRetries) {
                long delay = RateLimit.backoffMillis(attempt, baseBackoffMillis, maxBackoffMillis);
                attempt++;
                retries.incrementAndGet();
                Thread.sleep(delay);
                continue;
            }
            failed.incrementAndGet();
            logger.warn("Discord webhook {} dropped a message after {} attempt(s): {}", target.masked(),
                    attempt + 1, result.error());
            return;
        }
    }

    private void awaitRateLimit() throws InterruptedException {
        long wait = nextAllowedAt - clock.getAsLong();
        if (wait <= 0) {
            return;
        }
        Thread.sleep(Math.min(wait, maxRateLimitWaitMillis));
    }

    private Attempt send(WebhookMessage message) throws InterruptedException {
        String json = DiscordJson.payload(message.content(), message.username(), message.avatarUrl());
        HttpRequest request = HttpRequest.newBuilder(target.uri())
                .timeout(requestTimeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException | RuntimeException e) {
            // Class name only: the message of a low-level I/O exception can contain the request URI.
            return Attempt.retryable("I/O " + e.getClass().getSimpleName());
        }

        int code = response.statusCode();
        Instant now = Instant.ofEpochMilli(clock.getAsLong());
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        String resetAfter = response.headers().firstValue("X-RateLimit-Reset-After").orElse(null);
        String resetAt = response.headers().firstValue("X-RateLimit-Reset").orElse(null);
        String remaining = response.headers().firstValue("X-RateLimit-Remaining").orElse(null);

        if (code >= 200 && code < 300) {
            long reset = RateLimit.resetAfterMillis(resetAfter);
            if (reset < 0) {
                reset = RateLimit.resetAtMillis(resetAt, clock.getAsLong());
            }
            if (RateLimit.remainingExhausted(remaining) && reset >= 0) {
                nextAllowedAt = Math.max(nextAllowedAt, RateLimit.nextAllowedAt(clock.getAsLong(), reset));
            }
            return Attempt.ok();
        }
        if (code == 429) {
            long delay = RateLimit.retryAfterMillis(retryAfter, now, -1L);
            if (delay < 0) {
                delay = RateLimit.resetAfterMillis(resetAfter);
            }
            if (delay < 0) {
                delay = RateLimit.resetAtMillis(resetAt, clock.getAsLong());
            }
            if (delay < 0) {
                delay = RateLimit.backoffMillis(0, baseBackoffMillis, maxBackoffMillis);
            }
            return Attempt.rateLimited(delay);
        }
        if (code >= 500 && code < 600) {
            return Attempt.retryable("HTTP " + code);
        }
        if (code == 408 || code == 425) {
            return Attempt.retryable("HTTP " + code);
        }
        return Attempt.permanent("HTTP " + code);
    }

    private record Attempt(boolean success, boolean rateLimited, boolean retryable, long delayMillis,
            @Nullable String error) {
        static Attempt ok() {
            return new Attempt(true, false, false, 0, null);
        }

        static Attempt rateLimited(long delayMillis) {
            return new Attempt(false, true, false, Math.max(0, delayMillis), "HTTP 429");
        }

        static Attempt retryable(String error) {
            return new Attempt(false, false, true, 0, error);
        }

        static Attempt permanent(String error) {
            return new Attempt(false, false, false, 0, error);
        }
    }
}
