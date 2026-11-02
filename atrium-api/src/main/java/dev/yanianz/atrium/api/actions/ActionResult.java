package dev.yanianz.atrium.api.actions;

import java.util.Objects;

/**
 * Outcome of an action sequence run through {@link ActionService}.
 *
 * @param status        how the sequence ended
 * @param linesExecuted number of lines whose executor actually ran (skipped lines are not counted)
 * @param detail        short, admin-facing explanation (for logs); empty when there is nothing to add
 */
public record ActionResult(Status status, int linesExecuted, String detail) {
    public enum Status {
        /** Every line was processed. */
        COMPLETED,
        /** A {@code stop} line ended the sequence on purpose. */
        STOPPED,
        /** A guard line (a line with only conditions) did not pass; nothing after it ran. */
        CONDITION_FAILED,
        /** {@code take-money} could not withdraw the amount; nothing after it ran. */
        INSUFFICIENT_FUNDS,
        /** A variable value was not safe to insert into a command; the line and the rest were rejected. */
        UNSAFE_VARIABLE,
        /** The lines contain a syntax error; nothing ran. */
        INVALID,
        /** The player left (or the entity was removed) before the sequence finished. */
        PLAYER_GONE,
        /** Too many sequences are running for this player, or sequences started each other too deeply. */
        LIMIT_REACHED,
        /** A required service was missing or an executor failed (teleport, economy error, exception). */
        FAILED
    }

    public ActionResult {
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public static ActionResult of(Status status, int linesExecuted) {
        return new ActionResult(status, linesExecuted, "");
    }

    /** True when the sequence ran to its end or stopped on purpose. */
    public boolean success() {
        return status == Status.COMPLETED || status == Status.STOPPED;
    }
}
