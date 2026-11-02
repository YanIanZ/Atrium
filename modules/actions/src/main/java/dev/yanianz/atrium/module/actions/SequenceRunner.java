package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionResult.Status;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.message.Msg;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Runs one parsed sequence line by line on the owner's thread. Asynchronous steps (money, console dispatch,
 * teleports, delays) suspend the sequence and resume it on the owner's thread; steps whose result is already
 * available continue inline.
 *
 * <p>Ordering guarantees: a line's conditions are evaluated before its executor; cooldowns are started only
 * after every other condition of the line passed; {@code take-money} completes before the next line starts,
 * and when it fails the sequence ends and the cooldowns this run started are cleared again.
 */
final class SequenceRunner {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private sealed interface Step permits Next, Done, Delay, Wait {}

    private record Next() implements Step {}

    private record Done(ActionResult result) implements Step {}

    private record Delay(long ticks) implements Step {}

    private record Wait<T>(CompletableFuture<T> future, Function<T, Step> then) implements Step {}

    private static final Next NEXT = new Next();

    private final List<ActionLine> lines;
    private final ActionHost host;
    private final Map<String, String> callerVariables;
    private final int depth;
    private final CompletableFuture<ActionResult> result = new CompletableFuture<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final List<String> startedCooldowns = new ArrayList<>();
    private int index;
    private int executed;

    SequenceRunner(List<ActionLine> lines, ActionHost host, Map<String, String> callerVariables, int depth) {
        this.lines = List.copyOf(lines);
        this.host = host;
        this.callerVariables = Map.copyOf(callerVariables);
        this.depth = depth;
    }

    /** Nesting depth of the sequence running on this thread (0 outside of any sequence). */
    static int currentDepth() {
        return DEPTH.get();
    }

    /** Runs {@code work} with the given nesting depth visible on this thread. */
    static <T> T withDepth(int depth, Supplier<T> work) {
        int previous = DEPTH.get();
        DEPTH.set(depth);
        try {
            return work.get();
        } finally {
            DEPTH.set(previous);
        }
    }

    CompletableFuture<ActionResult> result() {
        return result;
    }

    /** Must be called on the owner's thread. */
    void start() {
        if (lines.isEmpty()) {
            finish(ActionResult.of(Status.COMPLETED, 0));
            return;
        }
        enter(() -> drive(process(0)));
    }

    /** Ends the sequence from outside (module disabled). */
    void abort(Status status, String detail) {
        finish(new ActionResult(status, executed, detail));
    }

    private void enter(Runnable work) {
        withDepth(depth, () -> {
            work.run();
            return null;
        });
    }

    private void gone() {
        finish(new ActionResult(Status.PLAYER_GONE, executed, "player left before line " + currentLineNumber()));
    }

    private int currentLineNumber() {
        return index < lines.size() ? lines.get(index).number() : -1;
    }

    private void finish(ActionResult outcome) {
        if (finished.compareAndSet(false, true)) {
            result.complete(outcome);
        }
    }

    private void drive(Step first) {
        Step step = first;
        while (!finished.get()) {
            step = settle(step);
            switch (step) {
                case Next ignored -> {
                    index++;
                    if (index >= lines.size()) {
                        finish(ActionResult.of(Status.COMPLETED, executed));
                        return;
                    }
                    step = process(index);
                }
                case Done done -> {
                    finish(done.result());
                    return;
                }
                case Delay delay -> {
                    index++;
                    if (index >= lines.size()) {
                        finish(ActionResult.of(Status.COMPLETED, executed));
                        return;
                    }
                    if (delay.ticks() <= 0) {
                        step = process(index);
                    } else {
                        int at = index;
                        host.later(delay.ticks(), () -> enter(() -> drive(process(at))), this::gone);
                        return;
                    }
                }
                case Wait<?> wait -> {
                    suspend(wait);
                    return;
                }
            }
        }
    }

    /** Unwraps waits whose future is already complete, so they continue inline. */
    private Step settle(Step step) {
        Step current = step;
        while (current instanceof Wait<?> wait && wait.future().isDone()) {
            current = complete(wait);
        }
        return current;
    }

    private <T> Step complete(Wait<T> wait) {
        T value;
        try {
            value = wait.future().join();
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return failed("line " + currentLineNumber() + " failed: " + cause.getMessage(), cause);
        }
        try {
            return wait.then().apply(value);
        } catch (RuntimeException e) {
            return failed("line " + currentLineNumber() + " failed", e);
        }
    }

    private <T> void suspend(Wait<T> wait) {
        wait.future().whenComplete((value, error) -> host.resume(() -> enter(() -> drive(wait)), this::gone));
    }

    private Step process(int at) {
        ActionLine line = lines.get(at);
        try {
            Map<String, String> vars = Variables.merge(host.builtIns(), callerVariables);
            return checkConditions(line, vars);
        } catch (RuntimeException e) {
            return failed("line " + line.number() + " failed", e);
        }
    }

