package dev.yanianz.atrium.module.player;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.inventories.InventorySnapshotService;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * /invsee, /enderchest (aliases /endersee and /ec). Online targets use this live listener: display-only copies
 * whose edits exchange the current live slot on the target's thread, never write back a snapshot. It stays a
 * dedicated listener instead of a {@code ctx.menus()} menu because the menu toolkit cannot express clicks in
 * the viewer's own inventory or the cross-thread live exchange. Offline targets open
 * {@link OfflineInventoryMenu} from the newest QUIT snapshot instead.
 */
final class InventoryViewer implements Listener, TaskHandle {
    /** First display slot after the live player slots (0-35 storage, 36-39 armor, 40 off hand). */
    private static final int DECOR_FIRST = 41;
    private static final int INFO_SLOT = 44;
    private final ModuleContext ctx;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    private static final class Session implements InventoryHolder {
        final Player viewer;
        final Player target;
        final boolean ender;
        final AtomicBoolean busy = new AtomicBoolean();
        volatile boolean closed;
        volatile TaskHandle refresh;
        Inventory display;
        ItemStack info; // themed label in the unused bottom-right slot of an inventory view
        Session(Player viewer, Player target, boolean ender) {
            this.viewer = viewer; this.target = target; this.ender = ender;
        }
        @Override public Inventory getInventory() { return display; }
        Inventory live() { return ender ? target.getEnderChest() : target.getInventory(); }
        String modifyPermission() { return ender ? "atrium.enderchest.others.modify" : "atrium.invsee.modify"; }
        String basePermission() { return ender ? "atrium.enderchest" : "atrium.invsee"; }
        void stop() {
            closed = true;
            TaskHandle task = refresh;
            if (task != null) task.cancel();
        }
    }

    InventoryViewer(ModuleContext ctx) { this.ctx = ctx; }

    /** Aliases of /enderchest. Kept in one place so the command and its regression test agree. */
    static List<String> enderAliases() {
        return List.of("endersee", "ec");
    }

