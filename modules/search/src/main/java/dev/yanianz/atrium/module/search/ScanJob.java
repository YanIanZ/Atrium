package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.UUID;

/**
 * One running scan, owned by the player (or console) that started it. The owner may only run one
 * scan at a time; {@link Scans} enforces that and drops the job when it reports.
 */
abstract class ScanJob implements TaskHandle {
    private final UUID owner;
    private final String ownerName;
    private final long startedAt = System.nanoTime();
    protected volatile boolean cancelled;
    protected volatile boolean finished;
    /** Set when the module is disabled; a late report must not be sent any more. */
    protected volatile boolean stopped;
    protected volatile TaskHandle driver = TaskHandle.NOOP;

    protected ScanJob(UUID owner, String ownerName) {
        this.owner = owner;
        this.ownerName = ownerName;
    }

    final UUID owner() {
        return owner;
    }

    final String ownerName() {
        return ownerName;
    }

    final long seconds() {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000_000L);
    }

    final boolean isFinished() {
        return finished;
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return finished || cancelled;
    }

    /** Starts the driver. Called once, after the job was registered. */
    abstract void begin();

    /** Module disable: stop dispatching and cancel the driver. */
    void stop() {
        stopped = true;
        cancelled = true;
        driver.cancel();
    }
}
