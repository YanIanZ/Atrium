package dev.yanianz.atrium.module.actions;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionResult.Status;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SequenceRunnerTest {
    /** Synchronous host that records what happened. */
    static class FakeHost implements ActionHost {
        final List<String> log = new ArrayList<>();
        boolean player = true;
        Set<String> permissions = new HashSet<>();
        String world = "world";
        @Nullable BigDecimal balance = new BigDecimal("100");
        CompletableFuture<Optional<BigDecimal>> balanceFuture;
        Map<String, Long> cooldowns = new HashMap<>();
        double random = 50;
        List<Long> delays = new ArrayList<>();
        List<Runnable> pendingDelays = new ArrayList<>();
        boolean holdDelays;
        boolean teleportOk = true;
        Map<String, String> builtIns = Map.of("player", "Steve", "uuid", "u", "world", "world", "x", "1", "y", "2", "z", "3");

        @Override public boolean hasPlayer() { return player; }
        @Override public Map<String, String> builtIns() { return player ? builtIns : Map.of(); }
        @Override public boolean hasPermission(String node) { return permissions.contains(node); }
        @Override public @Nullable String world() { return world; }

        @Override public CompletableFuture<Optional<BigDecimal>> balance() {
            log.add("balance?");
            return balanceFuture != null ? balanceFuture : CompletableFuture.completedFuture(Optional.ofNullable(balance));
        }

        @Override public Optional<Duration> cooldownRemaining(String key) {
            Long left = cooldowns.get(key);
            return Optional.of(left == null ? Duration.ZERO : Duration.ofSeconds(left));
        }

        @Override public Optional<Duration> tryCooldown(String key, Duration duration) {
            Long left = cooldowns.get(key);
            if (left != null) {
                return Optional.of(Duration.ofSeconds(left));
            }
            cooldowns.put(key, duration.toSeconds());
            log.add("cooldown+" + key);
            return Optional.of(Duration.ZERO);
        }

        @Override public void clearCooldown(String key) {
            cooldowns.remove(key);
            log.add("cooldown-" + key);
        }

        @Override public double randomPercent() { return random; }
        @Override public CompletableFuture<Boolean> console(String command) { log.add("console " + command); return CompletableFuture.completedFuture(true); }
        @Override public boolean performCommand(String command) { log.add("player " + command); return true; }
        @Override public void message(String template, Map<String, String> vars) { log.add("msg " + Variables.toMiniMessage(template, vars)); }
        @Override public void actionBar(String template, Map<String, String> vars) { log.add("actionbar " + template); }
        @Override public void title(String title, String subtitle, Map<String, String> vars) { log.add("title " + title + "/" + subtitle); }
        @Override public void sound(ActionParser.SoundSpec sound) { log.add("sound " + sound.key().asString()); }
        @Override public void notify(String key, TagResolver... resolvers) { log.add("notify " + key); }
        @Override public String formatMoney(BigDecimal amount) { return amount.toPlainString(); }

        @Override public CompletableFuture<TransactionResult> withdraw(BigDecimal amount) {
            log.add("withdraw " + amount.toPlainString());
            if (balance == null || balance.compareTo(amount) < 0) {
                return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.INSUFFICIENT_FUNDS, amount,
                        balance == null ? BigDecimal.ZERO : balance));
            }
            balance = balance.subtract(amount);
            return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.SUCCESS, amount, balance));
        }

        @Override public CompletableFuture<TransactionResult> deposit(BigDecimal amount) {
            log.add("deposit " + amount.toPlainString());
            balance = balance.add(amount);
            return CompletableFuture.completedFuture(new TransactionResult(TransactionResult.Status.SUCCESS, amount, balance));
        }

        @Override public CompletableFuture<Boolean> teleport(StoredLocation target) {
            log.add("teleport " + target.world() + " " + target.blockX() + " " + target.blockY() + " " + target.blockZ());
            return CompletableFuture.completedFuture(teleportOk);
        }

        @Override public Optional<StoredLocation> warp(String name) {
            return name.equals("spawn") ? Optional.of(new StoredLocation("world", 0, 64, 0, 0, 0)) : Optional.empty();
        }

        @Override public void resume(Runnable task, Runnable gone) { log.add("resume"); task.run(); }

        @Override public void later(long ticks, Runnable task, Runnable gone) {
            delays.add(ticks);
            if (holdDelays) {
                pendingDelays.add(task);
            } else {
                task.run();
            }
        }

        @Override public void warn(String message) { log.add("warn " + message); }
        @Override public void error(String message, Throwable error) { log.add("error " + message); }
    }

    private final FakeHost host = new FakeHost();

    private ActionResult run(Map<String, String> vars, String... lines) throws Exception {
        SequenceRunner runner = new SequenceRunner(ActionParser.parseAll(List.of(lines)), host, vars, 1);
        runner.start();
        return runner.result().getNow(null);
    }

    private ActionResult run(String... lines) throws Exception {
        return run(Map.of(), lines);
    }

    @Test void runsLinesInOrderWithVariables() throws Exception {
        ActionResult result = run(Map.of("arg1", "5"), "msg: hi {player}", "console: give {player} diamond {arg1}",
                "player: spawn", "title: A|B", "sound: block.note_block.pling", "actionbar: x");
        assertEquals(Status.COMPLETED, result.status());
        assertEquals(6, result.linesExecuted());
        assertEquals(List.of("msg hi <atrium_var:player>", "console give Steve diamond 5", "player spawn", "title A/B",
                "sound minecraft:block.note_block.pling", "actionbar x"), host.log);
    }

    @Test void takeMoneyRunsBeforeLaterLinesAndAbortsOnFailure() throws Exception {
        host.balance = new BigDecimal("5");
        ActionResult result = run("[cooldown:buy:1h]", "take-money: 10", "console: give {player} diamond 1");
        assertEquals(Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(List.of("cooldown+buy", "withdraw 10", "cooldown-buy", "notify insufficient-funds"), host.log);
        assertTrue(host.cooldowns.isEmpty(), "the cooldown started by this run is rolled back");
    }

    @Test void takeMoneySucceedsThenCommandRuns() throws Exception {
        ActionResult result = run("take-money: 10", "console: give {player} diamond 1");
        assertEquals(Status.COMPLETED, result.status());
        assertEquals(List.of("withdraw 10", "console give Steve diamond 1"), host.log);
        assertEquals(new BigDecimal("90"), host.balance);
    }

    @Test void failingConditionSkipsOnlyItsLine() throws Exception {
        ActionResult result = run("[perm:vip] msg: vip", "[!perm:vip] msg: normal", "msg: end");
        assertEquals(Status.COMPLETED, result.status());
        assertEquals(List.of("msg normal", "msg end"), host.log);
    }

    @Test void guardLineStopsSequenceWithMessage() throws Exception {
        ActionResult result = run("[perm:vip]", "msg: secret");
        assertEquals(Status.CONDITION_FAILED, result.status());
        assertEquals(List.of("notify no-permission"), host.log);
    }

    @Test void moneyWorldAndChanceConditions() throws Exception {
        host.balance = new BigDecimal("20");
        run("[money:10] msg: rich", "[money:50] msg: richer", "[!money:50] msg: not-richer",
                "[world:WORLD] msg: in-world", "[world:nether] msg: nether", "[chance:60] msg: lucky", "[chance:40] msg: unlucky",
                "[!chance:40] msg: neg");
        assertEquals(List.of("balance?", "msg rich", "balance?", "balance?", "msg not-richer", "msg in-world", "msg lucky", "msg neg"),
                host.log);
    }

    @Test void conditionsFailClosedWithoutPlayer() throws Exception {
        host.player = false;
        ActionResult result = run("[perm:x] msg: a", "[!perm:x] msg: b", "[!money:1] msg: c", "[!world:w] msg: d",
                "[!cooldown:k:1m] msg: e", "[cooldown:k:1m] msg: f", "msg: end");
        assertEquals(Status.COMPLETED, result.status());
        assertEquals(List.of("msg end"), host.log);
    }

    @Test void noPlayerMeansNoPlayerExecutors() throws Exception {
        host.player = false;
        assertEquals(Status.FAILED, run("player: spawn").status());
        assertEquals(Status.FAILED, run("take-money: 1").status());
        assertEquals(Status.UNSAFE_VARIABLE, run("console: kick {player}").status(), "unknown {player} is rejected");
        assertEquals(Status.COMPLETED, run("console: say hello", "msg: hi").status());
    }

    @Test void cooldownStartsOnlyAfterOtherConditionsPass() throws Exception {
        run("[cooldown:a:1m] [perm:vip] msg: x");
        assertTrue(host.cooldowns.isEmpty());
        host.permissions.add("vip");
        run("[cooldown:a:1m] [perm:vip] msg: x");
        assertEquals(60L, host.cooldowns.get("a"));
        host.log.clear();
        ActionResult second = run("[cooldown:a:1m]", "msg: again");
        assertEquals(Status.CONDITION_FAILED, second.status());
        assertEquals(List.of("notify on-cooldown"), host.log);
    }

    @Test void secondCooldownFailingReleasesFirst() throws Exception {
        host.cooldowns.put("b", 30L);
        run("[cooldown:a:1m] [cooldown:b:1m] msg: x");
        assertFalse(host.cooldowns.containsKey("a"));
        assertTrue(host.log.contains("cooldown-a"));
    }

    @Test void unsafeVariableRejectsLineAndRest() throws Exception {
        ActionResult result = run(Map.of("arg1", "@a"), "msg: before", "console: give {arg1} diamond 64", "msg: after");
        assertEquals(Status.UNSAFE_VARIABLE, result.status());
        assertEquals(1, result.linesExecuted());
        assertTrue(host.log.contains("notify invalid-input"));
        assertFalse(host.log.stream().anyMatch(l -> l.startsWith("console")));
        assertFalse(host.log.contains("msg after"));
    }

    @Test void delaysScheduleTheRestAndZeroDelayContinues() throws Exception {
        host.holdDelays = true;
        SequenceRunner runner = new SequenceRunner(ActionParser.parseAll(List.of("msg: a", "delay: 0", "msg: b", "delay: 1s", "msg: c")),
                host, Map.of(), 1);
        runner.start();
        assertFalse(runner.result().isDone());
        assertEquals(List.of("msg a", "msg b"), host.log);
        assertEquals(List.of(20L), host.delays);
        host.pendingDelays.removeFirst().run();
        assertEquals(List.of("msg a", "msg b", "msg c"), host.log);
        assertEquals(Status.COMPLETED, runner.result().getNow(null).status());
    }

    @Test void asyncBalanceResumesOnOwnerThread() throws Exception {
        host.balanceFuture = new CompletableFuture<>();
        SequenceRunner runner = new SequenceRunner(ActionParser.parseAll(List.of("[money:10] msg: rich", "msg: end")), host, Map.of(), 1);
        runner.start();
        assertFalse(runner.result().isDone());
        host.balanceFuture.complete(Optional.of(new BigDecimal("15")));
        assertEquals(List.of("balance?", "resume", "msg rich", "msg end"), host.log);
        assertTrue(runner.result().getNow(null).success());
    }

    @Test void stopEndsWithMessage() throws Exception {
        ActionResult result = run("[!perm:vip] stop: <red>VIP only", "msg: vip stuff");
        assertEquals(Status.STOPPED, result.status());
        assertTrue(result.success());
        assertEquals(List.of("msg <red>VIP only"), host.log);
    }

    @Test void teleportWarpAndMoney() throws Exception {
        assertEquals(Status.COMPLETED, run(Map.of("w", "world"), "teleport: {w} {x} 70 {z}", "warp: spawn", "give-money: 5").status());
        assertEquals(List.of("teleport world 1 70 3", "teleport world 0 64 0", "deposit 5"), host.log);
        assertEquals(Status.FAILED, run("warp: nowhere").status());
        host.teleportOk = false;
        assertEquals(Status.FAILED, run("teleport: world 0 0 0", "msg: unreachable").status());
        assertFalse(host.log.contains("msg unreachable"));
    }

    @Test void variableProducingInvalidArgumentIsRejected() throws Exception {
        ActionResult result = run(Map.of("amount", "lots"), "take-money: {amount}");
        assertEquals(Status.INVALID, result.status());
        assertFalse(host.log.stream().anyMatch(l -> l.startsWith("withdraw")));
    }

    @Test void depthIsVisibleWhileRunning() throws Exception {
        int[] seen = new int[1];
        FakeHost probe = new FakeHost() {
            @Override public void message(String template, Map<String, String> vars) {
                seen[0] = SequenceRunner.currentDepth();
            }
        };
        SequenceRunner runner = new SequenceRunner(ActionParser.parseAll(List.of("msg: x")), probe, Map.of(), 3);
        runner.start();
        assertEquals(3, seen[0]);
        assertEquals(0, SequenceRunner.currentDepth());
    }
}
