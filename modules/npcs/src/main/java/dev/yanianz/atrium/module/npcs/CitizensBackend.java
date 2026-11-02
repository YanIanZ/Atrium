package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.DespawnReason;
import net.citizensnpcs.api.event.NPCLeftClickEvent;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.trait.trait.Equipment;
import net.citizensnpcs.trait.LookClose;
import net.citizensnpcs.trait.SkinTrait;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/**
 * Optional Citizens backend. This class is loaded only when Citizens is enabled, so a server without
 * Citizens never links a class that references its API.
 */
final class CitizensBackend implements NpcBackend, Listener {
    private final ModuleContext ctx;
    private final Nameplates nameplates;
    private final Supplier<Iterable<NpcDef>> definitions;
    private final BiConsumer<Player, NativeBackend.NpcInteraction> interactions;
    private final Map<String, Integer> ids = new java.util.concurrent.ConcurrentHashMap<>();

    CitizensBackend(ModuleContext ctx, Nameplates nameplates, Supplier<Iterable<NpcDef>> definitions,
            BiConsumer<Player, NativeBackend.NpcInteraction> interactions) {
        this.ctx = ctx;
        this.nameplates = nameplates;
        this.definitions = definitions;
        this.interactions = interactions;
    }

    @Override
    public NpcType type() {
        return NpcType.CITIZENS;
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens") && CitizensAPI.hasImplementation();
    }

    private NPCRegistry registry() {
        return CitizensAPI.getNPCRegistry();
    }

    private NPC find(NpcDef def) {
        NPCRegistry registry = registry();
        if (registry == null) {
            return null;
        }
        Integer id = ids.get(def.key());
        if (id != null) {
            NPC npc = registry.getById(id);
            if (npc != null) {
                return npc;
            }
            ids.remove(def.key());
        }
        for (NPC candidate : registry) {
            if (candidate.getName() != null && candidate.getName().equalsIgnoreCase(def.name())) {
                ids.put(def.key(), candidate.getId());
                return candidate;
            }
        }
        return null;
    }

