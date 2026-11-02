package dev.yanianz.atrium.module.npcs;

import com.destroystokyo.paper.profile.ProfileProperty;
import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Native backend using Paper's {@link Mannequin} entity. Mannequins are not persistent: Atrium spawns them
 * again on {@link EntitiesLoadEvent}/{@link ChunkLoadEvent} for their own chunk only (a chunk is never loaded
 * to make an NPC appear) and a PDC marker identifies the ones Atrium owns.
 */
final class NativeBackend implements NpcBackend, Listener {
    private final ModuleContext ctx;
    private final Supplier<NpcsConfig> config;
    private final NpcSkinResolver skins;
    private final Nameplates nameplates;
    private volatile NativeLookAt lookAt;
    private final Supplier<Iterable<NpcDef>> definitions;
    private final BiConsumer<Player, NpcInteraction> interactions;
    private final NamespacedKey marker;
    /** Live mannequins keyed by NPC. Only accessed from the region that owns the NPC. */
    private final Map<String, Entity> entities = new ConcurrentHashMap<>();
    /** Suppresses the second event Paper sends for one physical right click. */
    private final Map<String, Long> recentClicks = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    record NpcInteraction(String npc, NpcInteractClick click) {}

    NativeBackend(ModuleContext ctx, Supplier<NpcsConfig> config, NpcSkinResolver skins, Nameplates nameplates,
            Supplier<Iterable<NpcDef>> definitions, BiConsumer<Player, NpcInteraction> interactions) {
        this.ctx = ctx;
        this.config = config;
        this.skins = skins;
        this.nameplates = nameplates;
        this.definitions = definitions;
        this.interactions = interactions;
        this.marker = new NamespacedKey(ctx.plugin(), "npc");
    }

    void lookAt(NativeLookAt tracker) {
        this.lookAt = tracker;
    }

