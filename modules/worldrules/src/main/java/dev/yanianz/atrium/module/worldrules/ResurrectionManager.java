package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Custom totem resurrection. A sneaking right-click with the configured item arms it for a short window;
 * a lethal hit inside that window is cancelled once, the item is used up and a persisted cooldown starts.
 * Only the player's own thread is touched: the interaction and damage events already run there.
 */
final class ResurrectionManager implements Listener {
    static final String PERM_USE = "atrium.worldrules.resurrect";
    private static final String COOLDOWN_KEY = "worldrules:resurrection";

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final Map<UUID, Long> armedUntil = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    ResurrectionManager(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        WorldRulesConfig.Resurrection cfg = config.get().resurrection;
        if (!cfg.enabled || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        Material item = cfg.itemType();
        ItemStack held = event.getItem();
        if (item == null || held == null || held.getType() != item) {
            return;
        }
        Duration remaining = ctx.cooldowns().remaining(ctx.users().user(player), COOLDOWN_KEY);
        if (!remaining.isZero()) {
            ctx.messages().send(player, "worldrules.resurrect-cooldown", Msg.duration("time", remaining));
            return;
        }
        long now = System.currentTimeMillis();
        int seconds = cfg.warmupSeconds();
        armedUntil.put(player.getUniqueId(), seconds <= 0 ? Long.MAX_VALUE : now + seconds * 1000L);
        if (seconds <= 0) {
            ctx.messages().send(player, "worldrules.resurrect-armed-until-used");
        } else {
            ctx.messages().send(player, "worldrules.resurrect-armed", Msg.number("seconds", seconds));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        WorldRulesConfig.Resurrection cfg = config.get().resurrection;
        if (!cfg.enabled || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && !cfg.protectFromVoid) {
            return;
        }
        if (player.getHealth() - event.getFinalDamage() > 0) {
            return;
        }
        Long until = armedUntil.get(player.getUniqueId());
        if (until == null) {
            return;
        }
        if (until < System.currentTimeMillis()) {
            armedUntil.remove(player.getUniqueId());
            return;
        }
        if (!player.hasPermission(PERM_USE)) {
            return;
        }
        Material material = cfg.itemType();
        if (material == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        Found found = find(inventory, material, cfg.anywhereInInventory);
        if (found == null) {
            return;
        }
        Duration remaining = ctx.cooldowns().remaining(ctx.users().user(player), COOLDOWN_KEY);
        if (!remaining.isZero()) {
            armedUntil.remove(player.getUniqueId());
            ctx.messages().send(player, "worldrules.resurrect-cooldown", Msg.duration("time", remaining));
            return;
        }

        EntityResurrectEvent resurrect = new EntityResurrectEvent(player, found.hand());
        org.bukkit.Bukkit.getPluginManager().callEvent(resurrect);
        if (resurrect.isCancelled()) {
            return;
        }

        event.setCancelled(true);
        armedUntil.remove(player.getUniqueId());
        if (cfg.consumeItem) {
            consume(inventory, found);
        }
        int cooldown = cfg.cooldownSeconds();
        if (cooldown > 0) {
            ctx.cooldowns().start(ctx.users().user(player), COOLDOWN_KEY, Duration.ofSeconds(cooldown));
        }
        double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) == null
                ? 20.0
                : player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
        double health = Math.min(cfg.health(), maxHealth);
        player.setHealth(Math.max(0.5, health));
        applyEffects(player, cfg);
        if (cfg.showEffect) {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.2);
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID) {
            rescueFromVoid(player);
        }
        ctx.messages().send(player, "worldrules.resurrect-used");
    }

    private void applyEffects(Player player, WorldRulesConfig.Resurrection cfg) {
        List<ResurrectionEffects.Spec> specs = ResurrectionEffects.parseAll(cfg.effects);
        for (ResurrectionEffects.Spec spec : specs) {
            PotionEffectType type = ResurrectionEffects.resolve(spec);
            if (type == null) {
                if (warned.add(spec.name())) {
                    ctx.logger().warn("worldrules: unknown resurrection effect '{}'", spec.name());
                }
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, spec.seconds() * 20, spec.amplifier(), true, false, true));
        }
    }

    private void rescueFromVoid(Player player) {
        Location spawn = player.getWorld().getSpawnLocation();
        player.teleportAsync(spawn).thenAccept(moved -> {
            if (!Boolean.TRUE.equals(moved)) {
                ctx.logger().warn("Resurrection of {} could not move the player out of the void", player.getName());
                return;
            }
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline()) {
                    player.setFallDistance(0);
                }
            }, null);
        }).exceptionally(error -> {
            ctx.logger().warn("Resurrection of {} could not move the player out of the void", player.getName(), error);
            return null;
        });
    }

    private static Found find(PlayerInventory inventory, Material material, boolean anywhere) {
        if (inventory.getItemInMainHand().getType() == material) {
            return new Found(inventory.getHeldItemSlot(), EquipmentSlot.HAND);
        }
        if (inventory.getItemInOffHand().getType() == material) {
            return new Found(40, EquipmentSlot.OFF_HAND);
        }
        if (!anywhere) {
            return null;
        }
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() == material) {
                return new Found(i, EquipmentSlot.HAND);
            }
        }
        return null;
    }

    private static void consume(PlayerInventory inventory, Found found) {
        if (found.hand() == EquipmentSlot.OFF_HAND) {
            inventory.setItemInOffHand(reduce(inventory.getItemInOffHand()));
            return;
        }
        if (found.slot() == inventory.getHeldItemSlot()) {
            inventory.setItemInMainHand(reduce(inventory.getItemInMainHand()));
            return;
        }
        inventory.setItem(found.slot(), reduce(inventory.getItem(found.slot())));
    }

    private static ItemStack reduce(ItemStack item) {
        if (item == null) {
            return null;
        }
        if (item.getAmount() <= 1) {
            return null;
        }
        ItemStack copy = item.clone();
        copy.setAmount(copy.getAmount() - 1);
        return copy;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        armedUntil.remove(event.getPlayer().getUniqueId());
    }

    void reload() {
        armedUntil.clear();
        warned.clear();
    }

    void shutdown() {
        armedUntil.clear();
        warned.clear();
    }

    private record Found(int slot, EquipmentSlot hand) {}
}
