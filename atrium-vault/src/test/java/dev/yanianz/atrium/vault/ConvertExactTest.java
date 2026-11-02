package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.vault.ConvertMath.Action;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** /vault-convert between v1 and v2 economies, and between two v2 economies, in exact decimal. */
class ConvertExactTest {
    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void exactPlan() {
        assertEquals(new ConvertMath.ExactPlan(Action.DEPOSIT, d("9.8"), d("10.1")), ConvertMath.plan(d("10.1"), d("0.3"), 2));
        assertEquals(Action.NONE, ConvertMath.plan(d("5.00"), d("5"), 2).action(), "scale does not matter");
        ConvertMath.ExactPlan rounded = ConvertMath.plan(d("10.005"), d("0"), 2);
        assertEquals(d("10.00"), rounded.expected(), "rounded HALF_EVEN to the target precision");
        assertEquals(d("10.00"), rounded.amount());
        assertEquals(new ConvertMath.ExactPlan(Action.WITHDRAW, d("0.0000000001"), d("1")),
                ConvertMath.plan(d("1"), d("1.0000000001"), -1));
        assertEquals(Action.INVALID, ConvertMath.plan(null, d("1"), 2).action());
    }

    @Test
    void v2ToV2IsExactBeyondDoublePrecision() {
        MapEconomyV2 from = new MapEconomyV2("From");
        MapEconomyV2 to = new MapEconomyV2("To");
        var alice = Players.offline("alice");
        var bob = Players.offline("bob");
        from.balances.put(alice.getUniqueId(), d("12345678901234567.89")); // not representable as a double
        from.balances.put(bob.getUniqueId(), d("0.10"));
        to.balances.put(bob.getUniqueId(), d("0.30"));
        var source = EconomyConverter.v2(from, "From:v2", "Vault");
        var target = EconomyConverter.v2(to, "To:v2", "Vault");

        EconomyConverter.Report dry = EconomyConverter.convert(source, target, List.of(alice, bob), false);
        assertEquals(new EconomyConverter.Report(2, 0, 1, 1, 0, 0, List.of()), dry);
        assertEquals(0, to.writes.get());

        EconomyConverter.Report applied = EconomyConverter.convert(source, target, List.of(alice, bob), true);
        assertEquals(0, applied.failed(), applied.failures().toString());
        assertEquals(d("12345678901234567.89"), to.balances.get(alice.getUniqueId()));
        assertEquals(d("0.10"), to.balances.get(bob.getUniqueId()));
        assertEquals(List.of("Vault"), from.plugins.stream().distinct().toList(), "pluginName passed to v2");
        assertEquals(2, EconomyConverter.convert(source, target, List.of(alice, bob), true).unchanged(), "idempotent");
    }

    @Test
    void v1ToV2AndBack() {
        MapEconomy legacy = new MapEconomy("Legacy");
        MapEconomyV2 modern = new MapEconomyV2("Modern");
        var alice = Players.offline("alice");
        var carol = Players.offline("carol");
        legacy.balances.put("alice", 10.100000000000001); // double noise
        legacy.balances.put("carol", 7.25);
        modern.balances.put(carol.getUniqueId(), d("7.25"));
        var v1 = EconomyConverter.v1(legacy, "Legacy:v1");
        var v2 = EconomyConverter.v2(modern, "Modern:v2", "Vault");

        EconomyConverter.Report up = EconomyConverter.convert(v1, v2, List.of(alice, carol), true);
        assertEquals(0, up.failed(), up.failures().toString());
        assertEquals(1, up.unchanged());
        assertEquals(d("10.10"), modern.balances.get(alice.getUniqueId()), "rounded to the v2 provider's 2 digits");

        modern.balances.put(alice.getUniqueId(), d("99.99"));
        int modernWrites = modern.writes.get();
        EconomyConverter.Report down = EconomyConverter.convert(v2, v1, List.of(alice, carol), true);
        assertEquals(0, down.failed(), down.failures().toString());
        assertEquals(99.99, legacy.balances.get("alice"), 1e-9);
        assertEquals(modernWrites, modern.writes.get(), "the source is never written");
    }
}
