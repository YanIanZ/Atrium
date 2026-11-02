package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PatrolPickerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @Test
    void neverVisitedComesFirst() {
        Map<UUID, Long> visits = new HashMap<>();
        visits.put(A, 500L);
        assertEquals(B, PatrolPicker.next(List.of(A, B), visits).orElseThrow());
    }

    @Test
    void theOldestVisitWins() {
        Map<UUID, Long> visits = new HashMap<>();
        visits.put(A, 900L);
        visits.put(B, 100L);
        visits.put(C, 500L);
        assertEquals(B, PatrolPicker.next(List.of(A, B, C), visits).orElseThrow());
    }

    @Test
    void tiesKeepTheCandidateOrder() {
        Map<UUID, Long> visits = new HashMap<>();
        visits.put(A, 100L);
        visits.put(B, 100L);
        assertEquals(A, PatrolPicker.next(List.of(A, B), visits).orElseThrow());
        assertEquals(B, PatrolPicker.next(List.of(B, A), visits).orElseThrow());
    }

    @Test
    void anEmptyCandidateListPicksNobody() {
        assertTrue(PatrolPicker.next(List.of(), Map.of()).isEmpty());
    }

    @Test
    void negativeTimestampsAreStillCompared() {
        Map<UUID, Long> visits = new HashMap<>();
        visits.put(A, 0L);
        visits.put(B, -5L);
        assertEquals(B, PatrolPicker.next(List.of(A, B), visits).orElseThrow());
    }
}
