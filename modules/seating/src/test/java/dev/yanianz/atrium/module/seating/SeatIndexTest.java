package dev.yanianz.atrium.module.seating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeatIndexTest {
    private static SeatIndex.Entry entry(UUID player, UUID seat, boolean blockSeat, int x) {
        return new SeatIndex.Entry(player, seat, SeatIndex.Mode.SIT, "world", x, 64, 0, blockSeat,
                64.5, 64.0);
    }

    @Test
    void tracksAPlayerAndSeat() {
        SeatIndex index = new SeatIndex();
        UUID player = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        SeatIndex.Entry entry = entry(player, seat, true, 10);
        index.put(entry);

        assertTrue(index.isSeated(player));
        assertSame(entry, index.entry(player));
        assertEquals(1, index.size());
    }

    @Test
    void replacingASeatForgetsTheOldSeatMapping() {
        SeatIndex index = new SeatIndex();
        UUID player = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        index.put(entry(player, first, true, 0));
        SeatIndex.Entry previous = index.put(entry(player, second, true, 1));

        assertEquals(first, previous.seatId());
        assertNull(index.removeSeat(first));
        assertEquals(1, index.size());
        assertEquals(second, index.entry(player).seatId());
    }

    @Test
    void removingByPlayerClearsTheSeatMapping() {
        SeatIndex index = new SeatIndex();
        UUID player = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        index.put(entry(player, seat, true, 0));

        SeatIndex.Entry removed = index.removePlayer(player);
        assertEquals(seat, removed.seatId());
        assertFalse(index.isSeated(player));
        assertNull(index.removeSeat(seat));
        assertEquals(0, index.size());
    }

    @Test
    void removingBySeatReturnsTheEntry() {
        SeatIndex index = new SeatIndex();
        UUID player = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        index.put(entry(player, seat, false, 0));

        SeatIndex.Entry removed = index.removeSeat(seat);
        assertEquals(player, removed.playerId());
        assertNull(index.entry(player));
    }

    @Test
    void unknownSeatIsIgnored() {
        SeatIndex index = new SeatIndex();
        assertNull(index.removeSeat(UUID.randomUUID()));
        assertNull(index.removePlayer(UUID.randomUUID()));
    }

    @Test
    void findsEverySeatOnABlockButNotOpenSeats() {
        SeatIndex index = new SeatIndex();
        UUID blockSeat = UUID.randomUUID();
        index.put(entry(UUID.randomUUID(), blockSeat, true, 5));
        index.put(entry(UUID.randomUUID(), UUID.randomUUID(), true, 5));
        index.put(entry(UUID.randomUUID(), UUID.randomUUID(), false, 5));
        index.put(entry(UUID.randomUUID(), UUID.randomUUID(), true, 6));

        assertEquals(2, index.at("world", 5, 64, 0).size());
        assertEquals(0, index.at("world_nether", 5, 64, 0).size());
        assertEquals(0, index.at("world", 5, 65, 0).size());
    }

    @Test
    void clearRemovesEverything() {
        SeatIndex index = new SeatIndex();
        for (int i = 0; i < 3; i++) {
            index.put(entry(UUID.randomUUID(), UUID.randomUUID(), true, i));
        }
        List<SeatIndex.Entry> removed = index.clear();
        assertEquals(3, removed.size());
        assertEquals(0, index.size());
        assertTrue(index.all().isEmpty());
    }

    @Test
    void entriesAreImmutableSnapshots() {
        SeatIndex index = new SeatIndex();
        index.put(entry(UUID.randomUUID(), UUID.randomUUID(), true, 0));
        List<SeatIndex.Entry> first = index.all();
        index.clear();
        assertEquals(1, first.size());
        assertTrue(index.all().isEmpty());
    }
}
