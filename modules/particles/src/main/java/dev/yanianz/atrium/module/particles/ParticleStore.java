package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import dev.yanianz.atrium.module.particles.shape.Style;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Color;
import org.bukkit.Particle;

/** Persists the {@link ParticleProfile} of a player in two small tables. */
final class ParticleStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "particle cosmetics",
            "CREATE TABLE {prefix}particles_player (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "hide_others BOOLEAN NOT NULL DEFAULT FALSE, hidden BOOLEAN NOT NULL DEFAULT FALSE)",
            "CREATE TABLE {prefix}particles_effects (uuid CHAR(36) NOT NULL, style VARCHAR(32) NOT NULL, "
                    + "particle VARCHAR(48) NOT NULL, colour_mode VARCHAR(16) NOT NULL, colour1 INT NOT NULL, "
                    + "colour2 INT NOT NULL, radius DOUBLE NOT NULL, density DOUBLE NOT NULL, speed DOUBLE NOT NULL, "
                    + "PRIMARY KEY (uuid, style))"));

    private final Database db;
    final UserComponentType<ParticleProfile> component;

    ParticleStore(Database db) {
        this.db = db;
        this.component = new UserComponentType<>("particles:profile", this::load, this::save,
                () -> ParticleProfile.EMPTY);
    }

    private CompletableFuture<ParticleProfile> load(UUID uuid) {
        return db.query(connection -> {
            boolean hideOthers = false;
            boolean hidden = false;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT hide_others, hidden FROM " + db.table("particles_player") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        hideOthers = rs.getBoolean(1);
                        hidden = rs.getBoolean(2);
                    } else {
                        // No player row: the account never changed its particle settings.
                        return null;
                    }
                }
            }

            List<ParticleEffect> effects = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT style, particle, colour_mode, colour1, colour2, radius, density, speed FROM "
                            + db.table("particles_effects") + " WHERE uuid = ? ORDER BY style")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Style style = Style.byName(rs.getString(1));
                        Particle particle = ParticleNames.resolve(rs.getString(2));
                        if (style == null || particle == null || !ParticleData.supported(particle)) {
                            continue;
                        }
                        ParticleColours.Mode mode = ParticleColours.Mode.byName(rs.getString(3));
                        ParticleColours colours = new ParticleColours(
                                mode == null ? ParticleColours.Mode.FIXED : mode,
                                Color.fromRGB(rs.getInt(4)),
                                Color.fromRGB(rs.getInt(5)));
                        effects.add(new ParticleEffect(style, particle, colours, rs.getDouble(6), rs.getDouble(7),
                                rs.getDouble(8)));
                    }
                }
            }
            return new ParticleProfile(effects, hideOthers, hidden);
        });
    }

    private CompletableFuture<Void> save(UUID uuid, ParticleProfile profile) {
        return db.transaction(connection -> {
            deleteEffects(connection, uuid);
            deletePlayer(connection, uuid);
            if (profile.effects().isEmpty() && !profile.hideOthers() && !profile.hidden()) {
                return null;
            }
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + db.table("particles_player")
                    + " (uuid, hide_others, hidden) VALUES (?, ?, ?)")) {
                Database.bind(ps, uuid.toString(), profile.hideOthers(), profile.hidden());
                ps.executeUpdate();
            }
            for (ParticleEffect effect : profile.effects()) {
                try (PreparedStatement ps = connection.prepareStatement("INSERT INTO " + db.table("particles_effects")
                        + " (uuid, style, particle, colour_mode, colour1, colour2, radius, density, speed) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    Database.bind(ps, uuid.toString(), effect.style().key(), ParticleNames.key(effect.particle()),
                            effect.colours().mode().name().toLowerCase(java.util.Locale.ROOT),
                            effect.colours().primary().asRGB(), effect.colours().secondary().asRGB(),
                            effect.radius(), effect.density(), effect.speed());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private void deletePlayer(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("particles_player") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    private void deleteEffects(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM " + db.table("particles_effects") + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }
}
