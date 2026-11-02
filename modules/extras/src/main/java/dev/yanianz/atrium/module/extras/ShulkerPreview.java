package dev.yanianz.atrium.module.extras;

import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

/**
 * Read-only shulker box preview: sneak + right-click air while holding a shulker box opens a menu with
 * copies of its contents.
 *
 * <p>The preview is an Atrium menu ({@code ctx.menus()}) without input slots, so every click and drag is
 * cancelled by the menu service and the shown items are clones that are never written back. A hotbar
 * switch, dropping the box or swapping hands closes the preview, so the copy can never outlive the item it
 * came from.
 */
final class ShulkerPreview implements Listener {
    private final ModuleContext ctx;
    private final Supplier<ExtrasConfig> config;
    /** Players currently looking at a preview (removed by the menu's close handler). */
    private final Map<UUID, Object> viewing = new ConcurrentHashMap<>();

    ShulkerPreview(ModuleContext ctx, Supplier<ExtrasConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    // ---- open ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.hasPermission("atrium.shulker")) {
            return;
        }
        ExtrasConfig cfg = config.get();
        if (cfg.shulker == null || !cfg.shulker.enabled) {
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty() || !Tag.SHULKER_BOXES.isTagged(held.getType())) {
            return;
        }
        event.setCancelled(true);
        open(player, held);
    }

    /** Runs on the player's thread (PlayerInteractEvent). */
    private void open(Player player, ItemStack held) {
        List<ItemStack> contents;
        try {
            contents = contentsOf(held);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not read the contents of a shulker box held by {}", player.getName(), e);
            ctx.messages().send(player, "core.error");
            return;
        }
        UUID id = player.getUniqueId();
        Object token = new Object(); // identifies this preview; a newer one replaces it
        // Empty slots stay empty (no filler) so the preview looks like the box itself.
        Menu.Builder menu = Menu.builder(ctx.messages().render(player, "extras.shulker-title"),
                        ShulkerContents.SLOTS / 9)
                .filler(false)
                .onClose((viewer, inventory) -> viewing.remove(id, token));
        for (int slot = 0; slot < contents.size(); slot++) {
            ItemStack item = contents.get(slot);
            if (item != null && !item.isEmpty()) {
                menu.item(slot, item); // display-only: the button holds its own clone
            }
        }
        viewing.put(id, token);
        ctx.menus().open(player, menu.build());
        if (!ctx.menus().isViewing(player)) {
            viewing.remove(id, token); // another plugin refused the open
        }
    }

    /**
     * Copies the contents of the shulker item. The block state is a snapshot, and every stack is cloned, so
     * the returned list shares nothing with the real item.
     */
    private static List<ItemStack> contentsOf(ItemStack item) {
        Material type = item.getType();
        if (!Tag.SHULKER_BOXES.isTagged(type) || !(item.getItemMeta() instanceof BlockStateMeta meta)) {
            return List.of();
        }
        BlockState state = meta.getBlockState();
        if (!(state instanceof ShulkerBox box)) {
            return List.of();
        }
        return ShulkerContents.copy(Arrays.asList(box.getInventory().getContents()), ShulkerContents.SLOTS,
                ItemStack::clone);
    }

    // ---- close when the source item may have moved ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemHeld(PlayerItemHeldEvent event) {
        close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        viewing.remove(event.getPlayer().getUniqueId());
    }

    /** Closes the player's preview, if one is open. Safe from any thread (the menu service hops). */
    private void close(Player player) {
        if (viewing.remove(player.getUniqueId()) != null && Bukkit.getPlayer(player.getUniqueId()) != null) {
            ctx.menus().close(player);
        }
    }
}
