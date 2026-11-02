package dev.yanianz.atrium.module.seating;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure bookkeeping for active seats: which player rides which seat entity and which block (if any)
 * the seat belongs to. Kept free of Bukkit so cleanup rules can be tested directly.
 *
 * <p>Every method is synchronized. Seat operations happen only when a player sits, stands or a chunk
 * loads, never per tick, so the lock is never on a hot path.
 */
final class SeatIndex {
    enum Mode {
        SIT, LAY
    }

    /**
     * One active seat.
     *
     * @param blockSeat true when the seat belongs to a stair/slab/carpet block, false for /sit in the open
     * @param seatY     world Y of the seat entity
     * @param exitY     world Y the player must end up at when the seat is removed
     */
    record Entry(UUID playerId, UUID seatId, Mode mode, String world,
            int blockX, int blockY, int blockZ, boolean blockSeat, double seatY, double exitY) {

        boolean at(String world, int x, int y, int z) {
            return blockSeat && this.world.equals(world) && blockX == x && blockY == y && blockZ == z;
        }
    }

    private final Map<UUID, Entry> byPlayer = new LinkedHashMap<>();
    private final Map<UUID, UUID> playerBySeat = new LinkedHashMap<>();

    /** Stores an entry and returns the previous entry of the same player (it must be cleaned up). */
    synchronized Entry put(Entry entry) {
        Entry previous = byPlayer.put(entry.playerId(), entry);
        if (previous != null) {
            playerBySeat.remove(previous.seatId());
        }
        playerBySeat.put(entry.seatId(), entry.playerId());
        return previous;
    }

    synchronized Entry entry(UUID playerId) {
        return byPlayer.get(playerId);
    }

    synchronized boolean isSeated(UUID playerId) {
        return byPlayer.containsKey(playerId);
    }

    synchronized Entry removePlayer(UUID playerId) {
        Entry removed = byPlayer.remove(playerId);
        if (removed != null) {
            playerBySeat.remove(removed.seatId());
        }
        return removed;
    }

    synchronized Entry removeSeat(UUID seatId) {
        UUID playerId = playerBySeat.remove(seatId);
        if (playerId == null) {
            return null;
        }
        Entry removed = byPlayer.remove(playerId);
        if (removed != null && !removed.seatId().equals(seatId)) {
            // The player already moved to another seat; keep the mapping for that newer seat.
            playerBySeat.put(removed.seatId(), playerId);
            return null;
        }
        return removed;
    }

    /** Every seat that belongs to the given block. Multiple players may sit on one block. */
    synchronized List<Entry> at(String world, int x, int y, int z) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : byPlayer.values()) {
            if (entry.at(world, x, y, z)) {
                found.add(entry);
            }
        }
        return found;
    }

    synchronized List<Entry> all() {
        return List.copyOf(byPlayer.values());
    }

    /** Removes and returns every entry. Used when the module is disabled. */
    synchronized List<Entry> clear() {
        List<Entry> entries = new ArrayList<>(byPlayer.values());
        byPlayer.clear();
        playerBySeat.clear();
        return entries;
    }

    synchronized int size() {
        return byPlayer.size();
    }
}
