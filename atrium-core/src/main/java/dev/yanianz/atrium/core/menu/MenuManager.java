package dev.yanianz.atrium.core.menu;

import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Theme;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The menu engine behind {@link MenuService}. Owns the safety listener:
 * <ul>
 *   <li>every click/drag in a menu view (menu slots and the player's inventory, shift-click, number keys,
 *       double-click collect, drag) is cancelled at LOWEST and again at HIGHEST unless {@link ClickPolicy}
 *       allows it for a declared input slot;</li>
 *   <li>button handlers are debounced per player and run on the viewer's thread one tick later;</li>
 *   <li>menus close on quit, world change, module disable and plugin disable;</li>
 *   <li>items left in input slots are returned on close.</li>
 * </ul>
 * One instance per plugin; modules get an owner-scoped view through {@link #scoped(String)}.
 */
public final class MenuManager implements MenuService, Listener {
    public static final String CORE_OWNER = "core";

    private static volatile @Nullable MenuManager installed;

    private final JavaPlugin plugin;
    private final Scheduler scheduler;
    private final Messages messages;
    private final Logger logger;
    private final ClickDebounce debounce = new ClickDebounce();
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Scoped> scopes = new ConcurrentHashMap<>();
    private final Set<String> reportedFillers = ConcurrentHashMap.newKeySet();
    private volatile @Nullable String fillerName;
    private volatile ItemStack fillerItem = ItemStack.empty();

    private enum Mode { ROOT, PUSH, REPLACE, REFRESH, BACK }

    private record Entry(MenuProvider provider, String owner) {}

    private static final class Session {
        final Deque<Entry> history = new ArrayDeque<>();
        @Nullable MenuHolder current;
    }

    public MenuManager(JavaPlugin plugin, Scheduler scheduler, Messages messages, Logger logger) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.messages = messages;
        this.logger = logger;
    }

    /** The running instance, for {@code ModuleContext.menus()} implementations without a manager. */
    public static MenuService installed() {
        MenuManager manager = installed;
        if (manager == null) {
            throw new IllegalStateException("The Atrium menu service is not running");
        }
        return manager;
    }

    /** Registers the safety listener. Call once on enable. */
    public void install() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        installed = this;
    }

    /** Closes every open menu and unregisters the listener. Call on plugin disable. */
    public void shutdown() {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            closeIf(player, holder -> true);
        }
        sessions.clear();
        HandlerList.unregisterAll(this);
        if (installed == this) {
            installed = null;
        }
    }

    /** A view whose menus are tagged with {@code owner} and closed by {@link #closeOwnedBy(String)}. */
    public MenuService scoped(String owner) {
        if (CORE_OWNER.equals(owner)) {
            return this;
        }
        return scopes.computeIfAbsent(owner, Scoped::new);
    }

    /** Closes every menu opened by a module (module disable). */
    public void closeOwnedBy(String owner) {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            closeIf(player, holder -> holder.owner().equals(owner));
        }
        scopes.remove(owner);
    }

    private void closeIf(Player player, java.util.function.Predicate<MenuHolder> test) {
        Runnable close = () -> {
            MenuHolder holder = ours(player.getOpenInventory());
            if (holder != null && test.test(holder)) {
                player.closeInventory();
            }
        };
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            close.run();
        } else {
            try {
                scheduler.entity(player, close, null);
            } catch (RuntimeException e) {
                // Scheduler already shut down (plugin disable on Folia): the server closes views on stop.
                logger.debug("Could not schedule menu close for {}", player.getName(), e);
            }
        }
    }

    // ---------------------------------------------------------------- MenuService

    @Override
    public void open(Player viewer, MenuProvider menu) {
        show(viewer, menu, CORE_OWNER, Mode.ROOT);
    }

    @Override
    public void push(Player viewer, MenuProvider menu) {
        show(viewer, menu, CORE_OWNER, Mode.PUSH);
    }

    @Override
    public void replace(Player viewer, MenuProvider menu) {
        show(viewer, menu, CORE_OWNER, Mode.REPLACE);
    }

    @Override
    public void refresh(Player viewer) {
        onViewerThread(viewer, () -> {
            MenuHolder holder = ours(viewer.getOpenInventory());
            if (holder != null) {
                showNow(viewer, holder.provider(), holder.owner(), Mode.REFRESH);
            }
        });
    }

    @Override
    public boolean back(Player viewer) {
        Session session = sessions.get(viewer.getUniqueId());
        boolean has = session != null && !session.history.isEmpty();
        onViewerThread(viewer, () -> {
            Session s = sessions.get(viewer.getUniqueId());
            Entry previous = s == null ? null : s.history.pollFirst();
            if (previous == null) {
                closeNow(viewer);
            } else {
                showNow(viewer, previous.provider(), previous.owner(), Mode.BACK);
            }
        });
        return has;
    }

    @Override
    public void close(Player viewer) {
        onViewerThread(viewer, () -> closeNow(viewer));
    }

    @Override
    public boolean isViewing(Player viewer) {
        return ours(viewer.getOpenInventory()) != null;
    }

    @Override
    public boolean hasHistory(Player viewer) {
        Session session = sessions.get(viewer.getUniqueId());
        return session != null && !session.history.isEmpty();
    }

    @Override
    public void confirm(Player viewer, Component title, List<Component> details, ClickHandler onConfirm,
            @Nullable ClickHandler onCancel) {
        push(viewer, ConfirmMenu.of(this, title, details, onConfirm, onCancel));
    }

    @Override
    public Component text(Player viewer, String key, TagResolver... resolvers) {
        return messages.render(viewer, key, resolvers);
    }

    @Override
    public ItemStack filler() {
        String wanted = theme().gui().filler();
        if (!Objects.equals(wanted, fillerName)) {
            fillerItem = buildFiller(wanted);
            fillerName = wanted;
        }
        return fillerItem.clone();
    }

    private ItemStack buildFiller(String name) {
        if (name == null || name.isBlank()) {
            return ItemStack.empty();
        }
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) {
            if (reportedFillers.add(name)) {
                logger.warn("config.yml theme.gui.filler: '{}' is not an item; menus are shown without filler", name);
            }
            return ItemStack.empty();
        }
        return ItemBuilder.of(material).hideTooltip().build();
    }

    @Override
    public void playClick(Player viewer) {
        play(viewer, theme().gui().clickSound());
    }

    @Override
    public void playError(Player viewer) {
        play(viewer, theme().gui().errorSound());
    }

    private void playOpen(Player viewer) {
        play(viewer, theme().gui().openSound());
    }

    private void play(Player viewer, @Nullable Key sound) {
        if (sound == null) {
            return;
        }
        float volume = theme().gui().soundVolume();
        if (volume <= 0f) {
            return;
        }
        viewer.playSound(Sound.sound(sound, Sound.Source.MASTER, volume, 1f));
    }

    @Override
    public Theme theme() {
        return messages.theme();
    }

    // ---------------------------------------------------------------- opening

    private void onViewerThread(Player viewer, Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(viewer)) {
            task.run();
        } else {
            scheduler.entity(viewer, task, null);
        }
    }

    private void show(Player viewer, MenuProvider provider, String owner, Mode mode) {
        Objects.requireNonNull(provider, "menu");
        onViewerThread(viewer, () -> showNow(viewer, provider, owner, mode));
    }

    private void showNow(Player viewer, MenuProvider provider, String owner, Mode mode) {
        if (!viewer.isOnline()) {
            return;
        }
        Session session = sessions.computeIfAbsent(viewer.getUniqueId(), id -> new Session());
        MenuHolder previous = ours(viewer.getOpenInventory());
        Menu menu;
        try {
            menu = Objects.requireNonNull(provider.render(viewer), "provider returned no menu");
        } catch (RuntimeException e) {
            logger.error("Menu of module '{}' failed to render for {}", owner, viewer.getName(), e);
            playError(viewer);
            return;
        }
        if ((mode == Mode.REPLACE || mode == Mode.REFRESH) && previous != null && sameShape(previous.menu(), menu)) {
            // Update in place: no flicker, the cursor and input items stay where they are.
            previous.update(provider, owner, menu);
            render(previous.getInventory(), menu, false);
            return;
        }
        switch (mode) {
            case ROOT -> session.history.clear();
            case PUSH -> {
                if (previous != null) {
                    session.history.push(new Entry(previous.provider(), previous.owner()));
                }
            }
            default -> { }
        }
        MenuHolder holder = new MenuHolder(this, owner, provider, menu, viewer.getUniqueId());
        render(holder.getInventory(), menu, true);
        session.current = holder;
        InventoryView view;
        try {
            view = viewer.openInventory(holder.getInventory());
        } catch (RuntimeException e) {
            logger.error("Opening a menu of module '{}' for {} failed", owner, viewer.getName(), e);
            view = null;
        }
        if (view == null || ours(viewer.getOpenInventory()) != holder) {
            // Another plugin cancelled the open: roll back.
            holder.markClosed();
            session.current = previous;
            if (mode == Mode.PUSH && previous != null) {
                session.history.pollFirst();
            }
            if (previous == null) {
                sessions.remove(viewer.getUniqueId());
            }
            return;
        }
        if (mode == Mode.ROOT || mode == Mode.PUSH) {
            playOpen(viewer);
        }
    }

    private static boolean sameShape(Menu a, Menu b) {
        return a.rows() == b.rows() && a.title().equals(b.title()) && a.inputSlots().equals(b.inputSlots());
    }

    private void render(Inventory inventory, Menu menu, boolean fresh) {
        ItemStack filler = null;
        if (menu.filler()) {
            ItemStack custom = menu.fillerItem();
            filler = custom != null ? custom.clone() : filler();
            if (filler.isEmpty()) {
                filler = null;
            }
        }
        for (int slot = 0; slot < menu.size(); slot++) {
            if (menu.inputSlots().contains(slot)) {
                if (fresh) {
                    inventory.setItem(slot, null);
                }
                continue;
            }
            Button button = menu.button(slot);
            if (button != null) {
                inventory.setItem(slot, button.icon());
            } else {
                inventory.setItem(slot, filler == null ? null : filler.clone());
            }
        }
    }

    private void closeNow(Player viewer) {
        if (ours(viewer.getOpenInventory()) != null) {
            viewer.closeInventory();
        }
    }

    private @Nullable MenuHolder ours(InventoryView view) {
        MenuHolder holder = MenuHolder.of(view);
        return holder != null && holder.manager() == this ? holder : null;
    }

    // ---------------------------------------------------------------- safety listener

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickFirst(InventoryClickEvent event) {
        MenuHolder holder = ours(event.getView());
        if (holder != null && !allowed(event, holder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClickLast(InventoryClickEvent event) {
        MenuHolder holder = ours(event.getView());
        if (holder == null) {
            return;
        }
        if (!allowed(event, holder)) {
            event.setCancelled(true); // re-assert: another plugin may have un-cancelled it
            shiftInsert(event, holder);
        }
        dispatch(event, holder);
    }

    /**
     * Vanilla shift-click from the player's inventory is always cancelled (it would merge into display
     * slots). For menus that accept player-inventory input, the move is redone here into input slots only:
     * next tick on the viewer's thread, after checking the source slot still holds exactly the same stack.
     * The source is reduced before the menu is filled, so an exception can never duplicate items.
     */
    private void shiftInsert(InventoryClickEvent event, MenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Menu menu = holder.menu();
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getAction() != org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getRawSlot() < topSize || !menu.playerInventoryInput() || menu.inputSlots().isEmpty()
                || holder.isClosed()) {
            return;
        }
        Inventory source = event.getClickedInventory();
        int slot = event.getSlot();
        ItemStack clicked = event.getCurrentItem();
        if (source == null || clicked == null || clicked.isEmpty()) {
            return;
        }
        ItemStack snapshot = clicked.clone();
        scheduler.entity(player, () -> {
            Inventory top = holder.getInventory();
            if (holder.isClosed() || player.getOpenInventory().getTopInventory() != top) {
                return;
            }
            ItemStack current = source.getItem(slot);
            if (current == null || !current.equals(snapshot)) {
                return; // changed in the meantime: do nothing rather than guess
            }
            // The menu may have been re-rendered since the click (e.g. input slots locked during a save).
            Menu live = holder.menu();
            if (!live.playerInventoryInput() || live.inputSlots().isEmpty()) {
                return;
            }
            java.util.List<ShiftInsert.SlotState<ItemStack>> states = new java.util.ArrayList<>();
            for (int input : live.inputSlots()) {
                ItemStack item = top.getItem(input);
                boolean empty = item == null || item.isEmpty();
                states.add(new ShiftInsert.SlotState<>(input, empty ? null : item, empty ? 0 : item.getAmount(),
                        empty ? top.getMaxStackSize() : item.getMaxStackSize()));
            }
            ShiftInsert.Plan plan = ShiftInsert.plan(states, current, current.getAmount(), current.getMaxStackSize(),
                    (a, b) -> a != null && a.isSimilar(b));
            if (plan.puts().isEmpty()) {
                return;
            }
            if (plan.remainder() == 0) {
                source.setItem(slot, null);
            } else {
                ItemStack rest = current.clone();
                rest.setAmount(plan.remainder());
                source.setItem(slot, rest);
            }
            for (ShiftInsert.Put put : plan.puts()) {
                ItemStack placed;
                if (put.empty()) {
                    placed = current.clone();
                    placed.setAmount(put.amount());
                } else {
                    placed = top.getItem(put.slot()).clone();
                    placed.setAmount(placed.getAmount() + put.amount());
                }
                top.setItem(put.slot(), placed);
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDragFirst(InventoryDragEvent event) {
        guardDrag(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDragLast(InventoryDragEvent event) {
        guardDrag(event);
    }

    private void guardDrag(InventoryDragEvent event) {
        MenuHolder holder = ours(event.getView());
        if (holder == null) {
            return;
        }
        Menu menu = holder.menu();
        if (holder.isClosed() || !ClickPolicy.allowsDrag(event.getRawSlots(), event.getView().getTopInventory().getSize(),
                menu.inputSlots(), menu.playerInventoryInput())) {
            event.setCancelled(true);
        }
    }

    private boolean allowed(InventoryClickEvent event, MenuHolder holder) {
        if (holder.isClosed()) {
            return false;
        }
        Menu menu = holder.menu();
        return ClickPolicy.allowsClick(event.getRawSlot(), event.getView().getTopInventory().getSize(),
                event.getAction(), menu.inputSlots(), menu.playerInventoryInput());
    }

    private void dispatch(InventoryClickEvent event, MenuHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player) || holder.isClosed()) {
            return;
        }
        int raw = event.getRawSlot();
        Menu menu = holder.menu();
        if (raw < 0 || raw >= menu.size() || menu.inputSlots().contains(raw)) {
            return;
        }
        Button button = menu.button(raw);
        if (button == null) {
            return;
        }
        ClickHandler handler = button.handler(event.getClick());
        if (handler == null) {
            return;
        }
        if (!debounce.tryClick(player.getUniqueId(), theme().gui().clickDebounceMillis())) {
            return;
        }
        var type = event.getClick();
        scheduler.entity(player, () -> {
            if (holder.isClosed() || ours(player.getOpenInventory()) != holder || holder.menu() != menu) {
                return; // closed, replaced or re-rendered in the meantime: the click no longer applies
            }
            if (!button.isSilent()) {
                playClick(player);
            }
            try {
                handler.onClick(new MenuClick(player, type, raw, holder, scoped(holder.owner())));
            } catch (RuntimeException e) {
                logger.error("Menu click handler of module '{}' failed for {}", holder.owner(), player.getName(), e);
                playError(player);
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        MenuHolder holder = ours(event.getView());
        if (holder == null || !holder.markClosed()) {
            return;
        }
        Player player = event.getPlayer() instanceof Player p ? p : null;
        Menu menu = holder.menu();
        CloseHandler handler = menu.onClose();
        if (handler != null && player != null) {
            try {
                handler.onClose(player, holder.getInventory());
            } catch (RuntimeException e) {
                logger.error("Menu close handler of module '{}' failed", holder.owner(), e);
            }
        }
        if (player != null) {
            returnInputs(player, holder.getInventory(), menu.inputSlots());
        }
        if (event.getReason() != InventoryCloseEvent.Reason.OPEN_NEW && player != null) {
            Session session = sessions.get(player.getUniqueId());
            if (session != null && (session.current == holder || session.current == null)) {
                sessions.remove(player.getUniqueId());
            }
        }
    }

    /** Gives items left in input slots back to the player (dropping what does not fit). */
    private void returnInputs(Player player, Inventory inventory, Set<Integer> inputs) {
        for (int slot : inputs) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.isEmpty()) {
                continue;
            }
            inventory.setItem(slot, null);
            for (ItemStack left : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItem(player.getLocation(), left);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (ours(player.getOpenInventory()) != null) {
            player.closeInventory();
        }
        sessions.remove(player.getUniqueId());
        debounce.forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (ours(player.getOpenInventory()) != null) {
            player.closeInventory();
        }
    }

    // ---------------------------------------------------------------- module-scoped view

    /** Delegates to the manager but tags opened menus with a module id. */
    private final class Scoped implements MenuService {
        private final String owner;

        Scoped(String owner) {
            this.owner = owner;
        }

        @Override public void open(Player viewer, MenuProvider menu) { show(viewer, menu, owner, Mode.ROOT); }
        @Override public void push(Player viewer, MenuProvider menu) { show(viewer, menu, owner, Mode.PUSH); }
        @Override public void replace(Player viewer, MenuProvider menu) { show(viewer, menu, owner, Mode.REPLACE); }
        @Override public void refresh(Player viewer) { MenuManager.this.refresh(viewer); }
        @Override public boolean back(Player viewer) { return MenuManager.this.back(viewer); }
        @Override public void close(Player viewer) { MenuManager.this.close(viewer); }
        @Override public boolean isViewing(Player viewer) { return MenuManager.this.isViewing(viewer); }
        @Override public boolean hasHistory(Player viewer) { return MenuManager.this.hasHistory(viewer); }
        @Override public Component text(Player viewer, String key, TagResolver... resolvers) {
            return MenuManager.this.text(viewer, key, resolvers);
        }
        @Override public ItemStack filler() { return MenuManager.this.filler(); }
        @Override public void playClick(Player viewer) { MenuManager.this.playClick(viewer); }
        @Override public void playError(Player viewer) { MenuManager.this.playError(viewer); }
        @Override public Theme theme() { return MenuManager.this.theme(); }

        @Override
        public void confirm(Player viewer, Component title, List<Component> details, ClickHandler onConfirm,
                @Nullable ClickHandler onCancel) {
            push(viewer, ConfirmMenu.of(this, title, details, onConfirm, onCancel));
        }
    }

    /** Test/diagnostic helper: number of players with menu state. */
    int sessionCount() {
        return sessions.size();
    }
}
