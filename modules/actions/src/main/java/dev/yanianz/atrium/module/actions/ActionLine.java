package dev.yanianz.atrium.module.actions;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One parsed line. {@code executor == null} means a guard line (conditions only). {@code argument} is the raw
 * template after the colon (variables not yet substituted); {@code delayTicks} is set for {@link Executor#DELAY}.
 */
record ActionLine(int number, List<Condition> conditions, @Nullable Executor executor, String argument, long delayTicks) {
    ActionLine {
        conditions = List.copyOf(conditions);
        argument = argument == null ? "" : argument;
    }

    boolean isGuard() {
        return executor == null;
    }
}
