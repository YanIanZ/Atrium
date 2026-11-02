package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * God mode, the tool smuggling guards, tool interactions, command spy and the crash-safe join/quit handling.
 *
 * <p>Tool guards apply to every player: a staff tool found outside staff mode (module reload, an old
 * inventory) is deleted on first use instead of becoming a real item.
 */
final class StaffListener implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<StaffConfig> config;
    private final StaffManager manager;
    private final StaffTools tools;
    private final StaffStore store;
    private final SpectateManager spectate;

    StaffListener(ModuleContext ctx, ConfigHandle<StaffConfig> config, StaffManager manager, StaffTools tools,
            StaffStore store, SpectateManager spectate) {
        this.ctx = ctx;
        this.config = config;
        this.manager = manager;
        this.tools = tools;
        this.store = store;
        this.spectate = spectate;
    }

    // ---- god mode: only players in staff mode, and only protection (they cannot hurt others either) ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !manager.isActive(player)) {
            return;
        }
        // /kill and the void still work, so a staff member can never be stuck falling forever.
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause != EntityDamageEvent.DamageCause.KILL && cause != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player player && manager.isActive(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && manager.isActive(player)
                && event.getFoodLevel() < player.getFoodLevel()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && manager.isActive(player)) {
            event.setCancelled(true);
        }
    }

    /** A death in staff mode (/kill, void) must neither drop tools nor anything else. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (manager.isLocked(player)) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            return;
        }
        event.getDrops().removeIf(tools::isStaffItem);
    }

    // ---- items: pickup, drop, hands, entities, blocks ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (tools.isStaffItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
            return;
        }
        if (!(event.getEntity() instanceof Player player) || !manager.isLocked(player)) {
            return;
        }
        if (config.get().blockItemPickup || !manager.isActive(player)) {
            event.setCancelled(true);
            if (manager.isActive(player)) {
                ctx.messages().actionBar(player, "staff.item-pickup-blocked");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        boolean tool = tools.isStaffItem(event.getItemDrop().getItemStack());
        if (!manager.isLocked(player)) {
            if (tool) {
                event.getItemDrop().remove(); // stray tool: delete instead of dropping
            }
            return;
        }
        if (tool || config.get().blockItemDrop || !manager.isActive(player)) {
            event.setCancelled(true);
            ctx.messages().actionBar(player, "staff.item-drop-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        StaffTools.Tool tool = tools.toolOf(event.getItem());
        if (tool == null) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true); // a tool is never used as its material (pearl, head block, ...)
        if (!manager.isActive(player)) {
            deleteStray(player, event.getHand());
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (tool == StaffTools.Tool.FREEZE || tool == StaffTools.Tool.INSPECT) {
            ctx.messages().actionBar(player, "staff.tool-needs-player");
            return;
        }
        manager.useTool(player, tool, null);
    }

    /** Also receives {@code PlayerInteractAtEntityEvent} (same handler list). Item frames, mobs, players. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        EquipmentSlot hand = event.getHand();
        StaffTools.Tool tool = tools.toolOf(player.getInventory().getItem(hand));
        if (tool == null) {
            return;
        }
        event.setCancelled(true);
        if (!manager.isActive(player)) {
            deleteStray(player, hand);
            return;
        }
        if (hand != EquipmentSlot.HAND || event instanceof org.bukkit.event.player.PlayerInteractAtEntityEvent) {
            return; // the plain event follows for the main hand; act once
        }
        if (!(event.getRightClicked() instanceof Player target)) {
            if (tool == StaffTools.Tool.FREEZE || tool == StaffTools.Tool.INSPECT) {
                ctx.messages().actionBar(player, "staff.tool-needs-player");
                return;
            }
            manager.useTool(player, tool, null);
            return;
        }
        if (!player.getWorld().equals(target.getWorld())) {
            ctx.messages().actionBar(player, "staff.tool-too-far");
            return;
        }
        int range = Math.max(1, Math.min(32, config.get().toolRange));
        if (player.getLocation().distanceSquared(target.getLocation()) > (double) range * range) {
            ctx.messages().actionBar(player, "staff.tool-too-far");
            return;
        }
        manager.useTool(player, tool, target);
    }

    /** Own handler list: not covered by {@link #onInteractEntity}. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (tools.isStaffItem(event.getPlayerItem()) || tools.isStaffItem(event.getArmorStandItem())) {
            event.setCancelled(true);
        } else if (manager.isLocked(event.getPlayer())) {
            event.setCancelled(true); // nothing is moved onto or off armour stands while in staff mode
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemFrame(PlayerItemFrameChangeEvent event) {
        if (tools.isStaffItem(event.getItemStack())
                || (event.getAction() == PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE
                        && manager.isLocked(event.getPlayer()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (tools.isStaffItem(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (tools.isStaffItem(event.getMainHandItem()) || tools.isStaffItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    // ---- inventories ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        ItemStack swapped = swappedItem(event, player);
        boolean anyTool = tools.isStaffItem(cursor) || tools.isStaffItem(current) || tools.isStaffItem(swapped);
        if (!anyTool) {
            return;
        }
        if (!manager.isActive(player)) {
            // A stray tool (own inventory or somebody else's via /invsee): delete it, never move it.
            event.setCancelled(true);
            if (tools.isStaffItem(current)) {
                event.setCurrentItem(null);
            }
            if (tools.isStaffItem(cursor)) {
                event.getView().setCursor(null);
            }
            return;
        }
        Inventory clicked = event.getClickedInventory();
        boolean ownStorage = clicked != null && clicked.equals(player.getInventory())
                && event.getSlot() >= 0 && event.getSlot() < storageSlots(player);
        boolean otherViewOpen = event.getView().getTopInventory().getType() != InventoryType.CRAFTING;
        if (!ToolClickPolicy.allowed(category(event), ownStorage, otherViewOpen,
                kind(cursor), kind(current), kind(swapped))) {
            event.setCancelled(true);
            ctx.messages().actionBar(player, "staff.item-move-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean staffItem = tools.isStaffItem(event.getOldCursor())
                || event.getNewItems().values().stream().anyMatch(tools::isStaffItem);
        if (staffItem) {
            event.setCancelled(true);
            if (manager.isActive(player)) {
                ctx.messages().actionBar(player, "staff.item-move-blocked");
            }
        }
    }

    /** Hoppers, droppers and hopper minecarts never carry a staff tool. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (tools.isStaffItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        if (tools.isStaffItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
            event.getItem().remove();
        }
    }

    // ---- commands ----

    /** Other plugins' item commands (/hat, /sell hand, /ah sell, ...) are refused in staff mode. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStaffCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!manager.isLocked(player)) {
            return;
        }
        String label = CommandNames.label(event.getMessage());
        if (CommandNames.matches(label, StaffListener::knownNames, config.get().blockedCommands)) {
            event.setCancelled(true);
            ctx.messages().send(player, "staff.command-blocked", Msg.unparsed("command", "/" + label));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player sender = event.getPlayer();
        if (sender.hasPermission("atrium.commandspy.exempt")) {
            return;
        }
        String masked = CommandSpyFilter.mask(event.getMessage(), config.get().sensitiveCommands,
                StaffListener::knownNames);
        String key = event.isCancelled() ? "staff.commandspy-format-cancelled" : "staff.commandspy-format";
        String senderName = sender.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(sender) || !viewer.hasPermission("atrium.commandspy")) {
                continue;
            }
            boolean spying;
            try {
                spying = ctx.users().user(viewer).get(store.preferences).commandSpy();
            } catch (RuntimeException notLoaded) {
                continue; // preferences of a player who is still joining are not loaded yet
            }
            if (spying) {
                ctx.scheduler().entity(viewer, () -> ctx.messages().send(viewer, key,
                        Msg.unparsed("player", senderName),
                        Msg.unparsed("command", masked)), null);
            }
        }
    }

    /** Every name the server knows for the command behind {@code label}: real name, label and aliases. */
    static Collection<String> knownNames(String label) {
        List<String> names = new ArrayList<>();
        try {
            Command command = Bukkit.getCommandMap().getCommand(CommandNames.normalize(label));
            if (command == null) {
                command = Bukkit.getCommandMap().getCommand(label.toLowerCase(java.util.Locale.ROOT));
            }
            if (command != null) {
                names.add(command.getName());
                names.add(command.getLabel());
                names.addAll(command.getAliases());
            }
        } catch (RuntimeException e) {
            // an unknown or broken command never prevents masking by the typed label
        }
        return names;
    }

    // ---- lifecycle ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        manager.restoreOnJoin(player);
        spectate.restoreOnJoin(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
        spectate.handleQuit(event.getPlayer());
    }

    // ---- helpers ----

    private void deleteStray(Player player, @Nullable EquipmentSlot hand) {
        if (hand != null) {
            player.getInventory().setItem(hand, null);
        }
    }

    /** The second stack moved by a number-key or off-hand swap, otherwise {@code null}. */
    private static @Nullable ItemStack swappedItem(InventoryClickEvent event, Player player) {
        if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
            return player.getInventory().getItem(event.getHotbarButton());
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND) {
            return player.getInventory().getItemInOffHand();
        }
        return null;
    }

    private ToolClickPolicy.Kind kind(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return ToolClickPolicy.Kind.EMPTY;
        }
        return tools.isStaffItem(stack) ? ToolClickPolicy.Kind.TOOL : ToolClickPolicy.Kind.OTHER;
    }

    private static ToolClickPolicy.Category category(InventoryClickEvent event) {
        ClickType click = event.getClick();
        if (click == ClickType.CREATIVE || click == ClickType.MIDDLE) {
            return ToolClickPolicy.Category.OTHER; // creative set-slot / clone
        }
        InventoryAction action = event.getAction();
        return switch (action) {
            case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, PLACE_ALL, PLACE_ONE, PLACE_SOME,
                    SWAP_WITH_CURSOR, HOTBAR_SWAP, NOTHING -> ToolClickPolicy.Category.MOVE;
            case MOVE_TO_OTHER_INVENTORY -> ToolClickPolicy.Category.SHIFT;
            case DROP_ALL_CURSOR, DROP_ONE_CURSOR, DROP_ALL_SLOT, DROP_ONE_SLOT -> ToolClickPolicy.Category.DROP;
            default -> ToolClickPolicy.Category.OTHER;
        };
    }

    /** The storage part (hotbar + main) of the live inventory: 36 on a vanilla server, armour and off hand follow. */
    private static int storageSlots(Player player) {
        return player.getInventory().getStorageContents().length;
    }
}
