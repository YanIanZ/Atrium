package dev.yanianz.atrium.core.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jspecify.annotations.Nullable;

/** Cancellable handle for a scheduled task. */
public interface TaskHandle {
    TaskHandle NOOP = new TaskHandle() {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
            return true;
        }
    };

    void cancel();

    boolean isCancelled();

    /** True once the task can no longer run (cancelled, or a one-shot task that finished). */
    default boolean isDone() {
        return isCancelled();
    }

    static TaskHandle of(@Nullable ScheduledTask task) {
        if (task == null) {
            return NOOP;
        }
        return new TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }

            @Override
            public boolean isDone() {
                return switch (task.getExecutionState()) {
                    case FINISHED, CANCELLED, CANCELLED_RUNNING -> true;
                    default -> false;
                };
            }
        };
    }
}
