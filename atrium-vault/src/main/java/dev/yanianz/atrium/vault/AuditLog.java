package dev.yanianz.atrium.vault;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.logging.Level;
import org.bukkit.OfflinePlayer;

/**
 * Asynchronous, bounded audit log of economy writes.
 *
 * <p>{@link #record(AuditEntry)} is lock-free: a {@link ConcurrentLinkedQueue} bounded by an atomic counter. When the
 * queue is full the entry is dropped and counted (and the drop is logged, rate-limited) instead of blocking the caller.
 * A single virtual thread renders entries as tab-separated lines into {@code audit.log}, rotating it to
 * {@code audit-<timestamp>.log} when it exceeds the size limit or the UTC day changes, and keeping at most
 * {@code maxFiles} rotated files.
 */
public final class AuditLog implements AuditSink, AutoCloseable {
    static final String ACTIVE = "audit.log";
    private static final DateTimeFormatter ROTATED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final int BATCH = 512;

    private final Path dir;
    private final int capacity;
    private final long maxBytes;
    private final int maxFiles;
    private final RateLimitedLog log;
    private final ConcurrentLinkedQueue<AuditEntry> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder written = new LongAdder();
    private volatile boolean running;
    private Thread writer;

    // Writer-thread state only.
    private BufferedWriter out;
    private long bytes;
    private LocalDate day;
    private long droppedReported;

    public AuditLog(Path dir, int capacity, long maxBytes, int maxFiles, RateLimitedLog log) {
        this.dir = dir;
        this.capacity = Math.max(16, capacity);
        this.maxBytes = Math.max(64 * 1024, maxBytes);
        this.maxFiles = Math.max(1, maxFiles);
        this.log = log;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        writer = Thread.ofVirtual().name("Vault-audit-writer").start(this::run);
    }

    @Override
    public void record(AuditEntry entry) {
        if (!running) return;
        if (size.incrementAndGet() > capacity) {
            size.decrementAndGet();
            dropped.increment();
            return;
        }
        queue.offer(entry);
    }

    public int queued() {
        return size.get();
    }

    public long dropped() {
        return dropped.sum();
    }

    public long written() {
        return written.sum();
    }

    public Path directory() {
        return dir;
    }

