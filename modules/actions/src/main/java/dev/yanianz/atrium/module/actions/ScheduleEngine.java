package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;

/**
 * Runs the schedules from {@code schedules.yml} on the global scheduler.
 *
 * <p>One tracked global task sleeps until the next due schedule (at most an hour, so a clock change is
 * noticed). After a run the next time is computed <em>from now</em>, never from the planned time: a run missed
 * while the server was down or lagging is skipped instead of being replayed in a burst. Only the console
 * action host is used, so a schedule can never act as a random player.
 */
final class ScheduleEngine {
    private final ModuleContext ctx;
    private final ActionService actions;
    private final Path file;
    private volatile List<RuntimeJob> jobs = List.of();
    private TaskHandle task = TaskHandle.NOOP;
    private volatile boolean running;
    private volatile boolean stopping;

    /** A job plus its next planned run. Only the global scheduler thread touches {@code next}. */
    private static final class RuntimeJob {
        final SchedulesFile.Job job;
        long next;

        RuntimeJob(SchedulesFile.Job job) {
            this.job = job;
        }
    }

    ScheduleEngine(ModuleContext ctx, ActionService actions, Path file) {
        this.ctx = ctx;
        this.actions = actions;
        this.file = file;
    }

    /** Reads the file (blocking) and starts the timer. Call from enable or an I/O thread followed by {@link #start}. */
    void load() throws IOException {
        SchedulesFile.writeExampleIfMissing(file);
        List<SchedulesFile.Job> parsed = SchedulesFile.load(file, ctx.logger());
        long now = System.currentTimeMillis();
        List<RuntimeJob> loaded = new ArrayList<>();
        for (SchedulesFile.Job job : parsed) {
            if (!job.enabled()) {
                continue;
            }
            RuntimeJob runtime = new RuntimeJob(job);
            Long next = nextRun(job, now);
            if (next == null) {
                ctx.logger().warn("Schedule '{}' can never match; it was disabled", job.id());
                continue;
            }
            runtime.next = next;
            loaded.add(runtime);
        }
        jobs = loaded;
        ctx.logger().info("Loaded {} schedule(s)", loaded.size());
    }

    void start() {
        stopping = false;
        running = true;
        scheduleNextWake();
    }

    void stop() {
        stopping = true;
        running = false;
        task.cancel();
        task = TaskHandle.NOOP;
    }

    /** Ids of the loaded schedules, for tab completion. */
    List<String> ids() {
        return jobs.stream().map(job -> job.job.id()).sorted().toList();
    }

    /** Runs one schedule now, ignoring its next time and the player count. Used by {@code /actions schedules run}. */
    void runNow(String id) {
        for (RuntimeJob job : jobs) {
            if (job.job.id().equalsIgnoreCase(id)) {
                execute(job.job, true);
                return;
            }
        }
    }

    boolean has(String id) {
        return jobs.stream().anyMatch(job -> job.job.id().equalsIgnoreCase(id));
    }

    private void scheduleNextWake() {
        if (stopping || !running) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        for (RuntimeJob job : jobs) {
            next = Math.min(next, job.next);
        }
        long delayMs = next == Long.MAX_VALUE ? 3_600_000L : Math.max(500L, next - now);
        delayMs = Math.min(delayMs, 3_600_000L);
        long ticks = Math.max(1L, delayMs / 50L);
        task = ctx.track(ctx.scheduler().globalLater(this::wake, ticks));
    }

    private void wake() {
        if (stopping || !running) {
            return;
        }
        long now = System.currentTimeMillis();
        for (RuntimeJob job : jobs) {
            if (job.next > now) {
                continue;
            }
            // Compute the next run from now: a missed window is skipped, not replayed.
            Long next = nextRun(job.job, now);
            if (next == null) {
                ctx.logger().warn("Schedule '{}' has no future match any more; it was disabled", job.job.id());
                job.next = Long.MAX_VALUE;
                continue;
            }
            job.next = next;
            execute(job.job, false);
        }
        scheduleNextWake();
    }

    private void execute(SchedulesFile.Job job, boolean forced) {
        int online = Bukkit.getOnlinePlayers().size();
        if (!forced && !job.allowsPlayers(online)) {
            return;
        }
        try {
            actions.runAs(Bukkit.getConsoleSender(), job.actions(), Map.of("players", Integer.toString(online)))
                    .whenComplete((outcome, error) -> report(job, outcome, error));
        } catch (RuntimeException e) {
            ctx.logger().error("Schedule '{}' could not start", job.id(), e);
        }
    }

    private void report(SchedulesFile.Job job, ActionResult outcome, Throwable error) {
        if (error != null) {
            ctx.logger().error("Schedule '{}' failed", job.id(), error);
            return;
        }
        if (outcome.status() != ActionResult.Status.COMPLETED) {
            ctx.logger().warn("Schedule '{}' ended with {}: {}", job.id(), outcome.status(), outcome.detail());
        }
    }

    /** The next epoch millis the job should run, or {@code null} when it will never match. */
    private static Long nextRun(SchedulesFile.Job job, long now) {
        if (job.intervalBased()) {
            long period = job.every().toMillis();
            // Align to the epoch so a restart does not shift an interval schedule forever.
            long next = ((now / period) + 1) * period;
            return next;
        }
        ZonedDateTime after = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault());
        return job.cron().next(after).map(time -> time.toInstant().toEpochMilli()).orElse(null);
    }
}
