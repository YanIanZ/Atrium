package dev.yanianz.atrium.module.player;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Detects a Minecraft name change on join. The previous name is kept in a UUID-keyed component, so a
 * rename never affects flight, god or any other stored player data. Staff can be notified and admins can
 * run console commands with {@code {old}} / {@code {new}}.
 */
final class NameChangeWatcher implements Listener {
    static final String PERM_NOTIFY = "atrium.player.namechange.notify";
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int MAX_RETRIES = 5;

    private final ModuleContext ctx;
    private final ConfigHandle<PlayerConfig> config;
    private final PlayerStore store;
    private volatile boolean stopped;

    NameChangeWatcher(ModuleContext ctx, ConfigHandle<PlayerConfig> config, PlayerStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
    }

    void register() {
        ctx.permission(PERM_NOTIFY, org.bukkit.permissions.PermissionDefault.OP,
                "Receive a message when a player joins with a changed name");
        ctx.listen(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // The user row is written during pre-login; one tick later the component is guaranteed loaded.
        ctx.scheduler().entityLater(player, () -> check(player, 0), null, 1L);
    }

    private void check(Player player, int retries) {
        if (stopped || !player.isOnline()) {
            return;
        }
        PlayerIdentity identity;
        try {
            identity = ctx.users().user(player).get(store.identity);
        } catch (IllegalStateException notLoaded) {
            if (retries < MAX_RETRIES) {
                ctx.scheduler().entityLater(player, () -> check(player, retries + 1), null, 20L);
            } else {
                ctx.logger().warn("Player data of {} is still not loaded; skipping the name-change check",
                        player.getName());
            }
            return;
        }
        String current = player.getName();
        if (!identity.hasName()) {
            // First join since this feature was enabled: remember the name without announcing a change.
            remember(player, current, identity.changedAt());
            return;
        }
        String previous = identity.lastName();
        if (previous.equals(current)) {
            return;
        }
        remember(player, current, System.currentTimeMillis());
        ctx.logger().info("Player {} ({}) renamed from {} to {}", current, player.getUniqueId(), previous, current);
        notifyStaff(previous, current);
        runCommands(previous, current);
    }

    private void remember(Player player, String name, long changedAt) {
        try {
            ctx.users().user(player).update(store.identity, current -> current.withName(name, changedAt));
            ctx.users().saveNow(player.getUniqueId()).whenComplete((unused, error) -> {
                if (error != null) {
                    ctx.logger().warn("Saving the last name of {} failed; it will be retried",
                            player.getUniqueId(), error);
                }
            });
        } catch (RuntimeException e) {
            ctx.logger().error("Recording the last name of {} failed", player.getUniqueId(), e);
        }
    }

    private void notifyStaff(String previous, String current) {
        if (!config.get().nameChangeNotify) {
            return;
        }
        for (Player viewer : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!viewer.hasPermission(PERM_NOTIFY)) {
                continue;
            }
            ctx.scheduler().entity(viewer, () -> {
                if (!stopped) {
                    ctx.messages().send(viewer, "player.name-changed",
                            Msg.unparsed("old", previous), Msg.unparsed("new", current));
                }
            }, null);
        }
        ctx.messages().send(Bukkit.getConsoleSender(), "player.name-changed",
                Msg.unparsed("old", previous), Msg.unparsed("new", current));
    }

    private void runCommands(String previous, String current) {
        List<String> configured = config.get().nameChangeCommands;
        if (configured == null || configured.isEmpty()) {
            return;
        }
        if (!PLAYER_NAME.matcher(previous).matches() || !PLAYER_NAME.matcher(current).matches()) {
            ctx.logger().warn("Refusing name-change commands for invalid names '{}' and '{}'", previous, current);
            return;
        }
        List<String> commands = List.copyOf(configured);
        ctx.scheduler().globalLater(() -> {
            if (stopped) {
                return;
            }
            for (String raw : commands) {
                String command = render(raw, previous, current);
                if (command.isBlank()) {
                    continue;
                }
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                } catch (RuntimeException e) {
                    ctx.logger().warn("Name-change command '{}' for {} failed", command, current, e);
                }
            }
        }, 10L);
    }

    /** Replaces only the supported name placeholders; no other templating is applied. */
    static String render(String template, String previous, String current) {
        if (template == null) {
            return "";
        }
        return template.replace("{old}", previous)
                .replace("[oldname]", previous)
                .replace("{new}", current)
                .replace("[newname]", current)
                .strip();
    }

    void stop() {
        stopped = true;
    }
}
