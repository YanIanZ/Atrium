package dev.yanianz.atrium.module.actions;

import java.math.BigDecimal;
import java.time.Duration;

/** A {@code [name:argument]} prefix of an action line. {@code negated} is the {@code !} form. */
sealed interface Condition {
    boolean negated();

    /** The condition as written, for logs and validation messages. */
    String describe();

    record Permission(String node, boolean negated) implements Condition {
        @Override
        public String describe() {
            return "[" + (negated ? "!" : "") + "perm:" + node + "]";
        }
    }

    /** Has at least {@code amount}. */
    record Money(BigDecimal amount, boolean negated) implements Condition {
        @Override
        public String describe() {
            return "[" + (negated ? "!" : "") + "money:" + amount.toPlainString() + "]";
        }
    }

    record World(String name, boolean negated) implements Condition {
        @Override
        public String describe() {
            return "[" + (negated ? "!" : "") + "world:" + name + "]";
        }
    }

    /**
     * Passes when the cooldown is not running and starts it. Negated: passes while it is running (and changes
     * nothing).
     */
    record Cooldown(String key, Duration duration, boolean negated) implements Condition {
        @Override
        public String describe() {
            return "[" + (negated ? "!" : "") + "cooldown:" + key + ":" + duration.toSeconds() + "s]";
        }
    }

    /** Passes with {@code percent}% probability. */
    record Chance(double percent, boolean negated) implements Condition {
        @Override
        public String describe() {
            return "[" + (negated ? "!" : "") + "chance:" + percent + "]";
        }
    }
}
