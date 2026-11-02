package dev.yanianz.atrium.module.npcs;

import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.NpcManager;
import de.oliver.fancynpcs.api.actions.ActionTrigger;
import de.oliver.fancynpcs.api.skins.SkinData;
import de.oliver.fancynpcs.api.utils.NpcEquipmentSlot;
import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * Optional FancyNpcs backend. Loaded only when FancyNpcs is enabled. Atrium stores the definition; the
 * FancyNpcs NPC is a display created and updated from it, never edited directly by admins.
 */
final class FancyBackend implements NpcBackend, Listener {
    private final ModuleContext ctx;
    private final Nameplates nameplates;
    private final Supplier<Iterable<NpcDef>> definitions;
    private final BiConsumer<Player, NativeBackend.NpcInteraction> interactions;

    FancyBackend(ModuleContext ctx, Nameplates nameplates, Supplier<Iterable<NpcDef>> definitions,
            BiConsumer<Player, NativeBackend.NpcInteraction> interactions) {
        this.ctx = ctx;
        this.nameplates = nameplates;
        this.definitions = definitions;
        this.interactions = interactions;
    }

    @Override
    public NpcType type() {
        return NpcType.FANCYNPC;
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("FancyNpcs");
    }

    private NpcManager manager() {
        return FancyNpcsPlugin.get().getNpcManager();
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
        NpcManager manager = manager();
        Npc npc = manager.getNpc(def.name());
        if (npc == null) {
            NpcData data = new NpcData(def.name(), java.util.UUID.randomUUID(), location);
            data.setType(org.bukkit.entity.EntityType.PLAYER);
            configure(data, def, location);
            npc = FancyNpcsPlugin.get().getNpcAdapter().apply(data);
            manager.registerNpc(npc);
            npc.create();
            npc.spawnForAll();
            return;
        }
        configure(npc.getData(), def, location);
        npc.removeForAll();
        npc.spawnForAll();
    }

    private void configure(NpcData data, NpcDef def, Location location) {
        data.setLocation(location);
        boolean hologram = nameplates.active() && def.nameplate();
        // Off means no nameplate at all: FancyNpcs always renders its display name, so clear it.
        data.setDisplayName(!def.nameplate() || hologram ? "" : plain(def));
        data.setCollidable(false);
        data.setGlowing(!def.glow().isEmpty());
        NamedTextColor color = namedColor(def.glow());
        if (color != null) {
            data.setGlowingColor(color);
        }
        data.setTurnToPlayer(def.lookAt());
        data.setTurnToPlayerDistance((int) Math.max(1, Math.ceil(def.lookRange())));
        data.setScale((float) def.scale());
        if (def.skin() == null) {
            data.setSkinData(null);
            data.setMirrorSkin(false);
        } else {
            data.setSkin(def.skin());
        }
        Map<NpcEquipmentSlot, org.bukkit.inventory.ItemStack> equipment = new EnumMap<>(NpcEquipmentSlot.class);
        put(equipment, NpcEquipmentSlot.HEAD, def, "HEAD");
        put(equipment, NpcEquipmentSlot.CHEST, def, "CHEST");
        put(equipment, NpcEquipmentSlot.LEGS, def, "LEGS");
        put(equipment, NpcEquipmentSlot.FEET, def, "FEET");
        put(equipment, NpcEquipmentSlot.MAINHAND, def, "HAND");
        put(equipment, NpcEquipmentSlot.OFFHAND, def, "OFF_HAND");
        data.setEquipment(equipment);
        if (def.nameplate()) {
            nameplates.apply(def, def.location());
        } else {
            nameplates.remove(def);
        }
    }

    private void put(Map<NpcEquipmentSlot, org.bukkit.inventory.ItemStack> target, NpcEquipmentSlot slot, NpcDef def,
            String base64) {
        var item = NpcItems.decode(def.equipment().get(base64),
                message -> ctx.logger().warn("NPC '{}': {}", def.name(), message));
        if (item != null) {
            target.put(slot, item);
        }
    }

