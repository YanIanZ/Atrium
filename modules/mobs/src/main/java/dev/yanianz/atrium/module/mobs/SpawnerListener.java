package dev.yanianz.atrium.module.mobs;

import dev.yanianz.atrium.api.mobs.SpawnerChangeEvent;
import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Spawner rules: silk-touch drops that keep the mob type, optional charges, the egg permission and the
 * placement guard. Every handler runs on the region thread that owns the block, so no world access is
 * ever handed to another thread and no chunk is loaded to answer a question.
 */
final class SpawnerListener implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<MobsConfig> config;
    private final @Nullable ProtectionService protection;
    private final SpawnerChargeStore store;

    SpawnerListener(ModuleContext ctx, ConfigHandle<MobsConfig> config, @Nullable ProtectionService protection,
            SpawnerChargeStore store) {
        this.ctx = ctx;
        this.config = config;
        this.protection = protection;
        this.store = store;
    }

    // ---- breaking ----

    /** Refuses a break the player cannot pay for, before any other plugin sees a successful break. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreakGate(BlockBreakEvent event) {
        if (!SpawnerItems.isSpawner(event.getBlock())) {
            return;
        }
        MobsConfig cfg = config.get();
        MobsConfig.Spawners spawners = cfg.spawners;
        if (!spawners.enabled || !spawners.charges.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (spawners.charges.creativeBypass && player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        String node = spawners.charges.permission;
        if (node != null && !node.isBlank() && !player.hasPermission(node)) {
            event.setCancelled(true);
            ctx.messages().send(player, "mobs.spawner-charge-denied", Msg.unparsed("permission", node));
            return;
        }
        SpawnerChargeState state = current(player, cfg);
        if (!state.hasCharge()) {
            event.setCancelled(true);
            ctx.messages().send(player, "mobs.spawner-no-charge",
                    Msg.number("max", cfg.maxCharges()),
                    Msg.number("seconds", state.secondsUntilNext(cfg.maxCharges(), cfg.chargeCooldownSeconds(),
                            System.currentTimeMillis())));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!SpawnerItems.isSpawner(block)) {
            return;
        }
        MobsConfig cfg = config.get();
        MobsConfig.Spawners spawners = cfg.spawners;
        if (!spawners.enabled || !spawners.silk.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (spawners.silk.requirePermission && !player.hasPermission(MobsModule.PERM_SPAWNER_SILK)) {
            return;
        }
        boolean paid = spawners.charges.enabled
                && !(spawners.charges.creativeBypass && player.getGameMode() == GameMode.CREATIVE);
        boolean silkTouch = hasSilkTouch(player);
        EntityType type = SpawnerItems.spawnedType(block);
        if (type == null) {
            return; // an empty spawner keeps the vanilla behaviour
        }
        if (silkTouchRequired(spawners) && !silkTouch) {
            return; // nothing would drop anyway: do not charge for it
        }
        if (paid) {
            spend(player, cfg);
        }
        // From here on Atrium owns the drop: exactly one item, or nothing when the roll fails.
        event.setDropItems(false);
        double chance = paid
                ? SpawnerDropRules.chance(cfg.spawnerDropChance(), silkTouch, spawners.charges.requireSilkTouch)
                : SpawnerDropRules.chance(100, silkTouch, spawners.silk.requireSilkTouch);
        double roll = ThreadLocalRandom.current().nextDouble(100);
        if (!SpawnerDropRules.drops(chance, roll)) {
            if (chance < 100) {
                ctx.messages().send(player, "mobs.spawner-drop-failed",
                        Msg.number("chance", chance), Msg.unparsed("type", EntityTypes.name(type)));
            }
            return;
        }
        Location at = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().dropItemNaturally(at, SpawnerItems.item(type));
    }

    private static boolean silkTouchRequired(MobsConfig.Spawners spawners) {
        return spawners.silk.requireSilkTouch
                || (spawners.charges.enabled && spawners.charges.requireSilkTouch);
    }

    // ---- placing ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!SpawnerItems.isSpawner(block)) {
            return;
        }
        MobsConfig cfg = config.get();
        MobsConfig.Spawners spawners = cfg.spawners;
        if (!spawners.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (spawners.proximity.enabled && !hasBypass(player, spawners.proximity.bypassPermission)) {
            double nearest = nearestSpawner(block, cfg.proximityRadius());
            if (nearest >= 0) {
                event.setCancelled(true);
                ctx.messages().send(player, "mobs.spawner-too-close",
                        Msg.number("distance", Math.sqrt(nearest)),
                        Msg.number("radius", cfg.proximityRadius()));
                return;
            }
        }
        if (!spawners.silk.enabled) {
            return;
        }
        EntityType type = SpawnerItems.typeOf(event.getItemInHand());
        if (type == null) {
            return;
        }
        if (spawners.silk.requirePermission && !player.hasPermission(MobsModule.PERM_SPAWNER_PLACE)) {
            event.setCancelled(true);
            ctx.messages().send(player, "mobs.spawner-place-denied",
                    Msg.unparsed("permission", MobsModule.PERM_SPAWNER_PLACE));
            return;
        }
        if (protection != null && !protection.canBuild(player, block.getLocation())) {
            event.setCancelled(true);
            ctx.messages().send(player, "mobs.spawner-protected");
            return;
        }
        if (block.getState() instanceof CreatureSpawner spawner) {
            spawner.setSpawnedType(type);
            spawner.update(true, false);
        }
    }

    /**
     * Squared distance to the nearest other spawner, or -1. Only loaded chunks are inspected through their
     * tile entities, so placing a spawner never loads a chunk (a full cube scan would touch 35k blocks).
     */
    private static double nearestSpawner(Block block, int radius) {
        World world = block.getWorld();
        int chunkRadius = (radius >> 4) + 1;
        int centerX = block.getX() >> 4;
        int centerZ = block.getZ() >> 4;
        List<SpawnerProximity.Pos> found = new ArrayList<>();
        for (int x = centerX - chunkRadius; x <= centerX + chunkRadius; x++) {
            for (int z = centerZ - chunkRadius; z <= centerZ + chunkRadius; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    continue;
                }
                for (BlockState state : world.getChunkAt(x, z).getTileEntities()) {
                    if (state instanceof CreatureSpawner) {
                        found.add(new SpawnerProximity.Pos(state.getX(), state.getY(), state.getZ()));
                    }
                }
            }
        }
        return SpawnerProximity.nearest(block.getX(), block.getY(), block.getZ(), radius, found);
    }

    // ---- eggs and pickaxe info ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEgg(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        MobsConfig cfg = config.get();
        MobsConfig.Spawners spawners = cfg.spawners;
        if (!spawners.enabled || !spawners.egg.enabled) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!SpawnerItems.isSpawner(block)) {
            return;
        }
        EntityType type = SpawnEggs.of(event.getItem());
        if (type == null) {
            return;
        }
        Player player = event.getPlayer();
        String prefix = spawners.egg.permissionPrefix.isBlank()
                ? "atrium.spawner.egg" : spawners.egg.permissionPrefix;
        String node = prefix + "." + EntityTypes.node(type);
        if (player.hasPermission(node)) {
            return;
        }
        event.setCancelled(true);
        ctx.messages().send(player, "mobs.spawner-egg-denied",
                Msg.unparsed("type", EntityTypes.name(type)), Msg.unparsed("permission", node));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickaxeInfo(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.getPlayer().isSneaking()) {
            return;
        }
        MobsConfig cfg = config.get();
        MobsConfig.Spawners spawners = cfg.spawners;
        if (!spawners.enabled || !spawners.charges.enabled) {
            return;
        }
        Block block = event.getClickedBlock();
        if (!SpawnerItems.isSpawner(block)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !SpawnerItems.isPickaxe(item.getType())) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(MobsModule.PERM_SPAWNER_CHARGE)) {
            return;
        }
        SpawnerChargeState state = current(player, cfg);
        ctx.messages().actionBar(player, "mobs.spawner-charges",
                Msg.number("charges", state.charges()),
                Msg.number("max", cfg.maxCharges()),
                Msg.number("seconds", state.secondsUntilNext(cfg.maxCharges(), cfg.chargeCooldownSeconds(),
                        System.currentTimeMillis())),
                Msg.number("chance", cfg.spawnerDropChance()));
    }

    // ---- helpers ----

    private SpawnerChargeState current(Player player, MobsConfig cfg) {
        var user = ctx.users().user(player);
        long now = System.currentTimeMillis();
        SpawnerChargeState stored = user.get(store.component);
        if (stored.charges() == 0 && stored.lastGiveMillis() == 0) {
            // First use of the component: start at the configured amount and remember when that happened,
            // so a starting amount of 0 still refills after one cooldown.
            stored = SpawnerChargeState.initial(cfg.startingCharges(), now);
            SpawnerChargeState initial = stored;
            user.update(store.component, ignored -> initial);
        }
        return stored.refill(cfg.maxCharges(), cfg.chargeCooldownSeconds(), now);
    }

    private void spend(Player player, MobsConfig cfg) {
        SpawnerChargeState spent = current(player, cfg).spend();
        ctx.users().user(player).update(store.component, ignored -> spent);
    }

    private static boolean hasSilkTouch(Player player) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        return tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);
    }

    private static boolean hasBypass(Player player, @Nullable String permission) {
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    /**
     * Applies a new spawner type, calling {@link SpawnerChangeEvent}. Must run on the region thread that owns
     * the block; protection is checked by the caller.
     */
    static TypeChange changeType(Player player, Block block, EntityType type) {
        if (!(block.getState() instanceof CreatureSpawner spawner)) {
            return TypeChange.NOT_SPAWNER;
        }
        var event = new SpawnerChangeEvent(player, block, spawner.getSpawnedType(), type);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return TypeChange.DENIED;
        }
        spawner.setSpawnedType(type);
        spawner.update(true, false);
        return TypeChange.CHANGED;
    }

    enum TypeChange { CHANGED, DENIED, NOT_SPAWNER }
}
