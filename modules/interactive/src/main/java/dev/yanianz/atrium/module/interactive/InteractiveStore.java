package dev.yanianz.atrium.module.interactive;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Tables {@code {prefix}interactive_commands} and {@code {prefix}interactive_targets}. */
final class InteractiveStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "interactive commands and targets",
            "CREATE TABLE {prefix}interactive_commands (name_lower VARCHAR(32) NOT NULL PRIMARY KEY, name VARCHAR(32) NOT NULL, "
                    + "permission VARCHAR(128) NOT NULL, cooldown_ms BIGINT NOT NULL, cancel_event INT NOT NULL, "
                    + "trigger_type VARCHAR(16) NOT NULL, actions TEXT NOT NULL)",
            "CREATE TABLE {prefix}interactive_targets (kind VARCHAR(8) NOT NULL, target VARCHAR(200) NOT NULL, "
                    + "name_lower VARCHAR(32) NOT NULL, PRIMARY KEY (kind, target))"));

    record Target(InteractiveCommand.TargetKind kind, String target, String nameLower) {}

    record Snapshot(List<InteractiveCommand> commands, List<Target> targets) {}

    private final Database db;

    InteractiveStore(Database db) {
        this.db = db;
    }

    CompletableFuture<Snapshot> loadAll() {
        return db.query(c -> {
            List<InteractiveCommand> commands = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, permission, cooldown_ms, cancel_event, trigger_type, actions FROM "
                    + db.table("interactive_commands")); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    InteractiveCommand.Trigger trigger;
                    try {
                        trigger = InteractiveCommand.Trigger.valueOf(rs.getString(5).toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        trigger = InteractiveCommand.Trigger.RIGHT;
                    }
                    String actions = rs.getString(6);
                    commands.add(new InteractiveCommand(rs.getString(1), rs.getString(2), Duration.ofMillis(rs.getLong(3)),
                            rs.getInt(4) != 0, trigger, actions == null || actions.isEmpty() ? List.of()
                                    : Arrays.asList(actions.split("\n", -1))));
                }
            }
            List<Target> targets = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, target, name_lower FROM " + db.table("interactive_targets"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        targets.add(new Target(InteractiveCommand.TargetKind.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3)));
                    } catch (IllegalArgumentException e) {
                        // unknown kind written by a newer version; keep it in the table, skip here
                    }
                }
            }
            return new Snapshot(commands, targets);
        });
    }

    CompletableFuture<Void> upsert(InteractiveCommand command) {
        String sql = db.dialect().upsert(db.table("interactive_commands"),
                "name_lower, name, permission, cooldown_ms, cancel_event, trigger_type, actions", "?, ?, ?, ?, ?, ?, ?", "name_lower",
                "name, permission, cooldown_ms, cancel_event, trigger_type, actions");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, command.key(), command.name(), command.permission(), command.cooldown().toMillis(),
                        command.cancelEvent() ? 1 : 0, command.trigger().name(), String.join("\n", command.actions()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Inserts a new command; completes with false when the name exists. */
    CompletableFuture<Boolean> insert(InteractiveCommand command) {
        return db.transaction(c -> {
            try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM " + db.table("interactive_commands") + " WHERE name_lower = ?")) {
                check.setString(1, command.key());
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("interactive_commands")
                    + " (name_lower, name, permission, cooldown_ms, cancel_event, trigger_type, actions) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                Database.bind(ps, command.key(), command.name(), command.permission(), command.cooldown().toMillis(),
                        command.cancelEvent() ? 1 : 0, command.trigger().name(), String.join("\n", command.actions()));
                ps.executeUpdate();
            }
            return true;
        });
    }

    /** Deletes the command and its targets in one transaction. */
    CompletableFuture<Boolean> delete(String name) {
        String key = InteractiveCommand.key(name);
        return db.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("interactive_targets") + " WHERE name_lower = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("interactive_commands") + " WHERE name_lower = ?")) {
                ps.setString(1, key);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Binds a target; completes with false when it is already bound (to any command). */
    CompletableFuture<Boolean> bind(InteractiveCommand.TargetKind kind, String target, String name) {
        return db.transaction(c -> {
            try (PreparedStatement check = c.prepareStatement("SELECT 1 FROM " + db.table("interactive_targets")
                    + " WHERE kind = ? AND target = ?")) {
                Database.bind(check, kind.name(), target);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("interactive_targets")
                    + " (kind, target, name_lower) VALUES (?, ?, ?)")) {
                Database.bind(ps, kind.name(), target, InteractiveCommand.key(name));
                ps.executeUpdate();
            }
            return true;
        });
    }

    CompletableFuture<Boolean> unbind(InteractiveCommand.TargetKind kind, String target) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + db.table("interactive_targets") + " WHERE kind = ? AND target = ?")) {
                Database.bind(ps, kind.name(), target);
                return ps.executeUpdate() > 0;
            }
        });
    }
}
