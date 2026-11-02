package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import dev.yanianz.atrium.core.user.UserManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Persists which profile a player's profile-scoped Atrium data belongs to, as the account-wide user component
 * {@code sourbycraft:profile_owner} (table {@code {prefix}sourbycraft_profile_owner}). Loaded with the user
 * before join, so the owner is known when SourbyCraft first captures or the first reconciliation runs.
 */
final class ProfileOwnerStore implements ProfileOwnership.OwnerStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "sourbycraft_profile_owner",
            "CREATE TABLE {prefix}sourbycraft_profile_owner (uuid CHAR(36) NOT NULL, profile CHAR(36) NOT NULL, "
                    + "updated BIGINT NOT NULL, PRIMARY KEY (uuid))"));

    /** Component value; {@code profile == null} = unknown (nothing stored). */
    record Owner(@Nullable UUID profile) {
        static final Owner UNKNOWN = new Owner(null);
    }

    private final Database db;
    private final UserManager users;
    private final Logger logger;
    final UserComponentType<Owner> component;

    ProfileOwnerStore(Database db, UserManager users, Logger logger) {
        this.db = db;
        this.users = users;
        this.logger = logger;
        this.component = new UserComponentType<>("sourbycraft:profile_owner", this::loadRow, this::saveRow, () -> Owner.UNKNOWN);
    }

    @Override
    public @Nullable UUID load(UUID player) {
        Optional<AtriumUser> user = users.cached(player);
        return user.map(u -> u.get(component).profile()).orElse(null);
    }

    @Override
    public void save(UUID player, @Nullable UUID owner) {
        Optional<AtriumUser> user = users.cached(player);
        if (user.isEmpty()) {
            logger.warn("Cannot record the profile owner of {}: the player's data is not loaded", player);
            return;
        }
        user.get().update(component, old -> new Owner(owner));
        // Written now rather than with the next flush, so the owner follows the data it describes closely.
        users.saveNow(player).exceptionally(error -> {
            logger.warn("Saving the profile owner of {} failed; the save loop retries", player, error);
            return null;
        });
    }

    private CompletableFuture<Owner> loadRow(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT profile FROM " + db.table("sourbycraft_profile_owner")
                    + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Owner(UUID.fromString(rs.getString(1))) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> saveRow(UUID uuid, Owner owner) {
        String table = db.table("sourbycraft_profile_owner");
        return db.transaction(c -> {
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                delete.setString(1, uuid.toString());
                delete.executeUpdate();
            }
            if (owner.profile() != null) {
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO " + table
                        + " (uuid, profile, updated) VALUES (?, ?, ?)")) {
                    Database.bind(insert, uuid.toString(), owner.profile().toString(), System.currentTimeMillis());
                    insert.executeUpdate();
                }
            }
            return null;
        });
    }
}
