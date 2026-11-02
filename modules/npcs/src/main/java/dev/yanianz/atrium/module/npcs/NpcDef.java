package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One stored NPC. Immutable: every edit produces a new value, so a command can persist it first and only
 * then update the runtime/cache. The display name is a trusted admin MiniMessage template; equipment is
 * stored as base64-encoded {@code ItemStack} bytes, keyed by Bukkit equipment slot name.
 */
record NpcDef(
        long id,
        String name,
        String displayName,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        @Nullable String skin,
        Map<String, String> equipment,
        String pose,
        boolean lookAt,
        double lookRange,
        String glow,
        double scale,
        boolean nameplate,
        List<String> leftActions,
        List<String> rightActions,
        @Nullable String permission,
        long cooldownMillis,
        BigDecimal cost,
        NpcType engine) {

    static final String DEFAULT_POSE = "standing";
    static final List<String> SLOTS = List.of("HEAD", "CHEST", "LEGS", "FEET", "HAND", "OFF_HAND");

    NpcDef {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 32) {
            throw new IllegalArgumentException("name must be 1-32 characters");
        }
        displayName = displayName == null || displayName.isBlank() ? name : displayName;
        Objects.requireNonNull(world, "world");
        skin = skin == null || skin.isBlank() ? null : skin;
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
        // Stored values written by older versions may use an alias ("crouching") or an unknown pose.
        pose = NpcPose.canonicalOr(pose, DEFAULT_POSE);
        lookRange = clamp(lookRange, 0, 64, 8);
        glow = glow == null ? "" : glow.toLowerCase(Locale.ROOT);
        scale = clamp(scale, NpcScale.MIN, NpcScale.MAX, 1);
        leftActions = copyLines(leftActions);
        rightActions = copyLines(rightActions);
        permission = permission == null || permission.isBlank() ? null : permission;
        cooldownMillis = Math.max(0, cooldownMillis);
        cost = cost == null ? BigDecimal.ZERO
                : new BigDecimal(cost.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString());
        engine = engine == null ? NpcType.AUTO : engine;
    }

    static NpcDef create(String name, String displayName, StoredLocation location, @Nullable String skin,
            NpcType engine) {
        return new NpcDef(0, name, displayName, location.world(), location.x(), location.y(), location.z(),
                location.yaw(), location.pitch(), skin, Map.of(), DEFAULT_POSE, false, 8, "", 1, true,
                List.of(), List.of(), null, 0, BigDecimal.ZERO, engine);
    }

    String key() {
        return name.toLowerCase(Locale.ROOT);
    }

    StoredLocation location() {
        return new StoredLocation(world, x, y, z, yaw, pitch);
    }

    List<String> actions(NpcInteractClick click) {
        return click == NpcInteractClick.LEFT ? leftActions : rightActions;
    }

    NpcDef withLocation(StoredLocation location) {
        return new NpcDef(id, name, displayName, location.world(), location.x(), location.y(), location.z(),
                location.yaw(), location.pitch(), skin, equipment, pose, lookAt, lookRange, glow, scale, nameplate,
                leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withDisplayName(String value) {
        return new NpcDef(id, name, value, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange, glow,
                scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withSkin(@Nullable String value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, value, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withEquipment(String slot, @Nullable String base64) {
        Map<String, String> next = new LinkedHashMap<>(equipment);
        if (base64 == null || base64.isBlank()) {
            next.remove(slot);
        } else {
            next.put(slot, base64);
        }
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, next, pose, lookAt, lookRange, glow,
                scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withPose(String value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, value, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withLook(boolean enabled, double range) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, enabled, range, glow,
                scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withGlow(String value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                value, scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withScale(double value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, value, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withNameplate(boolean value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, value, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withActions(NpcInteractClick click, List<String> lines) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, click == NpcInteractClick.LEFT ? lines : leftActions,
                click == NpcInteractClick.RIGHT ? lines : rightActions, permission, cooldownMillis, cost, engine);
    }

    NpcDef withPermission(@Nullable String value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, value, cooldownMillis, cost, engine);
    }

    NpcDef withCooldown(long millis) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, permission, millis, cost, engine);
    }

    NpcDef withCost(BigDecimal value) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, permission, cooldownMillis, value, engine);
    }

    NpcDef withEngine(NpcType type) {
        return new NpcDef(id, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, type);
    }

    NpcDef withId(long value) {
        return new NpcDef(value, name, displayName, world, x, y, z, yaw, pitch, skin, equipment, pose, lookAt, lookRange,
                glow, scale, nameplate, leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    /**
     * A copy of this NPC under a new name, placed at {@code location} ({@code /npc copy}). The display name
     * follows the new name when it was still the default, so a copied NPC is not labelled with the old name.
     */
    NpcDef copyAs(String newName, StoredLocation location) {
        String display = displayName.equals(name) ? newName : displayName;
        return new NpcDef(0, newName, display, location.world(), location.x(), location.y(), location.z(),
                location.yaw(), location.pitch(), skin, equipment, pose, lookAt, lookRange, glow, scale, nameplate,
                leftActions, rightActions, permission, cooldownMillis, cost, engine);
    }

    private static List<String> copyLines(List<String> lines) {
        if (lines == null) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                copy.add(line.trim());
            }
        }
        return List.copyOf(copy);
    }

    private static double clamp(double value, double min, double max, double fallback) {
        if (!Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }
}
