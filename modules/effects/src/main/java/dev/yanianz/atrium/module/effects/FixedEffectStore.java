package dev.yanianz.atrium.module.effects;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.slf4j.Logger;

/** Fixed effects table {@code {prefix}effects_fixed}. */
final class FixedEffectStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "effects_fixed",
            "CREATE TABLE {prefix}effects_fixed (name_lower VARCHAR(32) NOT NULL PRIMARY KEY, "
                    + "name VARCHAR(32) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, "
                    + "y DOUBLE NOT NULL, z DOUBLE NOT NULL, shape VARCHAR(16) NOT NULL, "
                    + "particle VARCHAR(64) NOT NULL, colour VARCHAR(8), radius DOUBLE NOT NULL, "
                    + "height DOUBLE NOT NULL, points INT NOT NULL, speed DOUBLE NOT NULL, "
                    + "interval_ticks INT NOT NULL, created BIGINT NOT NULL)"));

    private static final String COLUMNS = "name_lower, name, world, x, y, z, shape, particle, colour, "
            + "radius, height, points, speed, interval_ticks, created";

    private final Database db;
    private final Logger logger;

    FixedEffectStore(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    CompletableFuture<List<FixedEffect>> loadAll() {
        return db.query(c -> {
            List<FixedEffect> effects = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM "
                    + db.table("effects_fixed") + " ORDER BY name_lower");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        FixedEffect effect = read(rs);
                        if (effect != null) {
                            effects.add(effect);
                        }
                    } catch (java.sql.SQLException e) {
                        logger.warn("Skipping an unreadable fixed effect row", e);
                    }
                }
            }
            return effects;
        });
    }

    /** Returns null and logs when a stored row can no longer be parsed (for example a removed particle). */
    private FixedEffect read(ResultSet rs) throws java.sql.SQLException {
        String name = rs.getString("name");
        Shape shape = Shape.parse(rs.getString("shape"));
        Particle particle = ParticleData.particle(rs.getString("particle"));
        if (shape == null || particle == null || !ParticleData.supported(particle)) {
            logger.warn("Skipping fixed effect '{}': shape '{}' or particle '{}' is not supported",
                    name, rs.getString("shape"), rs.getString("particle"));
            return null;
        }
        Color colour = ParticleData.colour(rs.getString("colour")).orElse(null);
        ParticleData.Check check = ParticleData.check(particle, colour, colour, 1.0);
        if (!check.valid()) {
            logger.warn("Skipping fixed effect '{}': {}", name, check.error());
            return null;
        }
        return new FixedEffect(name,
                new StoredLocation(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"),
                        rs.getDouble("z"), 0f, 0f),
                shape, particle, colour, rs.getDouble("radius"), rs.getDouble("height"),
                rs.getInt("points"), rs.getDouble("speed"), rs.getInt("interval_ticks"));
    }

    CompletableFuture<Void> upsert(FixedEffect effect) {
        String sql = db.dialect().upsert(db.table("effects_fixed"), COLUMNS,
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?", "name_lower",
                "name, world, x, y, z, shape, particle, colour, radius, height, points, speed, "
                        + "interval_ticks, created");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, effect.key(), effect.name(), effect.location().world(),
                        effect.location().x(), effect.location().y(), effect.location().z(),
                        effect.shape().name(), effect.particle().name(),
                        effect.colour() == null ? null : ParticleData.formatColour(effect.colour()),
                        effect.radius(), effect.height(), effect.points(), effect.speed(),
                        effect.intervalTicks(), System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    CompletableFuture<Boolean> delete(String key) {
        return db.update("DELETE FROM {prefix}effects_fixed WHERE name_lower = ?", key).thenApply(n -> n > 0);
    }
}
