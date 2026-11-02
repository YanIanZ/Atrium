package dev.yanianz.atrium.core.user;

import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Persisted per-player cooldowns shared by all modules. Keys are namespaced by convention:
 * {@code "<module>:<thing>"}, e.g. {@code "kits:starter"}. Expired entries are dropped on save.
 *
 * <p>Use {@link #tryAcquire} for "check and start" in one atomic step, so spamming a command cannot pass
 * the check twice.
 */
public final class Cooldowns {
    public static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "cooldowns",
            "CREATE TABLE {prefix}cooldowns (uuid CHAR(36) NOT NULL, cooldown_key VARCHAR(128) NOT NULL, "
                    + "expires BIGINT NOT NULL, PRIMARY KEY (uuid, cooldown_key))"));

    /** Immutable map key → expiry (epoch millis). */
    public record State(Map<String, Long> expiries) {
        public State {
            expiries = Map.copyOf(expiries);
        }
    }

    private final Database db;
    private final UserComponentType<State> component;

    public Cooldowns(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("core:cooldowns", this::load, this::save, () -> new State(Map.of()));
    }

    public UserComponentType<State> component() {
        return component;
    }

    /** Time left, or {@link Duration#ZERO} when the cooldown is not running. */
    public Duration remaining(AtriumUser user, String key) {
        Long expires = user.get(component).expiries().get(key);
        long left = expires == null ? 0 : expires - System.currentTimeMillis();
        return left > 0 ? Duration.ofMillis(left) : Duration.ZERO;
    }

    public void start(AtriumUser user, String key, Duration duration) {
        long expires = System.currentTimeMillis() + duration.toMillis();
        user.update(component, state -> with(state, key, expires));
    }

    public void clear(AtriumUser user, String key) {
        user.update(component, state -> {
            Map<String, Long> copy = new HashMap<>(state.expiries());
            copy.remove(key);
            return new State(copy);
        });
    }

    /**
     * Starts the cooldown if it is not running. Returns {@link Duration#ZERO} on success, otherwise the time
     * left (and nothing changes). Atomic per user.
     */
    public Duration tryAcquire(AtriumUser user, String key, Duration duration) {
        long now = System.currentTimeMillis();
        long[] left = {0};
        user.update(component, state -> {
            Long expires = state.expiries().get(key);
            if (expires != null && expires > now) {
                left[0] = expires - now;
                return state;
            }
            return with(state, key, now + duration.toMillis());
        });
        return left[0] > 0 ? Duration.ofMillis(left[0]) : Duration.ZERO;
    }

    private static State with(State state, String key, long expires) {
        Map<String, Long> copy = new HashMap<>(state.expiries());
        long now = System.currentTimeMillis();
        copy.values().removeIf(e -> e <= now);
        copy.put(key, expires);
        return new State(copy);
    }

    private CompletableFuture<State> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT cooldown_key, expires FROM " + db.table("cooldowns")
                    + " WHERE uuid = ? AND expires > ?")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, Instant.now().toEpochMilli());
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, Long> map = new HashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString(1), rs.getLong(2));
                    }
                    return new State(map);
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, State state) {
        long now = System.currentTimeMillis();
        return db.transaction(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + db.table("cooldowns") + " WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + db.table("cooldowns")
                    + " (uuid, cooldown_key, expires) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, Long> entry : state.expiries().entrySet()) {
                    if (entry.getValue() > now) {
                        Database.bind(insert, uuid.toString(), entry.getKey(), entry.getValue());
                        insert.addBatch();
                    }
                }
                insert.executeBatch();
            }
            return null;
        });
    }
}
