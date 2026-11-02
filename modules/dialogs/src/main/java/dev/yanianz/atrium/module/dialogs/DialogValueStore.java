package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the values a player typed into a dialog, so reopening it (or logging in again) shows their last
 * answers instead of the defaults.
 *
 * <p>Only values that already passed {@link InputValidator} are stored, so the memory can never hold text that
 * would be rejected at click time. Writing happens on an I/O thread after a click; the in-memory copy is what
 * {@link DialogsModule} reads, so opening a dialog never touches the database.
 *
 * <p>Pruning keeps at most {@code savedValuesMaxDialogs} dialogs per player, oldest first.
 */
final class DialogValueStore {
    static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "dialogs values",
            "CREATE TABLE {prefix}dialogs_values (uuid CHAR(36) NOT NULL, dialog VARCHAR(32) NOT NULL,"
                    + " input_key VARCHAR(32) NOT NULL, value VARCHAR(1024) NOT NULL, updated_at BIGINT NOT NULL,"
                    + " PRIMARY KEY (uuid, dialog, input_key))"));

    private final ModuleContext ctx;
    private final ConfigHandle<DialogsConfig> config;
    /** Player -> dialog id -> key -> value. Loaded on join, written after each click. */
    private final Map<UUID, Map<String, Map<String, String>>> cache = new ConcurrentHashMap<>();

    DialogValueStore(ModuleContext ctx, ConfigHandle<DialogsConfig> config) {
        this.ctx = ctx;
        this.config = config;
        ctx.listen(new Listener(this));
    }

    /** Values remembered for one dialog, empty when nothing is stored or remembering is off. */
    Map<String, String> remembered(UUID player, String dialog) {
        if (!config.get().rememberValues) {
            return Map.of();
        }
        Map<String, Map<String, String>> byDialog = cache.get(player);
        if (byDialog == null) {
            return Map.of();
        }
        Map<String, String> values = byDialog.get(dialog);
        return values == null ? Map.of() : Map.copyOf(values);
    }

    /** Stores the validated values of one dialog and persists them. */
    void remember(UUID player, String dialog, Map<String, String> variables) {
        if (!config.get().rememberValues || variables.isEmpty()) {
            return;
        }
        Map<String, Map<String, String>> byDialog = cache.computeIfAbsent(player, key -> new ConcurrentHashMap<>());
        byDialog.put(dialog, new LinkedHashMap<>(variables));
        persist(player, byDialog);
    }

    /** Reads every dialog value of one player. Called on join; the query itself runs on the database executor. */
    void load(UUID player) {
        if (!config.get().rememberValues) {
            return;
        }
        ctx.database().query(c -> {
            Map<String, Map<String, String>> byDialog = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT dialog, input_key, value FROM "
                    + ctx.database().table("dialogs_values") + " WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byDialog.computeIfAbsent(rs.getString(1), key -> new LinkedHashMap<>())
                                .put(rs.getString(2), rs.getString(3));
                    }
                }
            }
            return byDialog;
        }).thenAccept(byDialog -> {
            if (!byDialog.isEmpty()) {
                cache.put(player, new ConcurrentHashMap<>(byDialog));
            }
        }).exceptionally(error -> {
            ctx.logger().warn("Dialog values of {} could not be loaded", player, error);
            return null;
        });
    }

    /** Forgets one player entirely (on quit, after saving). */
    void forget(UUID player) {
        cache.remove(player);
    }

    private void persist(UUID player, Map<String, Map<String, String>> byDialog) {
        int maxDialogs = Math.max(1, Math.min(64, config.get().savedValuesMaxDialogs));
        List<String> kept = new ArrayList<>(byDialog.keySet());
        while (kept.size() > maxDialogs) {
            byDialog.remove(kept.removeFirst());
        }
        Map<String, Map<String, String>> snapshot = new HashMap<>();
        byDialog.forEach((dialog, values) -> snapshot.put(dialog, Map.copyOf(values)));
        String table = ctx.database().table("dialogs_values");
        // The database already runs this off the region threads; never wrap a future in a blocking wait.
        try {
            ctx.database().transaction(c -> {
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                    delete.setString(1, player.toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO " + table + " (uuid, dialog, input_key, value, updated_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, Map<String, String>> dialog : snapshot.entrySet()) {
                        for (Map.Entry<String, String> entry : dialog.getValue().entrySet()) {
                            insert.setString(1, player.toString());
                            insert.setString(2, dialog.getKey());
                            insert.setString(3, entry.getKey());
                            insert.setString(4, limit(entry.getValue()));
                            insert.setLong(5, now);
                            insert.addBatch();
                        }
                    }
                    insert.executeBatch();
                }
                return null;
            }).exceptionally(error -> {
                ctx.logger().warn("Dialog values of {} could not be saved", player, error);
                return null;
            });
        } catch (RuntimeException e) {
            ctx.logger().warn("Dialog values of {} could not be saved", player, e);
        }
    }

    private static String limit(String value) {
        String text = value == null ? "" : value;
        return text.length() <= 1024 ? text : text.substring(0, 1024);
    }

    /** Loads on join and saves on quit; a database failure is logged, never hidden. */
    private record Listener(DialogValueStore store) implements org.bukkit.event.Listener {
        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            store.load(event.getPlayer().getUniqueId());
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            UUID player = event.getPlayer().getUniqueId();
            Map<String, Map<String, String>> byDialog = store.cache.get(player);
            if (byDialog != null) {
                store.persist(player, byDialog);
            }
            store.forget(player);
        }
    }
}
