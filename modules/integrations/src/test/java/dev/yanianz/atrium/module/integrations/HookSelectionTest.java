package dev.yanianz.atrium.module.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HookSelectionTest {
    @Test
    void selectsOnlyAvailableHooksAndSkipsFailures() {
        List<String> failed = new ArrayList<>();
        List<String> selected = HookSelection.select(
                List.of("a", "b", "c", "d"),
                Set.of("a", "b", "d")::contains,
                id -> {
                    if (id.equals("b")) {
                        throw new IllegalStateException("incompatible");
                    }
                    return id.equals("c") ? null : id;
                },
                failed::add);
        assertEquals(List.of("a", "d"), selected);
        assertEquals(List.of("b"), failed);
    }

    @Test
    void emptyWhenNothingIsAvailable() {
        assertEquals(List.of(), HookSelection.select(List.of("a"), id -> false, id -> id, id -> {}));
    }
}