    private Step failed(String detail, Throwable error) {
        host.error("Action sequence " + detail, error);
        host.notify("failed");
        return new Done(new ActionResult(Status.FAILED, executed, detail));
    }

    // ---- conditions ----

    private enum Tri { PASS, FAIL, UNKNOWN }

    private static Tri of(boolean value, boolean negated) {
        return value != negated ? Tri.PASS : Tri.FAIL;
    }

    private Step checkConditions(ActionLine line, Map<String, String> vars) {
        List<Condition.Money> money = new ArrayList<>();
        for (Condition condition : line.conditions()) {
            Tri outcome = switch (condition) {
                case Condition.Permission p -> host.hasPlayer() ? of(host.hasPermission(p.node()), p.negated()) : Tri.UNKNOWN;
                case Condition.World w -> {
                    String world = host.hasPlayer() ? host.world() : null;
                    yield world == null ? Tri.UNKNOWN : of(world.equalsIgnoreCase(w.name()), w.negated());
                }
                case Condition.Chance c -> of(host.randomPercent() < c.percent(), c.negated());
                case Condition.Money m -> {
                    money.add(m);
                    yield Tri.PASS;
                }
                case Condition.Cooldown ignored -> Tri.PASS; // evaluated last, it has a side effect
            };
            if (outcome != Tri.PASS) {
                return conditionFailed(line, condition, null);
            }
        }
        if (money.isEmpty()) {
            return checkCooldowns(line, vars);
        }
        if (!host.hasPlayer()) {
            return conditionFailed(line, money.getFirst(), null);
        }
        return new Wait<>(host.balance(), balance -> {
            for (Condition.Money m : money) {
                Tri outcome = balance.map(b -> of(b.compareTo(m.amount()) >= 0, m.negated())).orElse(Tri.UNKNOWN);
                if (outcome != Tri.PASS) {
                    return conditionFailed(line, m, null);
                }
            }
            return checkCooldowns(line, vars);
        });
    }

    private Step checkCooldowns(ActionLine line, Map<String, String> vars) {
        List<String> startedHere = new ArrayList<>();
        for (Condition condition : line.conditions()) {
            if (!(condition instanceof Condition.Cooldown c)) {
                continue;
            }
            Optional<Duration> left = !host.hasPlayer() ? Optional.empty()
                    : c.negated() ? host.cooldownRemaining(c.key()) : host.tryCooldown(c.key(), c.duration());
            boolean pass = left.isPresent() && (c.negated() ? left.get().isPositive() : left.get().isZero());
            if (!pass) {
                startedHere.forEach(host::clearCooldown);
                return conditionFailed(line, c, left.filter(Duration::isPositive).orElse(null));
            }
            if (!c.negated()) {
                startedHere.add(c.key());
            }
        }
        startedCooldowns.addAll(startedHere);
        return line.isGuard() ? NEXT : execute(line, vars);
    }

    private Step conditionFailed(ActionLine line, Condition condition, @Nullable Duration cooldownLeft) {
        if (!line.isGuard()) {
            return NEXT; // a failing condition only skips its own line
        }
        if (!condition.negated() && host.hasPlayer()) {
            switch (condition) {
                case Condition.Permission ignored -> host.notify("no-permission");
                case Condition.Money m -> host.notify("not-enough-money", Msg.unparsed("amount", host.formatMoney(m.amount())));
                case Condition.World w -> host.notify("wrong-world", Msg.unparsed("world", w.name()));
                case Condition.Cooldown ignored -> {
                    if (cooldownLeft != null) {
                        host.notify("on-cooldown", Msg.duration("time", cooldownLeft));
                    }
                }
                case Condition.Chance ignored -> {
                    // silent on purpose
                }
            }
        }
        return new Done(new ActionResult(Status.CONDITION_FAILED, executed,
                "line " + line.number() + ": " + condition.describe() + " did not pass"));
    }

    // ---- executors ----

    private Step execute(ActionLine line, Map<String, String> vars) {
        Executor executor = line.executor();
        String argument = line.argument();
        if (executor == null) {
            return NEXT;
        }
        String resolved;
        if (executor.strictVariables) {
            try {
                resolved = Variables.substituteStrict(argument, vars);
            } catch (Variables.UnsafeValueException e) {
                host.warn("Rejected action line " + line.number() + ": " + e.getMessage());
                host.notify("invalid-input");
                return new Done(new ActionResult(Status.UNSAFE_VARIABLE, executed, "line " + line.number() + ": " + e.getMessage()));
            }
        } else {
            resolved = argument;
        }
        try {
            return run(line, executor, resolved, vars);
        } catch (ActionSyntaxException e) {
            // Only reachable when a substituted value produced an invalid argument.
            host.warn("Rejected action " + e.getMessage());
            host.notify("invalid-input");
            return new Done(new ActionResult(Status.INVALID, executed, e.getMessage()));
        }
    }