    /** Stops accepting entries, drains what is queued and closes the file (waits up to 5 seconds). */
    @Override
    public void close() {
        Thread thread;
        synchronized (this) {
            if (!running) return;
            running = false;
            thread = writer;
        }
        LockSupport.unpark(thread);
        try {
            if (!thread.join(java.time.Duration.ofSeconds(5))) {
                log.log(Level.WARNING, "audit:close", "Audit writer did not finish within 5 s; "
                        + size.get() + " entries were not written", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // Anything still queued (a timed-out writer, or an offer that raced the final drain) is counted
        // as dropped instead of disappearing from the counters.
        int left = drainLeftovers();
        if (left > 0) {
            dropped.add(left);
            log.log(Level.WARNING, "audit:close", left + " audit entries were not written before close; "
                    + "counted as dropped", null);
        }
    }

    private void run() {
        List<AuditEntry> batch = new ArrayList<>(BATCH);
        while (true) {
            boolean stopping = !running;
            AuditEntry entry;
            while (batch.size() < BATCH && (entry = queue.poll()) != null) {
                size.decrementAndGet();
                batch.add(entry);
            }
            if (!batch.isEmpty()) {
                writeBatch(batch);
                batch.clear();
                continue;
            }
            reportDrops();
            if (stopping) break;
            flushQuietly();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
        }
        // close() clears running before it unparks us: an entry can be offered after the last poll and
        // before this break. Drain once more so nothing accepted while running is silently lost.
        AuditEntry leftover;
        while ((leftover = queue.poll()) != null) {
            size.decrementAndGet();
            batch.add(leftover);
            if (batch.size() >= BATCH) {
                writeBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            writeBatch(batch);
            batch.clear();
        }
        closeQuietly();
    }

    private int drainLeftovers() {
        int count = 0;
        while (queue.poll() != null) {
            size.decrementAndGet();
            count++;
        }
        return count;
    }

    private void writeBatch(List<AuditEntry> batch) {
        try {
            for (AuditEntry entry : batch) {
                String line = render(entry);
                ensureOpen(LocalDate.ofInstant(Instant.ofEpochMilli(entry.epochMillis()), ZoneOffset.UTC));
                out.write(line);
                out.write('\n');
                bytes += utf8Bytes(line) + 1L;
                written.increment();
            }
            out.flush();
        } catch (IOException | RuntimeException e) {
            dropped.add(batch.size());
            log.log(Level.SEVERE, "audit:write", "Cannot write the Vault audit log in " + dir, e);
            closeQuietly();
        }
    }

    private void ensureOpen(LocalDate entryDay) throws IOException {
        if (out != null && bytes < maxBytes && entryDay.equals(day)) return;
        closeQuietly();
        Files.createDirectories(dir);
        Path active = dir.resolve(ACTIVE);
        if (Files.exists(active)) {
            LocalDate fileDay = LocalDate.ofInstant(Files.getLastModifiedTime(active).toInstant(), ZoneOffset.UTC);
            if (Files.size(active) >= maxBytes || !fileDay.equals(entryDay)) rotate(active);
        }
        out = Files.newBufferedWriter(active, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        bytes = Files.size(active);
        day = entryDay;
    }

    private void rotate(Path active) throws IOException {
        Path target = dir.resolve("audit-" + ROTATED.format(Instant.now()) + ".log");
        Files.move(active, target, StandardCopyOption.REPLACE_EXISTING);
        List<Path> rotated = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "audit-*.log")) {
            files.forEach(rotated::add);
        }
        rotated.sort(null); // the timestamp format sorts chronologically
        for (int i = 0; i < rotated.size() - maxFiles; i++) {
            Files.deleteIfExists(rotated.get(i));
        }
    }

    private void reportDrops() {
        long total = dropped.sum();
        if (total > droppedReported
                && log.log(Level.WARNING, "audit:dropped", "Vault audit log dropped " + (total - droppedReported)
                + " entries (queue full or write error); total dropped " + total, null)) {
            droppedReported = total;
        }
    }

    private void flushQuietly() {
        if (out == null) return;
        try {
            out.flush();
        } catch (IOException e) {
            log.log(Level.SEVERE, "audit:flush", "Cannot flush the Vault audit log", e);
            closeQuietly();
        }
    }

    private void closeQuietly() {
        if (out == null) return;
        try {
            out.close();
        } catch (IOException e) {
            log.log(Level.SEVERE, "audit:close-file", "Cannot close the Vault audit log", e);
        }
        out = null;
    }

    /** One tab-separated line: time, operation, account, world, amount, result, balance, caller, message. */
    static String render(AuditEntry e) {
        return Instant.ofEpochMilli(e.epochMillis()) + "\t" + clean(e.operation()) + '\t' + account(e.account()) + '\t'
                + clean(e.world()) + '\t' + number(e.amount()) + '\t' + clean(e.result()) + '\t' + number(e.balance()) + '\t'
                + clean(e.caller()) + '\t' + clean(e.message());
    }

    /** Exact decimals are written in plain notation (no exponent); doubles keep Java's usual rendering. */
    private static String number(Number value) {
        if (value == null) return "-";
        return value instanceof java.math.BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    /** File size accounting is in bytes, not Java chars: a multi-byte character would undercount the rotation. */
    static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String account(Object account) {
        if (account instanceof OfflinePlayer player) {
            String name;
            try {
                name = player.getName();
            } catch (RuntimeException e) {
                name = null;
            }
            return player.getUniqueId() + (name != null ? "(" + clean(name) + ")" : "");
        }
        return clean(account == null ? null : account.toString());
    }

    private static String clean(String value) {
        if (value == null) return "-";
        StringBuilder sb = new StringBuilder(Math.min(value.length(), 256));
        for (int i = 0; i < value.length() && i < 256; i++) {
            char c = value.charAt(i);
            sb.append(Character.isISOControl(c) ? ' ' : c);
        }
        return sb.toString();
    }
}
