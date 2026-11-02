package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.vault.ConvertMath.Action;
import dev.yanianz.atrium.vault.ConvertMath.Plan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConvertTest {
    @Test
    void deltaMath() {
        assertEquals(new Plan(Action.DEPOSIT, 9.8), ConvertMath.plan(10.1, 0.3));
        assertEquals(new Plan(Action.WITHDRAW, 50.0), ConvertMath.plan(50, 100));
        assertEquals(new Plan(Action.NONE, 0), ConvertMath.plan(42.42, 42.42));
        assertEquals(new Plan(Action.DEPOSIT, 0.01), ConvertMath.plan(0.01, 0));
        assertEquals(new Plan(Action.WITHDRAW, 7.25), ConvertMath.plan(0, 7.25));
        assertEquals(new Plan(Action.DEPOSIT, 15.0), ConvertMath.plan(5, -10), "debt in target is cleared");
        assertEquals(Action.NONE, ConvertMath.plan(10.1, 10.100000000000001).action(), "double noise is not a delta");
        assertEquals(Action.NONE, ConvertMath.plan(10.004, 10.0, 2).action(), "below the target's precision");
        assertEquals(new Plan(Action.DEPOSIT, 0.01), ConvertMath.plan(10.01, 10.0, 2));
        assertEquals(Action.INVALID, ConvertMath.plan(Double.NaN, 1).action());
        assertEquals(Action.INVALID, ConvertMath.plan(1, Double.POSITIVE_INFINITY).action());
    }

    @Test
    void verificationTolerance() {
        assertTrue(ConvertMath.matches(10.005, 10.01, 2));
        assertFalse(ConvertMath.matches(10.0, 10.02, 2));
        assertTrue(ConvertMath.matches(3.0, 3.0, -1));
        assertFalse(ConvertMath.matches(3.0, 3.0001, -1));
    }

    @Test
    void dryRunWritesNothingAndApplyNeverTouchesSource() {
        MapEconomy from = new MapEconomy("From");
        MapEconomy to = new MapEconomy("To");
        from.balances.putAll(Map.of("alice", 100.0, "bob", 5.5, "carol", 0.0, "dave", 10.1));
        to.balances.putAll(Map.of("alice", 40.0, "bob", 20.0, "carol", 0.0, "dave", 0.3));
        var players = List.of(Players.offline("alice"), Players.offline("bob"), Players.offline("carol"),
                Players.offline("dave"), Players.offline("erin"), Players.offline("frank"));
        from.balances.put("frank", 3.0); // frank has no target account yet

        EconomyConverter.Report dry = EconomyConverter.convert(from, to, players, false);
        assertEquals(new EconomyConverter.Report(6, 1, 3, 1, 1, 0, List.of()), dry);
        assertEquals(0, to.writes.get(), "dry run is read-only");

        Map<String, Double> sourceBefore = Map.copyOf(from.balances);
        EconomyConverter.Report applied = EconomyConverter.convert(from, to, players, true);
        assertEquals(new EconomyConverter.Report(6, 1, 3, 1, 1, 0, List.of()), applied);
        assertEquals(0, from.writes.get(), "source is never written");
        assertEquals(sourceBefore, from.balances);
        assertEquals(100.0, to.balances.get("alice"));
        assertEquals(5.5, to.balances.get("bob"));
        assertEquals(10.1, to.balances.get("dave"), 1e-9, "double arithmetic in the fake provider");
        assertEquals(3.0, to.balances.get("frank"));
        assertFalse(to.balances.containsKey("erin"));

        EconomyConverter.Report again = EconomyConverter.convert(from, to, players, true);
        assertEquals(5, again.unchanged(), "idempotent");
    }

    @Test
    void failuresAreReported() {
        MapEconomy from = new MapEconomy("From");
        MapEconomy to = new MapEconomy("To");
        from.balances.put("alice", 1.0);
        to.balances.put("alice", 50.0);
        to.failWith = null;
        MapEconomy refusing = new MapEconomy("Refusing") {
            @Override
            public net.milkbowl.vault.economy.EconomyResponse withdrawPlayer(String player, double amount) {
                return new net.milkbowl.vault.economy.EconomyResponse(0, 50, net.milkbowl.vault.economy.EconomyResponse.ResponseType.FAILURE, "locked");
            }
        };
        refusing.balances.put("alice", 50.0);
        EconomyConverter.Report report = EconomyConverter.convert(from, refusing, List.of(Players.offline("alice")), true);
        assertEquals(1, report.failed());
        assertTrue(report.failures().getFirst().contains("locked"), report.failures().toString());

        to.failWith = new IllegalStateException("boom");
        report = EconomyConverter.convert(from, to, List.of(Players.offline("alice")), true);
        assertEquals(1, report.failed());
        assertTrue(report.failures().getFirst().startsWith("alice: IllegalStateException"));
    }
}
