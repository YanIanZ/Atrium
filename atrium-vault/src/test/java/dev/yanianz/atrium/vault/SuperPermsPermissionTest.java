package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class SuperPermsPermissionTest {
    /** Online player with a fixed permission set; {@code implicitAll} simulates an op's default resolution. */
    private record FakePermissible(Set<String> granted, boolean implicitAll) implements Permissible {
        @Override public boolean isPermissionSet(String name) { return granted.contains(name); }
        @Override public boolean isPermissionSet(Permission perm) { return granted.contains(perm.getName()); }
        @Override public boolean hasPermission(String name) { return implicitAll || granted.contains(name); }
        @Override public boolean hasPermission(Permission perm) { return implicitAll || granted.contains(perm.getName()); }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment addAttachment(Plugin plugin) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) { throw new UnsupportedOperationException(); }
        @Override public PermissionAttachment addAttachment(Plugin plugin, int ticks) { throw new UnsupportedOperationException(); }
        @Override public void removeAttachment(PermissionAttachment attachment) { }
        @Override public void recalculatePermissions() { }
        @Override public Set<PermissionAttachmentInfo> getEffectivePermissions() { return Set.of(); }
        @Override public boolean isOp() { return implicitAll; }
        @Override public void setOp(boolean value) { }
    }

    private final FakePermissible alice = new FakePermissible(Set.of("shop.use", "group.vip"), false);
    private final UUID aliceId = Players.offline("alice").getUniqueId();
    private final SuperPermsPermission perms = new SuperPermsPermission(null, new SuperPermsPermission.OnlinePlayers() {
        private final Map<String, Permissible> byName = Map.of("alice", alice);

        @Override public Permissible byName(String name) { return byName.get(name); }
        @Override public Permissible byId(UUID id) { return id.equals(aliceId) ? alice : null; }
    });

    private final FakePermissible op = new FakePermissible(Set.of(), true);

    private final SuperPermsPermission opPerms = new SuperPermsPermission(null,
            new SuperPermsPermission.OnlinePlayers() {
                private final Map<String, Permissible> byName = Map.of("op", op);

                @Override public Permissible byName(String name) { return byName.get(name); }
                @Override public Permissible byId(UUID id) { return null; }
            });

    @Test
    void metadata() {
        assertEquals("SuperPerms", perms.getName());
        assertTrue(perms.isEnabled());
        assertTrue(perms.hasSuperPermsCompat());
        assertFalse(perms.hasGroupSupport());
    }

    @Test
    void checksOnlinePlayersOnly() {
        assertTrue(perms.playerHas((String) null, "alice", "shop.use"));
        assertFalse(perms.playerHas((String) null, "alice", "shop.admin"));
        assertFalse(perms.playerHas((String) null, "bob", "shop.use"), "offline");
        assertTrue(perms.playerHas((String) null, Players.offline("alice"), "shop.use"));
        assertFalse(perms.playerHas((String) null, Players.offline("bob"), "shop.use"));
        assertFalse(perms.playerHas((String) null, (String) null, "shop.use"));
        assertFalse(perms.playerHas((String) null, "alice", null));
    }

    @Test
    void noPersistenceAndNoGroups() {
        assertFalse(perms.playerAdd((String) null, "alice", "x"));
        assertFalse(perms.playerRemove((String) null, "alice", "x"));
        assertFalse(perms.groupAdd((String) null, "vip", "x"));
        assertFalse(perms.groupHas((String) null, "vip", "x"));
        assertFalse(perms.playerAddGroup((String) null, "alice", "vip"));
        assertArrayEquals(new String[0], perms.getGroups());
        assertArrayEquals(new String[0], perms.getPlayerGroups((String) null, "alice"));
        assertNull(perms.getPrimaryGroup((String) null, "alice"));
        assertTrue(perms.playerInGroup((String) null, "alice", "vip"), "group.<name> convention");
        assertFalse(perms.playerInGroup((String) null, "alice", "admin"));
        // An op has every unregistered node through Bukkit's defaults, but is not "in" that group unless
        // the permission was explicitly set.
        assertTrue(op.hasPermission("group.admin"));
        assertFalse(opPerms.playerInGroup((String) null, "op", "admin"), "unset group node must not count");
    }
}
