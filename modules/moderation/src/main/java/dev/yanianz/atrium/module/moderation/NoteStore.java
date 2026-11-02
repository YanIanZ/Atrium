package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.core.storage.Database;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Staff notes about an account ({@code {prefix}moderation_notes}). Notes are internal: they never appear in
 * punishments, are never announced and every read or write goes through a command with its own permission.
 */
final class NoteStore {
    /** One note. {@code actor} is {@code null} when it was written from the console. */
    record Note(long id, UUID target, String targetName, @Nullable UUID actor, String actorName, String body,
            Instant created) {}

    private final Database db;
    private final String table;

    NoteStore(Database db) {
        this.db = db;
        this.table = db.table("moderation_notes");
    }

    CompletableFuture<Long> add(UUID target, String targetName, @Nullable UUID actor, String actorName, String body,
            Instant created) {
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + table
                    + " (target, target_name, actor, actor_name, body, created) VALUES (?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                Database.bind(ps, target.toString(), targetName, actor == null ? null : actor.toString(), actorName,
                        body, created.toEpochMilli());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new java.sql.SQLException("No id generated for the new note");
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    CompletableFuture<Integer> count(UUID target) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE target = ?")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    /** One page of notes, oldest first. */
    CompletableFuture<List<Note>> page(UUID target, int offset, int limit) {
        return db.query(c -> {
            List<Note> notes = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, target, target_name, actor, actor_name, body, "
                    + "created FROM " + table + " WHERE target = ? ORDER BY id LIMIT ? OFFSET ?")) {
                Database.bind(ps, target.toString(), Math.max(1, limit), Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        notes.add(read(rs));
                    }
                }
            }
            return List.copyOf(notes);
        });
    }

    CompletableFuture<Optional<Note>> byId(UUID target, long id) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, target, target_name, actor, actor_name, body, "
                    + "created FROM " + table + " WHERE target = ? AND id = ?")) {
                Database.bind(ps, target.toString(), id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.<Note>empty();
                }
            }
        });
    }

    /** Deletes one note of the account. Completes with false when the id does not belong to it. */
    CompletableFuture<Boolean> remove(UUID target, long id) {
        return db.update("DELETE FROM " + table + " WHERE target = ? AND id = ?", target.toString(), id)
                .thenApply(rows -> rows > 0);
    }

    CompletableFuture<Integer> clear(UUID target) {
        return db.update("DELETE FROM " + table + " WHERE target = ?", target.toString());
    }

    private static Note read(ResultSet rs) throws java.sql.SQLException {
        String actor = rs.getString(4);
        return new Note(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3),
                actor == null ? null : UUID.fromString(actor), rs.getString(5), rs.getString(6),
                Instant.ofEpochMilli(rs.getLong(7)));
    }
}
