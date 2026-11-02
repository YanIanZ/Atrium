package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.MenuHolder;
import dev.yanianz.atrium.core.menu.MenuProvider;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The admin kit editor, a {@code ctx.menus()} menu. Kit items are shown as locked copies (buttons) that can never
 * leave the editor; every other content slot is a menu input slot where the admin places items from their own
 * inventory. Those borrowed items are either stored in the kit (Save) or returned (Cancel, closing the menu,
 * disconnecting, module shutdown).
 *
 * <p>Item accounting: while a menu is shown, the borrowed items live in its input slots ({@link Session#live}).
 * Whenever that menu goes away (re-render with another shape, chat input, save, close), {@link #harvest} moves
 * them back into the session and empties the slots, so the menu engine's own return-on-close never hands them
 * out a second time. Every exit path settles the session exactly once ({@link Session#finished}).
 *
 * <p>Only one editor can be open per kit ({@link KitEditLocks}) and a save is an optimistic, versioned update:
 * when the kit changed since the editor opened, nothing is written and the borrowed items go back to the admin.
 * While a save runs the menu is shown without input slots, so nothing can be moved in or out mid-write. Items
 * that cannot be handed back right now (admin offline, server stopping) are persisted in {@code kits_returns}
 * and delivered on the next join.
 */
final class KitEditor implements Listener {
    static final int ROWS = 6;
    static final int CONTENT = KitItems.SIZE;
    static final int SAVE = 45;
    static final int CANCEL = 46;
    static final int COOLDOWN = 47;
    static final int COST = 48;
    static final int PERMISSION = 49;
    static final int FIRST_JOIN = 50;
    static final int ONE_TIME = 51;
    static final int MAX_USES = 52;
    static final int ICON = 53;
    private static final int MAX_USES_LIMIT = 1_000_000;

    private enum Input { COOLDOWN, COST, PERMISSION, MAX_USES }

    private final ModuleContext ctx;
    private final KitService kits;
    private final Consumer<Kit> permissionRegistrar;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final KitEditLocks<Session> locks = new KitEditLocks<>();
    /** Writes of owed items still running; shutdown waits for them before the database closes. */
    private final Set<CompletableFuture<?>> returnWrites = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    private static final class Session {
        final Player admin;
        final String kitName;
        /** Row version of the kit when the editor opened; the save only applies while it is unchanged. */
        final long version;
        final ItemStack[] content = new ItemStack[CONTENT];
        final KitEditModel model = new KitEditModel(CONTENT);
        @Nullable ItemStack icon;
        long cooldownSeconds;
        BigDecimal cost;
        String permission;
        boolean firstJoin;
        boolean oneTime;
        int maxUses;
        /** The menu this session shows; assigned by the editor right after construction. */
        MenuProvider provider;
        /** The last menu rendered for this session (it may not be open). */
        @Nullable Menu rendered;
        /** Inventory of the last menu that was actually shown. */
        @Nullable Inventory shown;
        /** Inventory whose input slots currently hold the borrowed items, or null when the session holds them. */
        @Nullable Inventory live;
        Set<Integer> liveInputs = Set.of();
        /** True while the editor itself replaces its menu: the old menu's close is not a cancel. */
        boolean transitioning;
        volatile @Nullable Input input;
        volatile boolean awaitingInput;
        volatile boolean saving;
        volatile boolean closing;
        volatile boolean closedWhileSaving;
        /** The running versioned update, set while {@link #saving}. */
        volatile @Nullable CompletableFuture<Optional<Kit>> write;
        /** Completes after the save callback ran (it may have handed the outcome to the admin's thread). */
        volatile @Nullable CompletableFuture<?> afterWrite;
        /** Exactly one party (save callback or shutdown) handles a save outcome. */
        final AtomicBoolean settled = new AtomicBoolean();
        /** Borrowed items are given back, persisted or consumed exactly once. */
        final AtomicBoolean finished = new AtomicBoolean();

        Session(Player admin, Kit kit) {
            this.admin = admin;
            this.kitName = kit.name();
            this.version = kit.version();
            this.cooldownSeconds = kit.cooldownSeconds();
            this.cost = kit.cost();
            this.permission = kit.permission();
            this.firstJoin = kit.firstJoin();
            this.oneTime = kit.oneTime();
            this.maxUses = kit.maxUses();
            ItemStack[] items = KitItems.deserialize(kit.items());
            for (int i = 0; i < CONTENT; i++) {
                if (items[i] == null || items[i].isEmpty()) continue;
                content[i] = items[i].clone();
                model.markKit(i);
            }
            icon = KitItems.deserializeIcon(kit.icon());
        }
    }

    KitEditor(ModuleContext ctx, KitService kits, Consumer<Kit> permissionRegistrar) {
        this.ctx = ctx;
        this.kits = kits;
        this.permissionRegistrar = permissionRegistrar;
    }

    // ---- opening ----

    void open(Player admin, Kit requested) {
        ctx.scheduler().entity(admin, () -> {
            if (stopping) return;
            // The newest stored definition (and version), not the one seen when the command was typed.
            Kit kit = kits.kit(requested.name()).orElse(null);
            if (kit == null) {
                ctx.messages().send(admin, "kits.unknown", Msg.unparsed("kit", requested.name()));
                return;
            }
            Session previous = sessions.get(admin.getUniqueId());
            if (previous != null) {
                if (previous.saving) {
                    message(previous, "busy");
                    return;
                }
                sessions.remove(admin.getUniqueId(), previous);
                cancelInternal(previous, false);
            }
            Session session;
            try {
                session = new Session(admin, kit);
            } catch (RuntimeException e) {
                ctx.logger().error("Cannot open the editor for kit {}", kit.name(), e);
                ctx.messages().send(admin, "kits.failed");
                return;
            }
            session.provider = viewer -> render(session);
            Optional<Session> holder = locks.tryAcquire(kit.name(), session);
            if (holder.isPresent()) {
                ctx.messages().send(admin, "kits.editor-in-use", Msg.unparsed("kit", kit.name()),
                        Msg.unparsed("player", holder.get().admin.getName()));
                return;
            }
            boolean shown;
            try {
                shown = show(session, true);
            } catch (RuntimeException e) {
                ctx.logger().error("Cannot open the editor for kit {}", kit.name(), e);
                shown = false;
            }
            if (!shown) {
                // Nothing was borrowed yet; this only frees the lock.
                cancelInternal(session, false);
                ctx.messages().send(admin, "kits.failed");
                return;
            }
            Session raced = sessions.put(admin.getUniqueId(), session);
            if (raced != null && raced != session) cancelInternal(raced, false);
        }, null);
    }

    // ---- showing ----

    /**
     * Shows (or re-renders) the session's menu; admin's thread only. A re-render with the same input slots updates
     * in place; otherwise the old menu closes (its borrowed items are harvested) and the new one is filled from
     * the session. Returns false when the menu is not open afterwards (refused by another plugin).
     */
    private boolean show(Session s, boolean root) {
        s.transitioning = true;
        try {
            if (root) {
                ctx.menus().open(s.admin, s.provider);
            } else {
                ctx.menus().replace(s.admin, s.provider);
            }
        } finally {
            s.transitioning = false;
        }
        MenuHolder holder = MenuHolder.of(s.admin.getOpenInventory());
        Menu menu = s.rendered;
        if (holder == null || menu == null || holder.menu() != menu) {
            return false;
        }
        Inventory inventory = holder.getInventory();
        if (s.live != inventory) {
            harvest(s); // defensive: a previous menu that was not harvested on close
            for (int slot : menu.inputSlots()) {
                if (slot < CONTENT && s.model.isBorrowed(slot) && s.content[slot] != null) {
                    inventory.setItem(slot, s.content[slot].clone());
                }
            }
        }
        s.live = inventory;
        s.liveInputs = Set.copyOf(menu.inputSlots());
        s.shown = inventory;
        return true;
    }

    /**
     * Takes the borrowed items out of the live menu's input slots into the session and empties those slots. Safe to
     * call repeatedly; afterwards the session alone holds the borrowed items.
     */
    private void harvest(Session s) {
        Inventory inventory = s.live;
        if (inventory == null) return;
        for (int slot : s.liveInputs) {
            if (slot >= CONTENT) continue;
            ItemStack item = inventory.getItem(slot);
            boolean present = item != null && !item.isEmpty();
            s.content[slot] = present ? item.clone() : null;
            s.model.harvested(slot, present);
            if (present) inventory.setItem(slot, null);
        }
        s.live = null;
        s.liveInputs = Set.of();
    }

    private boolean isShowing(Session s) {
        MenuHolder holder = MenuHolder.of(s.admin.getOpenInventory());
        return holder != null && s.shown != null && holder.getInventory() == s.shown;
    }

    // ---- rendering ----

    private Menu render(Session s) {
        Player admin = s.admin;
        boolean locked = s.saving;
        Menu.Builder menu = Menu.builder(ctx.messages().render(admin, "kits.editor-title",
                Msg.unparsed("kit", s.kitName)), ROWS);
        for (int slot = 0; slot < CONTENT; slot++) {
            ItemStack item = s.content[slot];
            if (s.model.origin(slot) == KitEditModel.Origin.KIT && item != null) {
                int target = slot;
                menu.button(slot, locked ? Button.of(item) : Button.of(item, click -> active(s, () -> kitCopy(s, target, click))));
            } else if (locked) {
                if (item != null) menu.item(slot, item);
            } else {
                menu.input(slot);
            }
        }
        menu.playerInventoryInput(true);
        menu.item(SAVE, ItemBuilder.of(Material.EMERALD_BLOCK).name(text(admin, "editor-save"))
                .lore(text(admin, locked ? "editor-saving" : "editor-save-lore")).build(),
                click -> active(s, () -> save(s)));
        menu.item(CANCEL, ItemBuilder.of(Material.BARRIER).name(text(admin, "editor-cancel"))
                .lore(text(admin, "editor-cancel-lore")).build(),
                click -> active(s, () -> cancel(s, true)));
        menu.button(COOLDOWN, valueButton(s, Material.CLOCK, "editor-cooldown",
                Durations.format(Duration.ofSeconds(s.cooldownSeconds), 3), "editor-value-hint", Input.COOLDOWN,
                () -> s.cooldownSeconds = 0));
        menu.button(COST, valueButton(s, Material.GOLD_INGOT, "editor-cost", costText(s.cost), "editor-value-hint",
                Input.COST, () -> s.cost = BigDecimal.ZERO));
        menu.button(PERMISSION, valueButton(s, Material.NAME_TAG, "editor-permission", s.permission,
                "editor-permission-hint", Input.PERMISSION, () -> s.permission = defaultPermission(s.kitName)));
        menu.button(FIRST_JOIN, toggleButton(s, Material.IRON_DOOR, "editor-firstjoin", s.firstJoin,
                () -> s.firstJoin = !s.firstJoin));
        menu.button(ONE_TIME, toggleButton(s, Material.ENDER_EYE, "editor-onetime", s.oneTime,
                () -> s.oneTime = !s.oneTime));
        menu.button(MAX_USES, valueButton(s, Material.EXPERIENCE_BOTTLE, "editor-maxuses",
                s.maxUses < 0 ? unlimitedText(admin) : Integer.toString(s.maxUses), "editor-maxuses-hint",
                Input.MAX_USES, () -> s.maxUses = -1));
        menu.item(ICON, iconItem(s), click -> active(s, () -> icon(s)));
        menu.onClose((viewer, inventory) -> closed(s, inventory));
        Menu built = menu.build();
        s.rendered = built;
        return built;
    }

    /** Left-click asks for a value in chat, right-click resets it. */
    private Button valueButton(Session s, Material material, String nameKey, String value, String hintKey,
            Input input, Runnable reset) {
        ItemStack icon = ItemBuilder.of(material).name(text(s.admin, nameKey, Msg.unparsed("value", value)))
                .lore(text(s.admin, hintKey)).hideDetails().build();
        return Button.of(icon, click -> active(s, () -> {
            if (click.isRight()) {
                reset.run();
                reshow(s);
            } else {
                requestInput(s, input);
            }
        }));
    }

    private Button toggleButton(Session s, Material material, String nameKey, boolean value, Runnable toggle) {
        Component state = text(s.admin, value ? "editor-on" : "editor-off");
        ItemStack icon = ItemBuilder.of(material).name(text(s.admin, nameKey, Msg.component("state", state)))
                .lore(text(s.admin, "editor-toggle-hint")).hideDetails().build();
        return Button.of(icon, click -> active(s, () -> {
            toggle.run();
            reshow(s);
        }));
    }

    private ItemStack iconItem(Session s) {
        Player admin = s.admin;
        if (s.icon == null) {
            return ItemBuilder.of(Material.ITEM_FRAME).name(text(admin, "editor-icon"))
                    .lore(text(admin, "editor-icon-empty")).build();
        }
        List<Component> lore = new ArrayList<>();
        List<Component> existing = s.icon.lore();
        if (existing != null) lore.addAll(existing);
        lore.add(text(admin, "editor-icon-lore"));
        return ItemBuilder.from(s.icon).lore(lore).build();
    }

    private Component text(Player admin, String key, TagResolver... tags) {
        return ctx.messages().render(admin, "kits." + key, tags);
    }

    private String costText(BigDecimal value) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        return economy != null ? economy.format(value) : value.toPlainString();
    }

    private String unlimitedText(Player admin) {
        return PlainTextComponentSerializer.plainText().serialize(ctx.messages().render(admin, "kits.editor-unlimited"));
    }

    // ---- clicks (menu handlers run on the admin's thread, one tick after the cancelled click) ----

    /** Runs a click action only while the session is the admin's live, idle editor. */
    private void active(Session s, Runnable action) {
        if (stopping || sessions.get(s.admin.getUniqueId()) != s || s.closing || s.saving || s.awaitingInput) return;
        action.run();
    }

    /**
     * A click on a locked kit copy. With an item on the cursor the copy is replaced by that item (the copy is
     * discarded, never handed out); a shift-click removes the copy from the kit; a plain click explains why the
     * copy cannot be taken.
     */
    private void kitCopy(Session s, int slot, MenuClick click) {
        Player admin = s.admin;
        ItemStack cursor = admin.getItemOnCursor();
        if (!cursor.isEmpty()) {
            s.content[slot] = cursor.clone();
            s.model.place(slot, true);
            admin.setItemOnCursor(null);
            reshow(s);
            return;
        }
        if (click.isShift()) {
            s.content[slot] = null;
            s.model.remove(slot);
            reshow(s);
            return;
        }
        click.error();
        message(s, "editor-copy");
    }

    /** The icon slot: click with an item to use it as the icon, click with an empty cursor to clear it. */
    private void icon(Session s) {
        Player admin = s.admin;
        ItemStack cursor = admin.getItemOnCursor();
        if (!cursor.isEmpty()) {
            if (s.icon != null && s.model.iconBorrowed()) returnToAdmin(s, s.icon);
            s.icon = cursor.clone();
            s.model.setIcon(true);
            admin.setItemOnCursor(null);
            reshow(s);
            return;
        }
        if (s.icon == null) return;
        if (s.model.iconBorrowed()) returnToAdmin(s, s.icon);
        s.icon = null;
        s.model.clearIcon();
        reshow(s);
    }

    /** Re-renders after a change; a menu that cannot be shown any more ends the session with its items returned. */
    private void reshow(Session s) {
        if (!show(s, false)) cancelInternal(s, true);
    }

    // ---- value input ----

    private void requestInput(Session s, Input input) {
        s.input = input;
        s.awaitingInput = true;
        // The close harvests the borrowed items into the session; they come back when the menu reopens.
        ctx.menus().close(s.admin);
        String key = switch (input) {
            case COOLDOWN -> "editor-prompt-cooldown";
            case COST -> "editor-prompt-cost";
            case PERMISSION -> "editor-prompt-permission";
            case MAX_USES -> "editor-prompt-maxuses";
        };
        String current = switch (input) {
            case COOLDOWN -> Durations.format(Duration.ofSeconds(s.cooldownSeconds), 3);
            case COST -> costText(s.cost);
            case PERMISSION -> s.permission;
            case MAX_USES -> s.maxUses < 0 ? unlimitedText(s.admin) : Integer.toString(s.maxUses);
        };
        message(s, key, Msg.unparsed("value", current));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || !s.awaitingInput || s.input == null) return;
        Input input = s.input;
        s.awaitingInput = false;
        s.input = null;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        ctx.scheduler().entity(s.admin, () -> applyInput(s, input, text), null);
    }

    private void applyInput(Session s, Input input, String text) {
        if (s.closing || s.saving || !s.admin.isOnline() || sessions.get(s.admin.getUniqueId()) != s) return;
        if (text.equalsIgnoreCase("cancel") || text.equalsIgnoreCase("batal")) {
            message(s, "editor-input-cancelled");
            reopen(s);
            return;
        }
        switch (input) {
            case COOLDOWN -> {
                var parsed = Durations.parse(text);
                if (parsed.isEmpty()) {
                    message(s, "editor-invalid-cooldown");
                } else {
                    s.cooldownSeconds = parsed.get().toSeconds();
                }
            }
            case COST -> {
                try {
                    BigDecimal value = new BigDecimal(text);
                    if (value.signum() < 0) throw new NumberFormatException("negative");
                    s.cost = value.stripTrailingZeros();
                } catch (NumberFormatException e) {
                    message(s, "editor-invalid-cost");
                }
            }
            case PERMISSION -> {
                if (text.equalsIgnoreCase("default") || text.equalsIgnoreCase("reset")) {
                    s.permission = defaultPermission(s.kitName);
                } else if (Kit.validPermission(text)) {
                    s.permission = text.toLowerCase(Locale.ROOT);
                } else {
                    message(s, "editor-invalid-permission");
                }
            }
            case MAX_USES -> {
                try {
                    int value = Integer.parseInt(text);
                    if (value < -1 || value > MAX_USES_LIMIT) throw new NumberFormatException("out of range");
                    s.maxUses = value;
                } catch (NumberFormatException e) {
                    message(s, "editor-invalid-maxuses");
                }
            }
        }
        reopen(s);
    }

    private void reopen(Session s) {
        if (!s.admin.isOnline() || sessions.get(s.admin.getUniqueId()) != s) return;
        if (!show(s, true)) cancelInternal(s, true);
    }

    private String defaultPermission(String kitName) {
        return "atrium.kit." + kitName;
    }

    // ---- save / cancel ----

    private void save(Session s) {
        if (s.saving || s.closing || stopping) return;
        // Take the borrowed items out of the menu first: what is saved is exactly what the session holds.
        harvest(s);
        ItemStack[] items = new ItemStack[CONTENT];
        for (int i = 0; i < CONTENT; i++) items[i] = s.content[i] == null ? null : s.content[i].clone();
        Kit updated;
        try {
            updated = new Kit(s.kitName, s.cooldownSeconds, s.oneTime, s.firstJoin, s.cost, KitItems.serialize(items),
                    KitItems.serializeIcon(s.icon), s.permission, s.maxUses, System.currentTimeMillis(), s.version);
        } catch (RuntimeException e) {
            ctx.logger().error("Cannot build kit {} from the editor", s.kitName, e);
            message(s, "failed");
            reshow(s);
            return;
        }
        s.saving = true;
        // Locked view: no input slots while the write runs, so nothing can be moved in or out mid-save.
        if (!show(s, false)) s.closedWhileSaving = true;
        CompletableFuture<Optional<Kit>> write = kits.store().updateKit(updated);
        s.write = write;
        s.afterWrite = write.whenComplete((stored, failure) -> {
            // While stopping, shutdown() waits for the write and settles it on the stopping thread itself.
            if (stopping || !s.settled.compareAndSet(false, true)) return;
            onAdmin(s, () -> settleSave(s, stored, failure, true), () -> settleSave(s, stored, failure, false));
        });
    }

    /**
     * Applies the outcome of an editor save. {@code onAdminThread} = we run on the admin's thread (or the server is
     * stopping on the thread that owns everything); otherwise the admin is gone and owed items are persisted.
     */
    private void settleSave(Session s, @Nullable Optional<Kit> stored, @Nullable Throwable failure,
            boolean onAdminThread) {
        if (failure == null && stored != null && stored.isPresent()) {
            Kit saved = stored.get();
            s.model.commit();
            kits.put(saved);
            registerPermission(saved);
            if (onAdminThread) {
                finishSave(s);
            } else {
                s.finished.set(true);
                end(s);
            }
            return;
        }
        s.saving = false;
        if (failure == null) {
            // Version mismatch: the kit was changed or deleted since the editor opened. Nothing was written.
            ctx.logger().info("Kit {} changed while {} was editing it; editor save refused", s.kitName, s.admin.getName());
            kits.refresh(s.kitName);
            if (onAdminThread && s.admin.isOnline()) {
                cancelInternal(s, false);
                message(s, "editor-conflict", Msg.unparsed("kit", s.kitName));
            } else {
                storePending(s);
            }
            return;
        }
        ctx.logger().error("Could not save kit {} from the editor", s.kitName, failure);
        if (!onAdminThread || !s.admin.isOnline()) {
            storePending(s);
        } else if (s.closedWhileSaving || stopping) {
            cancelInternal(s, true);
        } else {
            message(s, "failed");
            reshow(s);
        }
    }

    private void registerPermission(Kit kit) {
        try {
            ctx.scheduler().global(() -> permissionRegistrar.accept(kit));
        } catch (RuntimeException e) {
            // Plugin already disabled (server stopping): permissions are registered again on the next enable.
            ctx.logger().debug("Skipped permission registration of kit {}: {}", kit.name(), e.toString());
        }
    }

    /**
     * Runs {@code task} on the admin's thread, or {@code retired} when that is impossible (admin gone, or the plugin
     * is already disabled so nothing can be scheduled any more).
     */
    private void onAdmin(Session s, Runnable task, Runnable retired) {
        if (Bukkit.isOwnedByCurrentRegion(s.admin)) {
            task.run();
            return;
        }
        try {
            ctx.scheduler().entity(s.admin, task, retired);
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot schedule kit editor work for {} ({}); persisting owed items instead",
                    s.admin.getName(), e.toString());
            retired.run();
        }
    }

    private void finishSave(Session s) {
        s.closing = true;
        s.finished.set(true);
        end(s);
        harvest(s); // the locked view has no input slots; this only forgets it
        if (s.admin.isOnline()) {
            if (isShowing(s)) s.admin.closeInventory();
            message(s, "editor-saved", Msg.unparsed("kit", s.kitName));
        }
    }

    private void cancel(Session s, boolean notify) {
        if (s.saving || s.closing) return;
        cancelInternal(s, notify);
    }

    /** Cancels a session; must run on the admin's thread. */
    private void cancelInternal(Session s, boolean notify) {
        s.closing = true;
        // Empty the input slots first, so the menu's own return-on-close cannot hand the items out a second time.
        harvest(s);
        if (s.admin.isOnline() && isShowing(s)) {
            s.admin.closeInventory();
        }
        finishCancel(s, notify);
    }

    /** Ends a session without saving and hands back every borrowed item exactly once; admin's thread only. */
    private void finishCancel(Session s, boolean notify) {
        s.closing = true;
        if (!s.finished.compareAndSet(false, true)) {
            end(s);
            return;
        }
        harvest(s);
        List<ItemStack> returned = borrows(s);
        end(s);
        if (s.admin.isOnline()) {
            for (ItemStack item : returned) giveBack(s.admin, item);
            if (notify) message(s, "editor-cancelled", Msg.unparsed("kit", s.kitName));
        } else {
            persistReturns(s.admin.getUniqueId(), s.admin.getName(), returned);
        }
    }

    /** Forgets a session and frees its kit lock. */
    private void end(Session s) {
        sessions.remove(s.admin.getUniqueId(), s);
        locks.release(s.kitName, s);
    }

    /** The admin that holds the editor lock of a kit, if any. */
    Optional<Player> editing(String kitName) {
        return locks.holder(kitName).map(session -> session.admin);
    }

    private List<ItemStack> borrows(Session s) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < CONTENT; i++) {
            if (s.model.isBorrowed(i) && s.content[i] != null) items.add(s.content[i].clone());
        }
        if (s.model.iconBorrowed() && s.icon != null) items.add(s.icon.clone());
        return items;
    }

    /** Ends a session whose admin cannot be reached now; borrowed items are persisted for the next join. */
    private void storePending(Session s) {
        s.closing = true;
        // Last resort (admin gone or plugin stopping): the menu, if any, is no longer used by anyone.
        harvest(s);
        if (!s.finished.compareAndSet(false, true)) {
            end(s);
            return;
        }
        List<ItemStack> items = borrows(s);
        end(s);
        persistReturns(s.admin.getUniqueId(), s.admin.getName(), items);
    }

    /** Stores owed items in the database; they are given back on the player's next join. */
    private void persistReturns(UUID uuid, String name, List<ItemStack> items) {
        if (items.isEmpty()) return;
        byte[] blob;
        try {
            blob = ItemStack.serializeItemsAsBytes(items);
        } catch (RuntimeException e) {
            ctx.logger().error("ITEMS LOST: cannot serialize {} kit editor item stack(s) owed to {}: {}", items.size(),
                    name, items, e);
            return;
        }
        persistBlob(uuid, name, blob, items.size(), true);
    }

    /**
     * @param stacks    number of stacks for the log, -1 when unknown
     * @param redeliver try to deliver right away when the player is online (false for blobs that just failed)
     */
    private void persistBlob(UUID uuid, String name, byte[] blob, int stacks, boolean redeliver) {
        CompletableFuture<Void> write;
        try {
            write = kits.store().addReturn(uuid, blob);
        } catch (RuntimeException e) {
            ctx.logger().error("ITEMS LOST: cannot store {} kit editor item stack(s) owed to {} ({})", stacks, name,
                    Base64.getEncoder().encodeToString(blob), e);
            return;
        }
        returnWrites.add(write);
        write.whenComplete((unused, failure) -> {
            returnWrites.remove(write);
            if (failure != null) {
                ctx.logger().error("ITEMS LOST: could not store {} kit editor item stack(s) owed to {} ({})", stacks,
                        name, Base64.getEncoder().encodeToString(blob), failure);
                return;
            }
            if (stacks >= 0) {
                ctx.logger().warn("Kit editor of {} closed without the admin present; {} item stack(s) will be"
                        + " returned on the next join", name, stacks);
            }
            if (stopping || !redeliver) return;
            Player online = Bukkit.getPlayer(uuid);
            if (online != null) deliver(online);
        });
    }

    /** Takes every persisted return of a player out of the database and gives it on the player's thread. */
    private void deliver(Player player) {
        UUID uuid = player.getUniqueId();
        kits.store().takeReturns(uuid).whenComplete((blobs, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not load kit editor returns of {}", player.getName(), failure);
                return;
            }
            if (blobs.isEmpty()) return;
            Runnable keep = () -> {
                for (byte[] blob : blobs) persistBlob(uuid, player.getName(), blob, -1, false);
            };
            try {
                ctx.scheduler().entity(player, () -> giveReturns(player, blobs), keep);
            } catch (RuntimeException e) {
                keep.run();
            }
        });
    }

    private void giveReturns(Player player, List<byte[]> blobs) {
        if (!player.isOnline()) {
            for (byte[] blob : blobs) persistBlob(player.getUniqueId(), player.getName(), blob, -1, false);
            return;
        }
        int count = 0;
        for (byte[] blob : blobs) {
            ItemStack[] items;
            try {
                items = ItemStack.deserializeItemsFromBytes(blob);
            } catch (RuntimeException e) {
                ctx.logger().error("Cannot read kit editor returns of {}; kept for an admin to inspect",
                        player.getName(), e);
                persistBlob(player.getUniqueId(), player.getName(), blob, -1, false);
                continue;
            }
            for (ItemStack item : items) {
                if (item == null || item.isEmpty()) continue;
                giveBack(player, item);
                count++;
            }
        }
        if (count > 0) ctx.messages().send(player, "kits.editor-returned", Msg.number("count", count));
    }

    private void returnToAdmin(Session s, ItemStack item) {
        if (s.admin.isOnline()) giveBack(s.admin, item);
    }

    private void giveBack(Player player, ItemStack item) {
        var leftover = player.getInventory().addItem(item.clone());
        if (leftover.isEmpty()) return;
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItem(player.getLocation(), rest, dropped -> dropped.setOwner(player.getUniqueId()));
        }
        messageRaw(player, "kits.editor-returned-dropped");
    }

    private void message(Session s, String key, TagResolver... tags) {
        if (s.admin.isOnline()) ctx.messages().send(s.admin, "kits." + key, tags);
    }

    private void messageRaw(Player player, String key, TagResolver... tags) {
        ctx.messages().send(player, key, tags);
    }

    // ---- lifecycle ----

    /** Close handler of every editor menu (admin's thread). Runs before the menu returns its input slots. */
    private void closed(Session s, Inventory inventory) {
        if (s.live == inventory) harvest(s);
        if (s.transitioning || s.awaitingInput || s.finished.get()) return;
        if (s.saving) {
            s.closedWhileSaving = true;
            return;
        }
        if (s.closing) {
            end(s);
            return;
        }
        cancelInternal(s, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) return;
        if (s.saving) {
            s.closedWhileSaving = true;
            return;
        }
        cancelInternal(s, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!stopping) deliver(event.getPlayer());
    }

    /**
     * Module disable / server stop. The plugin may already be disabled, so nothing can be scheduled: a session whose
     * admin is owned by this thread (Folia's shutdown thread owns every entity, Paper's main thread too) is settled
     * right here; any other session's borrowed items are persisted. Waits for in-flight saves and for the writes of
     * owed items so they are stored before the database closes.
     */
    void shutdown() {
        stopping = true;
        for (Session s : List.copyOf(sessions.values())) {
            try {
                shutdownSession(s);
            } catch (RuntimeException e) {
                ctx.logger().error("Kit editor of {} could not be closed cleanly; persisting its items", s.admin.getName(), e);
                storePending(s);
            }
        }
        CompletableFuture<?>[] writes = returnWrites.toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(writes).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            ctx.logger().error("Not every owed kit editor item could be stored before shutdown (see errors above)", e);
        }
        locks.clear();
    }

    private void shutdownSession(Session s) {
        CompletableFuture<Optional<Kit>> write = s.write;
        if (s.saving && write != null) {
            Optional<Kit> stored = null;
            Throwable failure = null;
            try {
                stored = write.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failure = e.getCause();
            } catch (TimeoutException e) {
                // Unknown outcome: returning the items could duplicate them if the write still lands.
                ctx.logger().error("Kit {} save by {} still running at shutdown; its {} borrowed item stack(s) are in"
                        + " the kit if the save completes", s.kitName, s.admin.getName(), borrows(s).size());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!s.settled.compareAndSet(false, true)) {
                // The save callback won the race before stopping; wait for it to hand off its work.
                CompletableFuture<?> after = s.afterWrite;
                if (after != null) after.completeOnTimeout(null, 5, TimeUnit.SECONDS).join();
                return;
            }
            boolean owned = Bukkit.isOwnedByCurrentRegion(s.admin);
            settleSave(s, stored, failure, owned);
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(s.admin)) {
            cancelInternal(s, false);
        } else {
            onAdmin(s, () -> cancelInternal(s, false), () -> storePending(s));
        }
    }
}