    @Override
    public void spawn(NpcDef def) {
        if (!available()) {
            return;
        }
        Location location = def.location().toLocation().orElse(null);
        if (location == null) {
            return;
        }
        NPCRegistry registry = registry();
        NPC npc = find(def);
        if (npc == null) {
            npc = registry.createNPC(EntityType.PLAYER, def.name());
        }
        ids.put(def.key(), npc.getId());
        apply(npc, def);
        if (!npc.isSpawned()) {
            npc.spawn(location, SpawnReason.PLUGIN);
        } else {
            npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        // The scale attribute and the sneaking flag only exist once Citizens has spawned the entity.
        applyLive(npc, def);
    }

    private void apply(NPC npc, NpcDef def) {
        boolean hologram = nameplates.active() && def.nameplate();
        npc.setName(hologram ? "" : display(def));
        npc.data().setPersistent(NPC.Metadata.NAMEPLATE_VISIBLE, def.nameplate() && !hologram);
        SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
        if (def.skin() == null) {
            skin.clearTexture();
        } else {
            skin.setSkinName(def.skin());
        }
        LookClose look = npc.getOrAddTrait(LookClose.class);
        look.lookClose(def.lookAt());
        look.setRange(Math.max(1, def.lookRange()));
        npc.data().setPersistent(NPC.Metadata.GLOWING, !def.glow().isEmpty());
        npc.data().setPersistent(NPC.Metadata.COLLIDABLE, false);

        Equipment equipment = npc.getOrAddTrait(Equipment.class);
        equipment.set(toCitizensSlot("HAND"), NpcItems.decode(def.equipment().get("HAND"), message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)));
        equipment.set(toCitizensSlot("OFF_HAND"), NpcItems.decode(def.equipment().get("OFF_HAND"), message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)));
        equipment.set(toCitizensSlot("HEAD"), NpcItems.decode(def.equipment().get("HEAD"), message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)));
        equipment.set(toCitizensSlot("CHEST"), NpcItems.decode(def.equipment().get("CHEST"), message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)));
        equipment.set(toCitizensSlot("LEGS"), NpcItems.decode(def.equipment().get("LEGS"), message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)));
        equipment.set(toCitizensSlot("FEET"), NpcItems.decode(def.equipment().get("FEET"), message -> ctx.logger().warn("NPC '{}': {}", def.name(), message)));

        applyLive(npc, def);
        if (def.nameplate()) {
            nameplates.apply(def, def.location());
        } else {
            nameplates.remove(def);
        }
    }

    /** Pose and scale need the spawned entity. Citizens only carries a sneaking flag, so other poses are stored but not shown. */
    private void applyLive(NPC npc, NpcDef def) {
        npc.setSneaking(NpcPose.SNEAKING.equals(def.pose()));
        Entity entity = npc.getEntity();
        if (entity instanceof LivingEntity living) {
            AttributeInstance scale = living.getAttribute(Attribute.SCALE);
            if (scale != null) {
                scale.setBaseValue(def.scale());
            }
        }
    }

    private String display(NpcDef def) {
        String plain = PlainTextComponentSerializer.plainText()
                .serialize(ctx.messages().parseTrusted(def.displayName()));
        return plain.isBlank() ? def.name() : plain;
    }

    private static Equipment.EquipmentSlot toCitizensSlot(String slot) {
        return switch (slot) {
            case "HEAD" -> Equipment.EquipmentSlot.HELMET;
            case "CHEST" -> Equipment.EquipmentSlot.CHESTPLATE;
            case "LEGS" -> Equipment.EquipmentSlot.LEGGINGS;
            case "FEET" -> Equipment.EquipmentSlot.BOOTS;
            case "OFF_HAND" -> Equipment.EquipmentSlot.OFF_HAND;
            default -> Equipment.EquipmentSlot.HAND;
        };
    }

    @Override
    public void despawn(NpcDef def) {
        NPC npc = find(def);
        if (npc == null) {
            return;
        }
        npc.despawn(DespawnReason.PLUGIN);
        registry().deregister(npc);
        ids.remove(def.key());
        nameplates.remove(def);
    }

    @Override
    public void refresh(NpcDef def) {
        NPC npc = find(def);
        if (npc == null) {
            spawn(def);
            return;
        }
        if (npc.isSpawned()) {
            npc.despawn(DespawnReason.PLUGIN);
        }
        spawn(def);
    }

    @Override
    public void move(NpcDef def) {
        Location location = def.location().toLocation().orElse(null);
        NPC npc = find(def);
        if (location == null || npc == null) {
            spawn(def);
            return;
        }
        if (npc.isSpawned()) {
            npc.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else {
            npc.spawn(location, SpawnReason.PLUGIN);
        }
        applyLive(npc, def);
        nameplates.apply(def, def.location());
    }

    @Override
    public @Nullable String npcKey(Entity entity) {
        if (entity == null) {
            return null;
        }
        NPCRegistry registry = registry();
        NPC npc = registry == null ? null : registry.getNPC(entity);
        if (npc == null || npc.getName() == null) {
            return null;
        }
        for (NpcDef def : definitions.get()) {
            if (def.name().equalsIgnoreCase(npc.getName())) {
                return def.key();
            }
        }
        return null;
    }

    @Override
    public void shutdown() {
        for (NpcDef def : definitions.get()) {
            NPC npc = find(def);
            if (npc != null) {
                npc.despawn(DespawnReason.PLUGIN);
                registry().deregister(npc);
            }
            nameplates.remove(def);
        }
        ids.clear();
    }

    // ---- clicks (player thread) ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onRight(NPCRightClickEvent event) {
        click(event.getNPC(), event.getClicker(), NpcInteractClick.RIGHT);
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onLeft(NPCLeftClickEvent event) {
        click(event.getNPC(), event.getClicker(), NpcInteractClick.LEFT);
        event.setCancelled(true);
    }

    private void click(NPC npc, Player player, NpcInteractClick click) {
        if (player == null || npc.getName() == null) {
            return;
        }
        for (NpcDef def : definitions.get()) {
            if (def.name().equalsIgnoreCase(npc.getName())) {
                interactions.accept(player, new NativeBackend.NpcInteraction(def.key(), click));
                return;
            }
        }
    }
}