    private String plain(NpcDef def) {
        String value = PlainTextComponentSerializer.plainText().serialize(ctx.messages().parseTrusted(def.displayName()));
        return value.isBlank() ? def.name() : value;
    }

    private static NamedTextColor namedColor(String glow) {
        return switch (glow.toLowerCase(java.util.Locale.ROOT)) {
            case "black" -> NamedTextColor.BLACK;
            case "dark_blue" -> NamedTextColor.DARK_BLUE;
            case "dark_green" -> NamedTextColor.DARK_GREEN;
            case "dark_aqua" -> NamedTextColor.DARK_AQUA;
            case "dark_red" -> NamedTextColor.DARK_RED;
            case "dark_purple" -> NamedTextColor.DARK_PURPLE;
            case "gold" -> NamedTextColor.GOLD;
            case "gray", "grey" -> NamedTextColor.GRAY;
            case "dark_gray", "dark_grey" -> NamedTextColor.DARK_GRAY;
            case "blue" -> NamedTextColor.BLUE;
            case "green" -> NamedTextColor.GREEN;
            case "aqua" -> NamedTextColor.AQUA;
            case "red" -> NamedTextColor.RED;
            case "light_purple" -> NamedTextColor.LIGHT_PURPLE;
            case "yellow" -> NamedTextColor.YELLOW;
            case "white" -> NamedTextColor.WHITE;
            default -> null;
        };
    }

    @Override
    public void despawn(NpcDef def) {
        Npc npc = manager().getNpc(def.name());
        if (npc == null) {
            return;
        }
        npc.removeForAll();
        manager().removeNpc(npc);
        nameplates.remove(def);
    }

    @Override
    public void refresh(NpcDef def) {
        spawn(def);
    }

    @Override
    public void move(NpcDef def) {
        Location location = def.location().toLocation().orElse(null);
        Npc npc = manager().getNpc(def.name());
        if (location == null || npc == null) {
            spawn(def);
            return;
        }
        npc.getData().setLocation(location);
        npc.moveForAll();
        nameplates.apply(def, def.location());
    }

    /**
     * FancyNpcs renders packet-only NPCs, so there is usually no server entity to hit. When the plugin is
     * configured to spawn a real entity, match it by entity id; otherwise {@code /npc select} uses the cone.
     */
    @Override
    public @Nullable String npcKey(org.bukkit.entity.Entity entity) {
        if (entity == null || !available()) {
            return null;
        }
        int entityId = entity.getEntityId();
        for (Npc npc : manager().getAllNpcs()) {
            if (npc.getEntityId() != entityId) {
                continue;
            }
            String name = npc.getData().getName();
            for (NpcDef def : definitions.get()) {
                if (def.name().equalsIgnoreCase(name)) {
                    return def.key();
                }
            }
        }
        return null;
    }

    @Override
    public void shutdown() {
        for (NpcDef def : definitions.get()) {
            Npc npc = manager().getNpc(def.name());
            if (npc != null) {
                npc.removeForAll();
                manager().removeNpc(npc);
            }
            nameplates.remove(def);
        }
    }

    // ---- clicks ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(de.oliver.fancynpcs.api.events.NpcInteractEvent event) {
        Player player = event.getPlayer();
        ActionTrigger trigger = event.getInteractionType();
        if (player == null || trigger == null) {
            return;
        }
        NpcInteractClick click = trigger == ActionTrigger.LEFT_CLICK ? NpcInteractClick.LEFT
                : trigger == ActionTrigger.RIGHT_CLICK ? NpcInteractClick.RIGHT : null;
        if (click == null) {
            return;
        }
        event.setCancelled(true);
        String name = event.getNpc().getData().getName();
        for (NpcDef def : definitions.get()) {
            if (def.name().equalsIgnoreCase(name)) {
                interactions.accept(player, new NativeBackend.NpcInteraction(def.key(), click));
                return;
            }
        }
    }
}
