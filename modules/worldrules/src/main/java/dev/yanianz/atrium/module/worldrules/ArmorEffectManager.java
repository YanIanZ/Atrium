package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Applies and removes the configured armor effects. No polling: the set is evaluated on join, respawn,
 * armor changes and item breaks, which are exactly the events that can change which set is worn.
 *
 * <p>Our effects are infinite, ambient, without particles and without an icon. That signature is the
 * "flag" used on removal, so an effect of the same type applied by a player or another plugin is never
 * removed (a documented bug in other plugins).
 *
 * <p>Ownership is also stored in the player's persistent data ({@code atrium:armor_effects}). A crash or
 * restart can therefore never leave an infinite armor effect behind: the next join removes what the
 * previous session applied before evaluating the set again.
 */
final class ArmorEffectManager {
    private static final String PDC_KEY = "armor_effects";

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final Map<UUID, Map<PotionEffectType, Integer>> applied = new ConcurrentHashMap<>();
    private volatile List<ArmorSet.CompiledSet> sets = List.of();

    ArmorEffectManager(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    private NamespacedKey ownershipKey() {
        return new NamespacedKey(ctx.plugin(), PDC_KEY);
    }

    void recompile() {
        List<ArmorSet.CompiledSet> compiled = new ArrayList<>();
        Map<String, ArmorSet> configured = config.get().armorSets;
        if (configured == null) {
            this.sets = List.of();
            return;
        }
        for (Map.Entry<String, ArmorSet> entry : configured.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            ArmorSet.CompiledSet set = entry.getValue().compile(entry.getKey(),
                    message -> ctx.logger().warn("worldrules: {}", message));
            if (set != null) {
                compiled.add(set);
            }
        }
        this.sets = List.copyOf(compiled);
        if (!compiled.isEmpty()) {
            ctx.logger().info("Loaded {} armor effect set(s)", compiled.size());
        }
    }

    void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> evaluate(player), null);
        }
    }

    /** Must run on the player's own thread. */
    @SuppressWarnings("deprecation")
    void evaluate(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        Map<PotionEffectType, Integer> previous = applied.getOrDefault(id, Map.of());
        ArmorSet.CompiledSet active = active(player);
        Map<PotionEffectType, Integer> wanted = new LinkedHashMap<>();
        if (active != null) {
            for (ArmorSet.CompiledEffect effect : active.effects()) {
                wanted.put(effect.type(), effect.amplifier());
            }
        }

        for (Map.Entry<PotionEffectType, Integer> entry : previous.entrySet()) {
            if (wanted.containsKey(entry.getKey())) {
                continue;
            }
            PotionEffect current = player.getPotionEffect(entry.getKey());
            if (isOurs(current) && current.getAmplifier() == entry.getValue()) {
                player.removePotionEffect(entry.getKey());
            }
        }

        Map<PotionEffectType, Integer> next = new HashMap<>();
        for (Map.Entry<PotionEffectType, Integer> entry : wanted.entrySet()) {
            PotionEffectType type = entry.getKey();
            int amplifier = entry.getValue();
            PotionEffect current = player.getPotionEffect(type);
            boolean wasOurs = previous.containsKey(type);
            if (isOurs(current)) {
                if (!wasOurs) {
                    // Another plugin owns an infinite effect of this type; leave it alone.
                    continue;
                }
                if (current.getAmplifier() == amplifier) {
                    next.put(type, amplifier);
                    continue;
                }
            } else if (current != null && current.getAmplifier() > amplifier) {
                // A stronger finite effect from a potion must not be weakened by the armor set.
                continue;
            }
            PotionEffect effect = new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, true, false, false);
            if (player.addPotionEffect(effect, true)) {
                next.put(type, amplifier);
            }
        }
        if (next.isEmpty()) {
            applied.remove(id);
        } else {
            applied.put(id, Map.copyOf(next));
        }
        persist(player, next.keySet());
    }

    private ArmorSet.CompiledSet active(Player player) {
        List<ArmorSet.CompiledSet> current = sets;
        if (current.isEmpty()) {
            return null;
        }
        ItemStack[] contents = player.getInventory().getArmorContents();
        Material[] materials = new Material[4];
        int[] customModelData = new int[4];
        Arrays.fill(customModelData, -1);
        for (int slot = 0; slot < contents.length && slot < 4; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.isEmpty()) {
                continue;
            }
            materials[slot] = item.getType();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                customModelData[slot] = customModelData(meta);
            }
        }
        for (ArmorSet.CompiledSet set : current) {
            if (set.matches(materials, customModelData)) {
                return set;
            }
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static int customModelData(ItemMeta meta) {
        if (meta.hasCustomModelDataComponent()) {
            var floats = meta.getCustomModelDataComponent().getFloats();
            if (!floats.isEmpty()) {
                float value = floats.getFirst();
                if (Float.isFinite(value) && value == Math.rint(value)) {
                    return (int) value;
                }
            }
        }
        return meta.hasCustomModelData() ? meta.getCustomModelData() : -1;
    }

    private static boolean isOurs(PotionEffect effect) {
        return effect != null && effect.isInfinite() && effect.isAmbient()
                && !effect.hasParticles() && !effect.hasIcon();
    }

    /** Writes which effects we applied, so leftovers can be found after a crash. Player thread only. */
    private void persist(Player player, Set<PotionEffectType> types) {
        List<String> keys = new ArrayList<>(types.size());
        for (PotionEffectType type : types) {
            keys.add(type.getKey().toString());
        }
        String desired = ArmorEffectOwnership.encode(keys);
        String current = player.getPersistentDataContainer().get(ownershipKey(), PersistentDataType.STRING);
        if (desired.equals(current == null ? "" : current)) {
            return;
        }
        if (desired.isEmpty()) {
            player.getPersistentDataContainer().remove(ownershipKey());
        } else {
            player.getPersistentDataContainer().set(ownershipKey(), PersistentDataType.STRING, desired);
        }
    }

    private static List<PotionEffectType> effectTypes(Set<String> effectKeys) {
        List<PotionEffectType> types = new ArrayList<>(effectKeys.size());
        for (String key : effectKeys) {
            NamespacedKey id = NamespacedKey.fromString(key);
            PotionEffectType type = id == null ? null : Registry.EFFECT.get(id);
            if (type != null) {
                types.add(type);
            }
        }
        return types;
    }

    /**
     * Removes leftovers recorded by a previous session. Called on join before {@link #evaluate(Player)};
     * must run on the player's own thread.
     */
    void cleanup(Player player) {
        String stored = player.getPersistentDataContainer().get(ownershipKey(), PersistentDataType.STRING);
        if (stored == null || stored.isBlank()) {
            return;
        }
        for (PotionEffectType type : effectTypes(new LinkedHashSet<>(ArmorEffectOwnership.decode(stored)))) {
            PotionEffect current = player.getPotionEffect(type);
            if (isOurs(current)) {
                player.removePotionEffect(type);
            }
        }
        player.getPersistentDataContainer().remove(ownershipKey());
        applied.remove(player.getUniqueId());
    }

    /**
     * Removes our effects and their ownership record. Runs inline on the player's thread (quit, death);
     * falls back to a scheduled entity task when the caller is on another thread.
     */
    void remove(Player player) {
        UUID id = player.getUniqueId();
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            removeNow(player);
            return;
        }
        ctx.scheduler().entity(player, () -> removeNow(player), () -> applied.remove(id));
    }

    private void removeNow(Player player) {
        UUID id = player.getUniqueId();
        Map<PotionEffectType, Integer> previous = applied.remove(id);
        if (previous != null) {
            for (Map.Entry<PotionEffectType, Integer> entry : previous.entrySet()) {
                PotionEffect current = player.getPotionEffect(entry.getKey());
                if (isOurs(current) && current.getAmplifier() == entry.getValue()) {
                    player.removePotionEffect(entry.getKey());
                }
            }
        }
        // Also remove anything a crashed session left behind, then drop the ownership record.
        String stored = player.getPersistentDataContainer().get(ownershipKey(), PersistentDataType.STRING);
        if (stored != null && !stored.isBlank()) {
            for (PotionEffectType type : effectTypes(new LinkedHashSet<>(ArmorEffectOwnership.decode(stored)))) {
                if (!previous.containsKey(type) && isOurs(player.getPotionEffect(type))) {
                    player.removePotionEffect(type);
                }
            }
        }
        player.getPersistentDataContainer().remove(ownershipKey());
    }

    /** Removes only effects this manager applied; used on disable. */
    void removeAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> removeNow(player), null);
        }
    }
}