    @Override
    public NpcType type() {
        return NpcType.NATIVE;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void spawn(NpcDef def) {
        if (stopping) {
            return;
        }
        Location location = def.location().toLocation().orElse(null);
        if (location == null || !chunkLoaded(location)) {
            return;
        }
        Entity existing = entity(def.key());
        if (existing instanceof Mannequin mannequin && mannequin.isValid()) {
            apply(mannequin, def, null);
            ensureLookAt(def);
            return;
        }
        if (def.skin() == null) {
            spawnNow(def, location, null);
            return;
        }
        skins.resolve(def.skin()).thenAccept(resolved -> {
            if (stopping) {
                return;
            }
            ctx.scheduler().region(location, () -> spawnNow(def, location, resolved.orElse(null)));
        });
    }

    private void spawnNow(NpcDef def, Location location, NpcSkinResolver.ResolvedSkin skin) {
        if (stopping || !chunkLoaded(location)) {
            return;
        }
        Entity current = entity(def.key());
        if (current instanceof Mannequin mannequin && mannequin.isValid()) {
            apply(mannequin, def, skin);
            ensureLookAt(def);
            return;
        }
        Mannequin mannequin;
        try {
            mannequin = location.getWorld().spawn(location, Mannequin.class, CreatureSpawnEvent.SpawnReason.CUSTOM,
                    false, entity -> {});
        } catch (RuntimeException e) {
            ctx.logger().error("Could not spawn NPC '{}' at {}", def.name(), location, e);
            return;
        }
        if (mannequin == null) {
            ctx.logger().warn("The world refused to spawn NPC '{}' at {}", def.name(), location);
            return;
        }
        mannequin.setPersistent(false);
        entities.put(def.key(), mannequin);
        apply(mannequin, def, skin);
        ensureLookAt(def);
    }

    private void apply(Mannequin mannequin, NpcDef def, NpcSkinResolver.ResolvedSkin skin) {
        mannequin.setPersistent(false);
        mannequin.setInvulnerable(true);
        mannequin.setSilent(true);
        mannequin.setGravity(false);
        mannequin.setCollidable(false);
        mannequin.setImmovable(true);
        mannequin.setRemoveWhenFarAway(false);
        mannequin.getPersistentDataContainer().set(marker, PersistentDataType.STRING, def.key());
        try {
            mannequin.setAI(false);
        } catch (RuntimeException e) {
            ctx.logger().debug("Mannequin {} does not support setAI", mannequin.getUniqueId());
        }

        if (skin != null && skin.hasTexture()) {
            ResolvableProfile.Builder profile = ResolvableProfile.resolvableProfile().name(skin.name());
            if (skin.profileId() != null) {
                profile.uuid(skin.profileId());
            }
            profile.addProperty(skin.signature() == null
                    ? new ProfileProperty("textures", skin.value())
                    : new ProfileProperty("textures", skin.value(), skin.signature()));
            mannequin.setProfile(profile.build());
        } else if (def.skin() == null) {
            mannequin.setProfile(Mannequin.defaultProfile());
        }

        boolean hologram = nameplates.active() && def.nameplate();
        if (hologram) {
            mannequin.customName(null);
            mannequin.setCustomNameVisible(false);
        } else {
            mannequin.customName(ctx.messages().parseTrusted(def.displayName()));
            mannequin.setCustomNameVisible(def.nameplate());
        }

        var equipment = mannequin.getEquipment();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.BODY) {
                continue;
            }
            equipment.setItem(slot, NpcItems.decode(def.equipment().get(slot.name()),
                    message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)), false);
        }

        applyPose(mannequin, def.pose());
        mannequin.setGlowing(!def.glow().isEmpty());
        AttributeInstance scale = mannequin.getAttribute(Attribute.SCALE);
        if (scale != null) {
            scale.setBaseValue(def.scale());
        }
        if (def.nameplate()) {
            nameplates.apply(def, def.location());
        } else {
            nameplates.remove(def);
        }
    }

    private void applyPose(Mannequin mannequin, String pose) {
        try {
            org.bukkit.entity.Pose value = org.bukkit.entity.Pose.valueOf(pose.toUpperCase(Locale.ROOT));
            if (Mannequin.validPoses().contains(value)) {
                mannequin.setPose(value);
            }
        } catch (IllegalArgumentException e) {
            ctx.logger().debug("Unknown mannequin pose '{}'", pose);
        }
    }

    @Override
    public void despawn(NpcDef def) {
        Entity entity = entities.remove(def.key());
        if (entity != null && entity.isValid()) {
            entity.remove();
        }
        stopLookAt(def.key());
        nameplates.remove(def);
    }

    @Override
    public void refresh(NpcDef def) {
        Entity entity = entity(def.key());
        if (entity instanceof Mannequin mannequin && mannequin.isValid()) {
            apply(mannequin, def, null);
            ensureLookAt(def);
        } else {
            spawn(def);
        }
    }

    @Override
    public void move(NpcDef def) {
        Location location = def.location().toLocation().orElse(null);
        if (location == null) {
            return;
        }
        Entity entity = entity(def.key());
        if (entity == null || !entity.isValid()) {
            spawn(def);
            return;
        }
        entity.teleportAsync(location).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().warn("Could not move NPC '{}': {}", def.name(), error.toString());
            }
        });
        nameplates.apply(def, def.location());
    }

    @Override
    public void shutdown() {
        stopping = true;
        for (Entity entity : new ArrayList<>(entities.values())) {
            if (entity == null || !entity.isValid()) {
                continue;
            }
            try {
                entity.remove();
            } catch (RuntimeException e) {
                // During server shutdown the region data is already gone; mannequins are non-persistent,
                // so they disappear with the world without needing this call.
                ctx.logger().debug("Could not remove mannequin {} during shutdown", entity.getUniqueId(), e);
            }
        }
        entities.clear();
        if (lookAt != null) {
            lookAt.shutdown();
        }
    }

    private void ensureLookAt(NpcDef def) {
        if (lookAt != null) {
            lookAt.ensure(def);
        }
    }

    private void stopLookAt(String key) {
        if (lookAt != null) {
            lookAt.stop(key);
        }
    }

    @Nullable
    Entity entity(String key) {
        Entity entity = entities.get(key);
        if (entity == null) {
            return null;
        }
        if (!entity.isValid()) {
            entities.remove(key);
            return null;
        }
        return entity;
    }

    /** The NPC key stored on an Atrium mannequin, or empty. */
    @Override
    public @Nullable String npcKey(Entity entity) {
        if (!(entity instanceof Mannequin)) {
            return null;
        }
        return entity.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
    }

    private boolean firstClick(Player player, String key) {
        String id = player.getUniqueId() + ":" + key;
        long now = System.currentTimeMillis();
        Long previous = recentClicks.put(id, now);
        return previous == null || now - previous > 150;
    }

    void removePlayer(UUID uuid) {
        String prefix = uuid + ":";
        recentClicks.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static boolean chunkLoaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    // ---- world events (region threads) ----

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (stopping) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Mannequin mannequin) {
                adoptOrRemove(mannequin);
            }
        }
        ensureChunk(event.getChunk());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (stopping) {
            return;
        }
        ensureChunk(event.getChunk());
    }

    private void ensureChunk(org.bukkit.Chunk chunk) {
        for (NpcDef def : definitions.get()) {
            if (!def.world().equals(chunk.getWorld().getName())
                    || (def.location().blockX() >> 4) != chunk.getX()
                    || (def.location().blockZ() >> 4) != chunk.getZ()) {
                continue;
            }
            spawn(def);
        }
    }

    private void adoptOrRemove(Mannequin mannequin) {
        String key = mannequin.getPersistentDataContainer().get(marker, PersistentDataType.STRING);
        if (key == null) {
            return;
        }
        NpcDef def = null;
        for (NpcDef candidate : definitions.get()) {
            if (candidate.key().equals(key)) {
                def = candidate;
                break;
            }
        }
        if (def == null) {
            mannequin.remove();
            return;
        }
        entities.put(key, mannequin);
        apply(mannequin, def, null);
        ensureLookAt(def);
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Mannequin) {
                String key = npcKey(entity);
                entities.values().removeIf(candidate -> candidate.getUniqueId().equals(entity.getUniqueId()));
                if (key != null) {
                    stopLookAt(key);
                }
            }
        }
    }

    // ---- clicks (player thread) ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String key = npcKey(event.getRightClicked());
        if (key == null) {
            return;
        }
        event.setCancelled(true);
        if (!firstClick(event.getPlayer(), key)) {
            return;
        }
        interactions.accept(event.getPlayer(), new NpcInteraction(key, NpcInteractClick.RIGHT));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteractAt(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String key = npcKey(event.getRightClicked());
        if (key == null) {
            return;
        }
        event.setCancelled(true);
        if (!firstClick(event.getPlayer(), key)) {
            return;
        }
        interactions.accept(event.getPlayer(), new NpcInteraction(key, NpcInteractClick.RIGHT));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        String key = npcKey(event.getAttacked());
        if (key == null) {
            return;
        }
        event.setCancelled(true);
        interactions.accept(event.getPlayer(), new NpcInteraction(key, NpcInteractClick.LEFT));
    }
}