    private Step run(ActionLine line, Executor executor, String argument, Map<String, String> vars) throws ActionSyntaxException {
        int number = line.number();
        switch (executor) {
            case CONSOLE -> {
                executed++;
                return new Wait<>(host.console(argument), ok -> {
                    if (!Boolean.TRUE.equals(ok)) {
                        host.warn("Action line " + number + ": console command '" + argument + "' was not handled");
                    }
                    return NEXT;
                });
            }
            case PLAYER -> {
                if (!host.hasPlayer()) {
                    return needsPlayer(number);
                }
                executed++;
                host.performCommand(argument);
                return NEXT;
            }
            case MSG -> {
                executed++;
                host.message(argument, vars);
                return NEXT;
            }
            case ACTIONBAR -> {
                if (host.hasPlayer()) {
                    executed++;
                    host.actionBar(argument, vars);
                }
                return NEXT;
            }
            case TITLE -> {
                if (host.hasPlayer()) {
                    executed++;
                    int bar = argument.indexOf('|');
                    host.title(bar < 0 ? argument : argument.substring(0, bar), bar < 0 ? "" : argument.substring(bar + 1), vars);
                }
                return NEXT;
            }
            case SOUND -> {
                ActionParser.SoundSpec sound = ActionParser.sound(number, argument);
                if (host.hasPlayer()) {
                    executed++;
                    host.sound(sound);
                }
                return NEXT;
            }
            case DELAY -> {
                executed++;
                return new Delay(line.delayTicks());
            }
            case TAKE_MONEY -> {
                BigDecimal amount = ActionParser.amount(number, argument);
                if (!host.hasPlayer()) {
                    return needsPlayer(number);
                }
                executed++;
                return new Wait<>(host.withdraw(amount), transaction -> {
                    if (transaction.success()) {
                        return NEXT;
                    }
                    rollbackCooldowns();
                    if (transaction.status() == TransactionResult.Status.INSUFFICIENT_FUNDS) {
                        host.notify("insufficient-funds", Msg.unparsed("amount", host.formatMoney(amount)),
                                Msg.unparsed("balance", host.formatMoney(transaction.balance())));
                        return new Done(new ActionResult(Status.INSUFFICIENT_FUNDS, executed - 1,
                                "line " + number + ": cannot take " + amount.toPlainString()));
                    }
                    host.warn("Action line " + number + ": withdrawing " + amount.toPlainString() + " failed: " + transaction.status());
                    host.notify("failed");
                    return new Done(new ActionResult(Status.FAILED, executed - 1, "line " + number + ": withdraw " + transaction.status()));
                });
            }
            case GIVE_MONEY -> {
                BigDecimal amount = ActionParser.amount(number, argument);
                if (!host.hasPlayer()) {
                    return needsPlayer(number);
                }
                executed++;
                return new Wait<>(host.deposit(amount), transaction -> {
                    if (transaction.success()) {
                        return NEXT;
                    }
                    host.warn("Action line " + number + ": depositing " + amount.toPlainString() + " failed: " + transaction.status());
                    host.notify("failed");
                    return new Done(new ActionResult(Status.FAILED, executed, "line " + number + ": deposit " + transaction.status()));
                });
            }
            case TELEPORT -> {
                StoredLocation target = ActionParser.location(number, argument);
                return teleport(number, target);
            }
            case WARP -> {
                String name = ActionParser.warp(number, argument);
                Optional<StoredLocation> target = host.warp(name);
                if (target.isEmpty()) {
                    host.warn("Action line " + number + ": unknown warp '" + name + "'");
                    host.notify("failed");
                    return new Done(new ActionResult(Status.FAILED, executed, "line " + number + ": unknown warp " + name));
                }
                return teleport(number, target.get());
            }
            case STOP -> {
                if (!argument.isEmpty()) {
                    host.message(argument, vars);
                }
                return new Done(ActionResult.of(Status.STOPPED, executed));
            }
        }
        throw new IllegalStateException("Unhandled executor " + executor);
    }

    private Step teleport(int number, StoredLocation target) {
        if (!host.hasPlayer()) {
            return needsPlayer(number);
        }
        executed++;
        return new Wait<>(host.teleport(target), arrived -> Boolean.TRUE.equals(arrived) ? NEXT
                : new Done(new ActionResult(Status.FAILED, executed, "line " + number + ": teleport did not happen")));
    }

    private Step needsPlayer(int number) {
        host.notify("player-only");
        return new Done(new ActionResult(Status.FAILED, executed, "line " + number + " needs a player"));
    }

    private void rollbackCooldowns() {
        startedCooldowns.forEach(host::clearCooldown);
        startedCooldowns.clear();
    }
}
