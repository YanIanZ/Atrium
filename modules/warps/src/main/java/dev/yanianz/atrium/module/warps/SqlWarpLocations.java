package dev.yanianz.atrium.module.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Extra random locations of a warp, in {@code {prefix}warps_locations}. The primary location stored on the
 * warp itself is not duplicated here; the deck is built from both.
 *
 * <p>Adding is one transaction: the slot number is read and the row written together, so two administrators
 * adding a location at the same time cannot overwrite each other. A warp holds at most
 * {@value #MAX_LOCATIONS} extra locations.
 */
final class SqlWarpLocations {
    static final int MAX_LOCATIONS = 32;

    private final Database db;

    SqlWarpLocations(Database db) {
        this.db = db;
    }

    /** Every warp's extra locations, ordered by the order they were added. */
    CompletableFuture<Map<String, List<StoredLocation>>> loadAll() {
        return db.query(c -> {
            Map<String, List<StoredLocation>> map = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT warp_lower, world, x, y, z, yaw, pitch FROM "
                    + db.table("warps_locations") + " ORDER BY warp_lower, slot");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    map.computeIfAbsent(rs.getString(1), key -> new ArrayList<>())
                            .add(new StoredLocation(rs.getString(2), rs.getDouble(3), rs.getDouble(4),
                                    rs.getDouble(5), rs.getFloat(6), rs.getFloat(7)));
                }
            }
            Map<String, List<StoredLocation>> frozen = new LinkedHashMap<>();
            map.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
            return Map.copyOf(frozen);
        });
    }

    /**
     * Appends {@code location} to the warp. Completes with the new number of extra locations, or {@code -1}
     * when the warp already holds {@value #MAX_LOCATIONS} of them.
     */
    CompletableFuture<Integer> add(String warpKey, StoredLocation location) {
        return db.transaction(c -> {
            int next;
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(slot), 0) FROM "
                    + db.table("warps_locations") + " WHERE warp_lower = ?")) {
                ps.setString(1, warpKey);
                try (ResultSet rs = ps.executeQuery()) {
                    next = rs.next() ? rs.getInt(1) + 1 : 1;
                }
            }
            if (next > MAX_LOCATIONS) {
                return -1;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("warps_locations")
                    + " (warp_lower, slot, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                Database.bind(ps, warpKey, next, location.world(), location.x(), location.y(), location.z(),
                        location.yaw(), location.pitch());
                ps.executeUpdate();
            }
            return next;
        });
    }

    /** Removes every extra location of the warp; completes with the number of removed rows. */
    CompletableFuture<Integer> clear(String warpKey) {
        return db.update("DELETE FROM {prefix}warps_locations WHERE warp_lower = ?", warpKey);
    }

    /** Moves the rows of a renamed warp to its new key. */
    CompletableFuture<Integer> rename(String oldKey, String newKey) {
        return db.update("UPDATE {prefix}warps_locations SET warp_lower = ? WHERE warp_lower = ?", newKey, oldKey);
    }
}
