package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Hue cycling for leather armour. A player opts in with {@code /armorcolor on}; only that player's own
 * armour is touched, on their own thread, at the configured low rate. The state is stored in the player's
 * persistent data, so the opt-in survives a restart without a table of its own.
 */
final class LeatherAnimationManager {
    static final String PERMISSION = "atrium.worldrules.armorcolor";
    static final String OTHERS = "atrium.worldrules.armorcolor.others";
    private static final String PDC_KEY = "armor_color";

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final Map<UUID, TaskHandle> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hues = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> updating = ConcurrentHashMap.newKeySet();

    LeatherAnimationManager(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    private NamespacedKey key() {
        return new NamespacedKey(ctx.plugin(), PDC_KEY);
    }

    /** Must run on the player's own thread. */
    boolean optedIn(Player player) {
        return Boolean.TRUE.equals(player.getPersistentDataContainer().get(key(), PersistentDataType.BOOLEAN));
    }

    /** Must run on the player's own thread. */
    void optIn(Player player) {
        player.getPersistentDataContainer().set(key(), PersistentDataType.BOOLEAN, true);
        start(player);
    }

    /** Must run on the player's own thread. */
    void optOut(Player player) {
        player.getPersistentDataContainer().remove(key());
        stop(player.getUniqueId());
    }

    /** Starts the timer for one player when every condition is met; otherwise stops it. */
    void start(Player player) {
        UUID id = player.getUniqueId();
        stop(id);
        if (!player.isOnline() || !config.get().leatherAnimation.enabled || !player.hasPermission(PERMISSION)
                || !optedIn(player)) {
            return;
        }
        long period = config.get().leatherAnimation.refreshTicks();
        hues.putIfAbsent(id, player.getTicksLived());
        TaskHandle handle = ctx.track(ctx.scheduler().entityTimer(player, () -> tick(player), () -> stop(id), 1, period));
        tasks.put(id, handle);
    }

    /** Runs on the player's thread. */
    private void tick(Player player) {
        WorldRulesConfig.LeatherAnimation settings = config.get().leatherAnimation;
        if (!player.isOnline() || !settings.enabled || !player.hasPermission(PERMISSION) || !optedIn(player)) {
            stop(player.getUniqueId());
            return;
        }
        int hue = LeatherColors.nextHue(hues.getOrDefault(player.getUniqueId(), 0), settings.hueStep());
        hues.put(player.getUniqueId(), hue);
        apply(player, LeatherColors.rgb(hue, settings.saturation(), settings.brightness()));
    }

    /** Replaces only the colour of the leather pieces the player is wearing. Player thread only. */
    void apply(Player player, int rgb) {
        ItemStack[] contents = player.getInventory().getArmorContents();
        boolean changed = false;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || !LeatherColors.leather(item.getType())) {
                continue;
            }
            if (!(item.getItemMeta() instanceof LeatherArmorMeta meta)) {
                continue;
            }
            Color colour = Color.fromRGB(rgb);
            if (meta.getColor() != null && meta.getColor().asRGB() == rgb) {
                continue;
            }
            meta.setColor(colour);
            item.setItemMeta(meta);
            changed = true;
        }
        if (changed) {
            UUID id = player.getUniqueId();
            updating.add(id);
            try {
                player.getInventory().setArmorContents(contents);
            } finally {
                updating.remove(id);
            }
        }
    }

    /** Keeps the armour-effect listener from re-evaluating a change this manager caused itself. */
    boolean updating(Player player) {
        return updating.contains(player.getUniqueId());
    }

    void stop(UUID id) {
        TaskHandle handle = tasks.remove(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    /** Restarts the timers after a reload; stopped first so a changed interval takes effect. */
    void reload() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> start(player), null);
        }
    }

    void stopAll() {
        for (UUID id : tasks.keySet()) {
            stop(id);
        }
        tasks.clear();
        hues.clear();
    }
}
