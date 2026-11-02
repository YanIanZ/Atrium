package dev.yanianz.atrium.module.dialogs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Permission and placeholder conditions on dialogs and buttons. */
class ConditionTest {
    private static Predicate<String> permissions(String... nodes) {
        Set<String> granted = Set.of(nodes);
        return granted::contains;
    }

    private static Function<String, String> values(Map<String, String> map) {
        return map::get;
    }

    @Test void permissionConditions() {
        Condition condition = Condition.parse("permission:atrium.dialog.vip");
        assertEquals(Condition.Type.PERMISSION, condition.type());
        assertEquals("atrium.dialog.vip", condition.key());
        assertTrue(condition.test(permissions("atrium.dialog.vip"), values(Map.of())));
        assertFalse(condition.test(permissions(), values(Map.of())));

        Condition negated = Condition.parse("!perm:atrium.dialog.vip");
        assertTrue(negated.negated());
        assertTrue(negated.test(permissions(), values(Map.of())));
        assertFalse(negated.test(permissions("atrium.dialog.vip"), values(Map.of())));
    }

    @Test void numericAndTextComparisons() {
        Function<String, String> online = values(Map.of("%server_online%", "12", "%rank%", "VIP"));
        assertTrue(Condition.parse("%server_online% >= 5").test(permissions(), online));
        assertFalse(Condition.parse("%server_online% > 12").test(permissions(), online));
        assertTrue(Condition.parse("%server_online% == 12").test(permissions(), online));
        assertTrue(Condition.parse("%server_online% <= 12").test(permissions(), online));
        assertTrue(Condition.parse("%server_online% != 11").test(permissions(), online));
        assertTrue(Condition.parse("%rank% == vip").test(permissions(), online));
        assertTrue(Condition.parse("%rank% != member").test(permissions(), online));
        // Ordering a non-number is never guessed to be true.
        assertFalse(Condition.parse("%rank% > 1").test(permissions(), online));
    }

    @Test void booleanStyleCondition() {
        Condition condition = Condition.parse("%vip%");
        assertNull(condition.op());
        assertTrue(condition.test(permissions(), values(Map.of("%vip%", "true"))));
        assertTrue(condition.test(permissions(), values(Map.of("%vip%", "YES"))));
        assertTrue(condition.test(permissions(), values(Map.of("%vip%", "1"))));
        assertFalse(condition.test(permissions(), values(Map.of("%vip%", "false"))));
        assertFalse(condition.test(permissions(), values(Map.of())));
    }

    @Test void missingPlaceholderIsAlwaysFalse() {
        assertFalse(Condition.parse("%missing% == x").test(permissions(), values(Map.of())));
        assertFalse(Condition.parse("%missing% != x").test(permissions(), values(Map.of("%missing%", ""))));
        assertFalse(Condition.parse("%missing%").test(permissions(), values(Map.of("%missing%", " "))));
    }

    @Test void builtInNamesNeedNoPercentSigns() {
        Condition condition = Condition.parse("online >= 3");
        assertEquals("online", condition.key());
        assertTrue(condition.test(permissions(), values(Map.of("online", "4"))));
        assertFalse(condition.test(permissions(), values(Map.of("online", "2"))));
    }

    @Test void doubleNegationCancelsOut() {
        Condition condition = Condition.parse("!!permission:a");
        assertFalse(condition.negated());
        assertTrue(condition.test(permissions("a"), values(Map.of())));
    }

    @Test void invalidConditionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Condition.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("!"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("permission:"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("permission:bad node"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("%online >= 3"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("%online% >="));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse(">= 3"));
        assertThrows(IllegalArgumentException.class, () -> Condition.parse("%online% ~ 3"));
    }

    @Test void sourceIsKeptForPreviewAndLogs() {
        assertEquals("%online% >= 3", Condition.parse("  %online% >= 3  ").source());
        assertEquals("!permission:x", Condition.parse("!permission:x").source());
    }
}