    void register() {
        ctx.listen(this);
        ctx.track(this);
        ctx.command(CommandSpec.of(Cmds.literal("enderchest", "atrium.enderchest")
                .executes(this::openOwnEnder)
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> open(c, true)))
                .build(), CommandDescriptions.of("enderchest"), enderAliases().toArray(String[]::new)));
        ctx.command(CommandSpec.of(Cmds.literal("invsee", "atrium.invsee")
                .executes(c -> { throw ctx.commands().fail(c.getSource(), "player.usage.invsee"); })
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> open(c, false)))
                .build(), CommandDescriptions.of("invsee")));
    }

    private int openOwnEnder(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player viewer = ctx.commands().player(c.getSource());
        ctx.scheduler().entity(viewer, () -> {
            if (cancelled) return;
            viewer.openInventory(viewer.getEnderChest());
        }, null);
        return Cmds.OK;
    }

    /**
     * Resolves the target account (online or offline). Offline targets open the newest QUIT snapshot through
     * {@link InventorySnapshotService}; online targets keep the live viewer.
     */
    private int open(CommandContext<CommandSourceStack> c, boolean ender) throws CommandSyntaxException {
        Player viewer = ctx.commands().player(c.getSource());
        String name = StringArgumentType.getString(c, "target");
        String base = "atrium." + (ender ? "enderchest" : "invsee");
        ctx.users().resolveAccount(name, false).whenComplete((found, error) -> {
            if (cancelled) return;
            if (error != null) {
                ctx.logger().warn("Resolving {} for /{} failed", name, ender ? "enderchest" : "invsee", error);
                message(viewer, "failed");
                return;
            }
            if (found == null || found.isEmpty()) {
                ctx.scheduler().entity(viewer, () -> {
                    if (!cancelled) {
                        ctx.messages().send(viewer, "core.user-not-found", Msg.unparsed("name", name));
                    }
                }, null);
                return;
            }
            PlayerRef ref = found.get();
            if (!viewer.getUniqueId().equals(ref.uuid()) && !viewer.hasPermission(base + ".others")) {
                ctx.scheduler().entity(viewer, () -> {
                    if (!cancelled) {
                        ctx.messages().send(viewer, "core.no-permission-others",
                                Msg.unparsed("permission", base + ".others"));
                    }
                }, null);
                return;
            }
            Player online = Bukkit.getPlayer(ref.uuid());
            ctx.scheduler().entity(viewer, () -> {
                if (cancelled || !viewer.isOnline()) return;
                if (online != null) {
                    openLive(viewer, online, ender);
                } else {
                    openOffline(viewer, ref, ender);
                }
            }, null);
        });
        return Cmds.OK;
    }

    /** Must run on the viewer's thread. */
    private void openLive(Player viewer, Player target, boolean ender) {
        if (cancelled) return;
        if (ender && viewer.equals(target)) {
            viewer.openInventory(viewer.getEnderChest());
            return;
        }
        var session = new Session(viewer, target, ender);
        session.display = Bukkit.createInventory(session, ender ? 27 : 45,
                ctx.messages().render(viewer, "player.inventory-title", Msg.unparsed("player", target.getName())));
        if (!ender) {
            session.info = ItemBuilder.of(Material.BOOK)
                    .name(ctx.messages().render(viewer, "player.inventory-info-name",
                            Msg.unparsed("player", target.getName())))
                    .lore(ctx.messages().render(viewer, "player.inventory-info-lore"))
                    .build();
            decorate(session);
        }
        viewer.openInventory(session.display);
        if (viewer.getOpenInventory().getTopInventory() != session.display) return;
        Session previous = sessions.put(viewer.getUniqueId(), session);
        if (previous != null) previous.stop();
        ctx.messages().send(viewer, "player.inventory-help");
        refresh(session);
    }

    /**
     * Opens the newest QUIT snapshot of an offline target. The edit permission is checked here; the service
     * refuses a player who joined in the meantime, so an online player is always edited live.
     */
    private void openOffline(Player viewer, PlayerRef target, boolean ender) {
        InventorySnapshotService service = ctx.services().get(InventorySnapshotService.class).orElse(null);
        if (service == null) {
            ctx.messages().send(viewer, "player.offline-unsupported");
            return;
        }
        service.latest(target.uuid(), "quit").whenComplete((snapshot, error) -> {
            ctx.scheduler().entity(viewer, () -> {
                if (cancelled || !viewer.isOnline()) return;
                if (error != null) {
                    ctx.logger().warn("Reading the last snapshot of {} failed", target.uuid(), error);
                    ctx.messages().send(viewer, "player.failed");
                    return;
                }
                if (snapshot == null || snapshot.isEmpty()) {
                    ctx.messages().send(viewer, "player.offline-no-snapshot",
                            Msg.unparsed("player", target.name()));
                    return;
                }
                boolean editable = viewer.hasPermission("atrium.invsee.modify.offline");
                // /enderchest alone must not expose the whole inventory; that needs the invsee permission.
                boolean allowInventory = !ender || viewer.hasPermission("atrium.invsee.others");
                new OfflineInventoryMenu(ctx, viewer, target, snapshot.get(), editable, ender, allowInventory)
                        .open();
            }, null);
        });
    }

    private boolean allowed(Session s) {
        return s.viewer.hasPermission(s.basePermission()) && (s.viewer.equals(s.target)
                || s.viewer.hasPermission(s.basePermission() + ".others"));
    }

    private void refresh(Session s) {
        if (cancelled || s.closed) return;
        ctx.scheduler().supplyOnEntity(s.target, () -> {
            if (cancelled || s.closed || !s.target.isOnline()) return null;
            ItemStack[] contents = s.live().getContents();
            ItemStack[] copy = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) copy[i] = contents[i] == null ? null : contents[i].clone();
            return copy;
        }).whenComplete((contents, error) -> {
            if (cancelled || s.closed) return;
            if (error != null) {
                if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)
                        && !(error.getCause() instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException))
                    ctx.logger().warn("Inventory inspection failed for {}", s.target.getUniqueId(), error);
                close(s);
                return;
            }
            ctx.scheduler().entity(s.viewer, () -> {
                if (cancelled || s.closed) return;
                if (contents == null || !allowed(s) || s.viewer.getOpenInventory().getTopInventory() != s.display) {
                    close(s); return;
                }
                s.display.clear();
                for (int i = 0; i < contents.length; i++) s.display.setItem(i, contents[i]);
                decorate(s);
                s.refresh = ctx.scheduler().entityLater(s.viewer, () -> refresh(s), s::stop, 10);
                if (s.closed || cancelled) s.refresh.cancel();
            }, s::stop);
        });
    }

    /**
     * Themes the display-only slots after the 41 live slots (storage, armor, off hand) of an inventory view.
     * Clicks there are ignored by {@link #onClick}, and the whole display is a copy that is never written back.
     */
    private void decorate(Session s) {
        if (s.ender || s.info == null) return;
        ItemStack filler = ctx.menus().filler();
        for (int slot = DECOR_FIRST; slot < INFO_SLOT; slot++) {
            s.display.setItem(slot, filler.isEmpty() ? null : filler);
        }
        s.display.setItem(INFO_SLOT, s.info.clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Session s)) return;
        // Cancel every vanilla path: shift, double-click, hotbar, offhand, drop, creative clone.
        event.setCancelled(true);
        if (cancelled || s.closed || event.getWhoClicked() != s.viewer || !allowed(s)) return;
        if (!s.viewer.hasPermission(s.modifyPermission())) return;
        if (event.getClick() != ClickType.LEFT || event.getClickedInventory() == null) return;
        boolean top = event.getClickedInventory() == s.display;
        int slot = event.getSlot();
        if (slot < 0 || (top && slot >= (s.ender ? 27 : DECOR_FIRST))) return;
        if (!s.busy.compareAndSet(false, true)) return;
        // Defer out of InventoryClickEvent. Both inventories must be owned by this task's region.
        ctx.scheduler().entity(s.target, () -> {
            try {
                if (cancelled || s.closed) return;
                if (!Bukkit.isOwnedByCurrentRegion(s.viewer)) {
                    message(s.viewer, "inventory-distant"); return;
                }
                if (!s.target.isOnline() || !s.viewer.isOnline() || !allowed(s)
                        || !s.viewer.hasPermission(s.modifyPermission())
                        || s.viewer.getOpenInventory().getTopInventory() != s.display) return;
                Inventory inventory = top ? s.live() : s.viewer.getInventory();
                ItemStack live = inventory.getItem(slot);
                ItemStack cursor = s.viewer.getItemOnCursor();
                // No asynchronous gap between these writes, and no source data from the display.
                inventory.setItem(slot, cursor.isEmpty() ? null : cursor.clone());
                s.viewer.setItemOnCursor(live == null ? null : live.clone());
                if (top) s.display.setItem(slot, cursor.isEmpty() ? null : cursor.clone());
            } catch (RuntimeException e) {
                ctx.logger().error("Live inventory edit failed for {}", s.target.getUniqueId(), e);
                message(s.viewer, "failed");
            } finally { s.busy.set(false); }
        }, () -> { s.busy.set(false); close(s); });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Session) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session s) {
            s.stop();
            sessions.remove(s.viewer.getUniqueId(), s);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        for (Session s : sessions.values()) {
            if (s.viewer.equals(event.getPlayer()) || s.target.equals(event.getPlayer())) close(s);
        }
    }

    private void message(Player player, String key) {
        ctx.scheduler().entity(player, () -> {
            if (!cancelled) ctx.messages().send(player, "player." + key);
        }, null);
    }

    private void close(Session s) {
        s.stop();
        sessions.remove(s.viewer.getUniqueId(), s);
        ctx.scheduler().entity(s.viewer, () -> {
            if (s.viewer.getOpenInventory().getTopInventory() == s.display) s.viewer.closeInventory();
            s.display.clear();
        }, null);
    }

    @Override public void cancel() {
        cancelled = true;
        sessions.values().forEach(this::close);
        sessions.clear();
    }
    @Override public boolean isCancelled() { return cancelled; }
}
