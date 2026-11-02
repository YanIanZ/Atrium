package dev.yanianz.atrium.module.armorstand;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Applies armor stand templates and (optionally) arms when a player places a stand.
 *
 * <p>The template id lives on the armor stand item that is actually being placed, so only that item can create
 * that stand: no nearby player's held item is ever consulted (the old "nearest player" guess let a template
 * spread to other players' stands). The spawn is matched to the click that caused it by position and time and
 * the pending entry is consumed, so it applies once.
 */
final class TemplateListener implements Listener {
    static final String PERM_ARMS = "atrium.armorstand.arms";

    private final ModuleContext ctx;
    private final ConfigHandle<ArmorStandConfig> config;
    private final TemplateStore store;
    private final NamespacedKey templateKey;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /** Where a placed stand is expected, and which template the used item carried. */
    private record Pending(@Nullable String template, Location at, long expires) {}

    TemplateListener(ModuleContext ctx, ConfigHandle<ArmorStandConfig> config, TemplateStore store,
            NamespacedKey templateKey) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.templateKey = templateKey;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ArmorStandConfig cfg = config.get();
        if (!cfg.templatesEnabled && !cfg.autoArmsOnPlace) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ARMOR_STAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        String template = cfg.templatesEnabled ? item.getPersistentDataContainer().get(templateKey,
                PersistentDataType.STRING) : null;
        Location expected = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        pending.put(event.getPlayer().getUniqueId(),
                new Pending(template, expected, System.currentTimeMillis() + 2_000L));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof ArmorStand stand)) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            Pending candidate = entry.getValue();
            if (candidate.expires() < now) {
                pending.remove(entry.getKey(), candidate);
                continue;
            }
            Location where = stand.getLocation();
            if (where.getWorld() != candidate.at().getWorld()
                    || where.distanceSquared(candidate.at()) > 4.0) {
                continue;
            }
            if (!pending.remove(entry.getKey(), candidate)) {
                continue;
            }
            Player placer = ctx.plugin().getServer().getPlayer(entry.getKey());
            applyTemplate(stand, candidate.template(), placer);
            applyArms(stand, placer);
            return;
        }
    }

    private void applyTemplate(ArmorStand stand, @Nullable String template, @Nullable Player placer) {
        if (template == null || template.isBlank()) {
            return;
        }
        Optional<StandTemplate> found = store.get(template);
        if (found.isEmpty()) {
            if (placer != null) {
                ctx.messages().send(placer, "armorstand.template-missing", Msg.unparsed("name", template));
            }
            return;
        }
        found.get().apply(stand);
        if (placer != null) {
            ctx.messages().send(placer, "armorstand.template-applied", Msg.unparsed("name", template));
        }
    }

    private void applyArms(ArmorStand stand, @Nullable Player placer) {
        if (!config.get().autoArmsOnPlace || placer == null || stand.hasArms()) {
            return;
        }
        if (!placer.hasPermission(PERM_ARMS)) {
            return;
        }
        // Only a stand that is still empty is touched, so a template with arms is never overridden.
        if (stand.getEquipment() != null && stand.getEquipment().getItemInMainHand().getType() != Material.AIR) {
            return;
        }
        stand.setArms(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    NamespacedKey templateKey() {
        return templateKey;
    }

    /** Tags an armor stand item with a template name; the item itself is what carries the template. */
    ItemStack tag(ItemStack item, String template) {
        item.editPersistentDataContainer(pdc -> pdc.set(templateKey, PersistentDataType.STRING, template));
        return item;
    }
}
