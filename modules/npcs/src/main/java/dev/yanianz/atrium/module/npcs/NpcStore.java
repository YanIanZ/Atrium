package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * The source of truth for NPCs. One row per NPC plus a small cache of resolved skin textures. The cache
 * never becomes the source of truth for an NPC: a missing or stale texture simply triggers a new lookup.
 */
final class NpcStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "npc definitions and skin cache",
            dialect -> List.of(
                    "CREATE TABLE {prefix}npcs_npc ("
                            + (dialect == Dialect.SQLITE
                                    ? "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                    : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, ")
                            + "name VARCHAR(32) NOT NULL, name_lower VARCHAR(32) NOT NULL UNIQUE, "
                            + "display_name VARCHAR(512) NOT NULL, world VARCHAR(64) NOT NULL, "
                            + "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
                            + "yaw DOUBLE NOT NULL, pitch DOUBLE NOT NULL, skin VARCHAR(32), "
                            + "equipment TEXT NOT NULL, pose VARCHAR(32) NOT NULL, look_at BOOLEAN NOT NULL, "
                            + "look_range DOUBLE NOT NULL, glow VARCHAR(16) NOT NULL, scale DOUBLE NOT NULL, "
                            + "nameplate BOOLEAN NOT NULL, actions_left TEXT NOT NULL, actions_right TEXT NOT NULL, "
                            + "permission_node VARCHAR(128), cooldown_millis BIGINT NOT NULL, cost VARCHAR(64) NOT NULL, "
                            + "engine VARCHAR(16) NOT NULL)",
                    "CREATE INDEX {prefix}npcs_npc_lookup ON {prefix}npcs_npc (name_lower)",
                    "CREATE TABLE {prefix}npcs_skin_cache (name_lower VARCHAR(16) NOT NULL PRIMARY KEY, "
                            + "name VARCHAR(16) NOT NULL, profile_id CHAR(36), texture_value TEXT, "
                            + "texture_signature TEXT, fetched_at BIGINT NOT NULL)")));

    private static final String COLUMNS = "name, name_lower, display_name, world, x, y, z, yaw, pitch, skin, "
            + "equipment, pose, look_at, look_range, glow, scale, nameplate, actions_left, actions_right, "
            + "permission_node, cooldown_millis, cost, engine";

    private final Database db;
    private final String table;
    private final String skins;
    private final java.util.function.Consumer<String> warn;

    NpcStore(Database db, java.util.function.Consumer<String> warn) {
        this.db = db;
        this.table = db.table("npcs_npc");
        this.skins = db.table("npcs_skin_cache");
        this.warn = warn;
    }

    CompletableFuture<List<NpcDef>> loadAll() {
        return db.query(c -> {
            List<NpcDef> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, " + COLUMNS + " FROM " + table
                    + " ORDER BY name_lower")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(read(rs));
                    }
                }
            }
            return out;
        });
    }

    /** Inserts or updates the row and returns the stored value with its id. */
    CompletableFuture<NpcDef> save(NpcDef def) {
        String sql = db.dialect().upsert(table, COLUMNS,
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?",
                "name_lower", "name, display_name, world, x, y, z, yaw, pitch, skin, equipment, pose, look_at, "
                        + "look_range, glow, scale, nameplate, actions_left, actions_right, permission_node, "
                        + "cooldown_millis, cost, engine");
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, def);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + table + " WHERE name_lower = ?")) {
                ps.setString(1, def.key());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? def.withId(rs.getLong(1)) : def;
                }
            }
        });
    }

    CompletableFuture<Boolean> delete(String name) {
        return db.update("DELETE FROM {prefix}npcs_npc WHERE name_lower = ?", name.toLowerCase(Locale.ROOT))
                .thenApply(count -> count > 0);
    }

    /** Moves every NPC from one stored engine to another (an empty stored engine means "auto"). */
    CompletableFuture<Integer> migrateEngine(NpcType from, NpcType to, NpcType autoResolved) {
        return db.query(c -> {
            int changed = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT id, engine FROM " + table)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String stored = rs.getString(2);
                        NpcType current = NpcType.parse(stored).orElse(NpcType.AUTO);
                        NpcType effective = current == NpcType.AUTO ? autoResolved : current;
                        if (effective == from) {
                            changed++;
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET engine = ? WHERE engine = ?")) {
                ps.setString(1, to.configName());
                ps.setString(2, from.configName());
                ps.executeUpdate();
            }
            if (from == autoResolved || from == NpcType.AUTO) {
                // Rows stored as "auto" (or an older empty value) are updated as well.
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + table + " SET engine = ? WHERE engine = ? OR engine = ''")) {
                    ps.setString(1, to.configName());
                    ps.setString(2, NpcType.AUTO.configName());
                    ps.executeUpdate();
                }
            }
            return changed;
        });
    }

    private NpcDef read(ResultSet rs) throws SQLException {
        long id = rs.getLong(1);
        String name = rs.getString(2);
        int i = 4; // positions 1-3 are id, name and the name_lower unique key
        String display = rs.getString(i++);
        String world = rs.getString(i++);
        double x = rs.getDouble(i++);
        double y = rs.getDouble(i++);
        double z = rs.getDouble(i++);
        float yaw = (float) rs.getDouble(i++);
        float pitch = (float) rs.getDouble(i++);
        String skin = rs.getString(i++);
        Map<String, String> equipment = parseEquipment(rs.getString(i++));
        String pose = rs.getString(i++);
        boolean lookAt = rs.getBoolean(i++);
        double lookRange = rs.getDouble(i++);
        String glow = rs.getString(i++);
        double scale = rs.getDouble(i++);
        boolean nameplate = rs.getBoolean(i++);
        List<String> left = parseLines(rs.getString(i++));
        List<String> right = parseLines(rs.getString(i++));
        String permission = rs.getString(i++);
        long cooldown = rs.getLong(i++);
        BigDecimal cost = parseCost(rs.getString(i++));
        NpcType engine = NpcType.parse(rs.getString(i++)).orElse(NpcType.AUTO);
        return new NpcDef(id, name, display, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange, glow,
                scale, nameplate, left, right, permission, cooldown, cost, engine);
    }

    private static void bind(PreparedStatement ps, NpcDef def) throws SQLException {
        int i = 1;
        ps.setString(i++, def.name());
        ps.setString(i++, def.key());
        ps.setString(i++, def.displayName());
        ps.setString(i++, def.world());
        ps.setDouble(i++, def.x());
        ps.setDouble(i++, def.y());
        ps.setDouble(i++, def.z());
        ps.setDouble(i++, def.yaw());
        ps.setDouble(i++, def.pitch());
        if (def.skin() == null) {
            ps.setNull(i++, Types.VARCHAR);
        } else {
            ps.setString(i++, def.skin());
        }
        ps.setString(i++, writeEquipment(def.equipment()));
        ps.setString(i++, def.pose());
        ps.setBoolean(i++, def.lookAt());
        ps.setDouble(i++, def.lookRange());
        ps.setString(i++, def.glow());
        ps.setDouble(i++, def.scale());
        ps.setBoolean(i++, def.nameplate());
        ps.setString(i++, String.join("\n", def.leftActions()));
        ps.setString(i++, String.join("\n", def.rightActions()));
        if (def.permission() == null) {
            ps.setNull(i++, Types.VARCHAR);
        } else {
            ps.setString(i++, def.permission());
        }
        ps.setLong(i++, def.cooldownMillis());
        ps.setString(i++, def.cost().stripTrailingZeros().toPlainString());
        ps.setString(i, def.engine().configName());
    }

    private static List<String> parseLines(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n", -1)) {
            if (!line.isBlank()) {
                out.add(line.trim());
            }
        }
        return List.copyOf(out);
    }

    private static String writeEquipment(Map<String, String> equipment) {
        if (equipment.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : equipment.entrySet()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    private Map<String, String> parseEquipment(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : raw.split("\n")) {
            int split = line.indexOf('=');
            if (split <= 0 || split == line.length() - 1) {
                warn.accept("Ignoring a malformed stored equipment entry: " + line);
                continue;
            }
            String slot = line.substring(0, split).trim().toUpperCase(Locale.ROOT);
            if (NpcDef.SLOTS.contains(slot)) {
                out.put(slot, line.substring(split + 1).trim());
            } else {
                warn.accept("Ignoring an unknown stored equipment slot: " + slot);
            }
        }
        return Map.copyOf(out);
    }

    private BigDecimal parseCost(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            warn.accept("Ignoring a malformed stored NPC cost: " + raw);
            return BigDecimal.ZERO;
        }
    }

    // ---- skin cache ----

    record CachedSkin(String name, @Nullable UUID profileId, @Nullable String value, @Nullable String signature,
            long fetchedAt) {}

    CompletableFuture<Optional<CachedSkin>> cachedSkin(String name) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, profile_id, texture_value, "
                    + "texture_signature, fetched_at FROM " + skins + " WHERE name_lower = ?")) {
                ps.setString(1, name.toLowerCase(Locale.ROOT));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<CachedSkin>empty();
                    }
                    UUID uuid = parseUuid(rs.getString(2));
                    return Optional.of(new CachedSkin(rs.getString(1), uuid, rs.getString(3), rs.getString(4),
                            rs.getLong(5)));
                }
            }
        });
    }

    CompletableFuture<Void> putCachedSkin(CachedSkin skin) {
        String sql = db.dialect().upsert(skins, "name_lower, name, profile_id, texture_value, texture_signature, fetched_at",
                "?, ?, ?, ?, ?, ?", "name_lower", "name, profile_id, texture_value, texture_signature, fetched_at");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, skin.name().toLowerCase(Locale.ROOT));
                ps.setString(2, skin.name());
                if (skin.profileId() == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, skin.profileId().toString());
                }
                ps.setString(4, skin.value());
                ps.setString(5, skin.signature());
                ps.setLong(6, skin.fetchedAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private @Nullable UUID parseUuid(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            warn.accept("Ignoring a malformed cached skin profile id: " + raw);
            return null;
        }
    }
}
