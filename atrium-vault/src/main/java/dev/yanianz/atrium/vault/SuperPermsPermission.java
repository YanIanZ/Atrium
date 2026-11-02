package dev.yanianz.atrium.vault;

import java.util.UUID;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.permissions.Permissible;
import org.bukkit.plugin.Plugin;

/**
 * Fallback {@link Permission} backed by Bukkit's built-in permission system ("SuperPerms"). Registered at the lowest
 * priority so any real permissions plugin (LuckPerms, ...) wins.
 *
 * <p>SuperPerms only knows online players and has no persistent storage and no groups, so: checks for offline players
 * return {@code false}; persistent add/remove and every group mutation return {@code false}; group listings are empty
 * and {@link #getPrimaryGroup(String, String)} returns {@code null} (as Vault's own SuperPerms hook did). Group
 * membership follows the common {@code group.<name>} permission convention, but the node must be explicitly
 * set: an op must not be "in" every group through Bukkit's default-value resolution. Transient permissions
 * use the inherited Vault implementation (permission attachments owned by this plugin).
 */
@SuppressWarnings("deprecation") // the String-name overloads are the abstract Vault contract
public final class SuperPermsPermission extends Permission {
    private static final String[] NONE = new String[0];

    /** Looks up online players; returns {@code null} for offline ones. */
    public interface OnlinePlayers {
        Permissible byName(String name);

        Permissible byId(UUID id);

        static OnlinePlayers of(Server server) {
            return new OnlinePlayers() {
                @Override
                public Permissible byName(String name) {
                    return server.getPlayerExact(name);
                }

                @Override
                public Permissible byId(UUID id) {
                    return server.getPlayer(id);
                }
            };
        }
    }

    private final OnlinePlayers online;

    public SuperPermsPermission(Plugin plugin, OnlinePlayers online) {
        this.plugin = plugin;
        this.online = online;
    }

    @Override
    public String getName() {
        return "SuperPerms";
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean hasSuperPermsCompat() {
        return true;
    }

    @Override
    public boolean hasGroupSupport() {
        return false;
    }

    private static boolean valid(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean check(Permissible permissible, String permission) {
        return permissible != null && permissible.hasPermission(permission);
    }

    /**
     * Group membership via the {@code group.<name>} convention. The node must be explicitly set: Bukkit's
     * default permission resolution makes an op "have" every unregistered node, which would put an op in
     * every group. This mirrors Vault's own SuperPerms hook.
     */
    private static boolean group(Permissible permissible, String group) {
        if (permissible == null || !valid(group)) {
            return false;
        }
        String node = "group." + group;
        return permissible.isPermissionSet(node) && permissible.hasPermission(node);
    }

    @Override
    public boolean playerHas(String world, String player, String permission) {
        return valid(player) && valid(permission) && check(online.byName(player), permission);
    }

    @Override
    public boolean playerHas(String world, OfflinePlayer player, String permission) {
        return player != null && valid(permission) && check(online.byId(player.getUniqueId()), permission);
    }

    @Override
    public boolean playerAdd(String world, String player, String permission) {
        return false;
    }

    @Override
    public boolean playerRemove(String world, String player, String permission) {
        return false;
    }

    @Override
    public boolean groupHas(String world, String group, String permission) {
        return false;
    }

    @Override
    public boolean groupAdd(String world, String group, String permission) {
        return false;
    }

    @Override
    public boolean groupRemove(String world, String group, String permission) {
        return false;
    }

    @Override
    public boolean playerInGroup(String world, String player, String group) {
        return valid(player) && group(online.byName(player), group);
    }

    @Override
    public boolean playerInGroup(String world, OfflinePlayer player, String group) {
        return player != null && group(online.byId(player.getUniqueId()), group);
    }

    @Override
    public boolean playerAddGroup(String world, String player, String group) {
        return false;
    }

    @Override
    public boolean playerRemoveGroup(String world, String player, String group) {
        return false;
    }

    @Override
    public String[] getPlayerGroups(String world, String player) {
        return NONE.clone();
    }

    @Override
    public String getPrimaryGroup(String world, String player) {
        return null;
    }

    @Override
    public String[] getGroups() {
        return NONE.clone();
    }
}
