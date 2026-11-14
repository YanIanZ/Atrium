package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.api.moderation.PunishResult;
import dev.yanianz.atrium.api.moderation.PunishmentRequest;
import dev.yanianz.atrium.api.moderation.PunishmentService;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.moderation.RevokeResult;
import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/** Staff mode lifecycle, saved inventories and the actions behind the staff tools. */
final class StaffManager {
    static final String FLIGHT_OWNER = "staff:mode";
    private static final String PERMISSION_FREEZE = "atrium.staff.freeze";
    static final String PERMISSION_RECOVER = "atrium.staff.recover";

    private final ModuleContext ctx;
    private final ConfigHandle<StaffConfig> config;
    private final StaffStore store;
    private final StaffTools tools;
    private final @Nullable VanishService vanish;
    private final @Nullable FlightService flight;
    private final @Nullable PunishmentService punishments;
    /**
     * Backend identity frozen when the manager was created (validated by the module). Never read
     * from the config again: a reload cannot null it out or reassign the origin of live rows.
     */
    private final String serverId;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    /**
     * Players whose restore may have changed the inventory. They are not in staff mode, but the
     * inventory stays locked until the held row is applied or discarded by an operator.
     */
    private final Set<UUID> held = ConcurrentHashMap.newKeySet();
    /** Rows confirmed RESTORING whose inventory task has not started yet. Claimed by the task or by disable. */
    private final Map<UUID, StaffStore.Stored> pendingApply = new ConcurrentHashMap<>();
    /** Reviewed rows were already uncertain before this attempt; disable must never reactivate them. */
    private final Set<UUID> reviewedRestores = ConcurrentHashMap.newKeySet();
    /** Snapshots that exist only in memory: the inventory was cleared but storing failed and the player left. */
    private final Map<UUID, StaffSnapshot> orphans = new ConcurrentHashMap<>();
    /** Set when the module is being disabled: from then on nothing is scheduled, state is only persisted. */
    private volatile boolean closing;

    StaffManager(ModuleContext ctx, ConfigHandle<StaffConfig> config, StaffStore store, StaffTools tools) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.tools = tools;
        this.vanish = ctx.services().get(VanishService.class).orElse(null);
        this.flight = ctx.services().get(FlightService.class).orElse(null);
        this.punishments = ctx.services().get(PunishmentService.class).orElse(null);
        this.serverId = config.get().serverId;
    }

    boolean isActive(UUID uuid) {
        return active.contains(uuid);
    }

    boolean isActive(Player player) {
        return isActive(player.getUniqueId());
    }

    /** In staff mode, in the middle of entering/leaving it, or holding a partial restore. */
    boolean isLocked(Player player) {
        UUID uuid = player.getUniqueId();
        return active.contains(uuid) || busy.contains(uuid) || held.contains(uuid);
    }

    // ---- staff mode ----

    void toggle(Player player, CommandSender sender) {
        if (closing) {
            return;
        }
        if (!busy.add(player.getUniqueId())) {
            reply(sender, "staff.busy");
            return;
        }
        if (isActive(player)) {
            beginExit(player, sender, true);
        } else {
            enter(player, sender);
        }
    }

    /**
     * Entering: capture and clear the inventory in one task on the player's thread (nothing can change in
     * between), then store the snapshot. Until it is stored the player is locked; if storing fails the
     * items are given back from memory. A snapshot is therefore never stored for items the player still
     * holds, which is what made the old order (save, then clear later) duplicate items.
     */
    private void enter(Player player, CommandSender sender) {
        UUID uuid = player.getUniqueId();
        store.load(uuid).whenComplete((existing, loadError) -> {
            if (loadError != null) {
                busy.remove(uuid);
                ctx.logger().error("Checking the staff snapshot of {} failed; staff mode stays off",
                        player.getName(), loadError);
                reply(sender, "staff.save-failed");
                return;
            }
            if (!SnapshotLifecycle.canEnter(existing == null ? null : existing.state())) {
                busy.remove(uuid);
                if (existing.state() == SnapshotLifecycle.State.ACTIVE && !existing.corrupt()) {
                    // A previous session was never restored (e.g. the module was reloaded): restore it now.
                    restoreOnJoin(player);
                    reply(sender, "staff.restore-pending", Msg.unparsed("player", player.getName()));
                } else {
                    reply(sender, "staff.recover-held", Msg.unparsed("player", player.getName()));
                }
                return;
            }
            CompletableFuture<StaffSnapshot> captured;
            try {
                captured = closing ? null : ctx.scheduler().supplyOnEntity(player, () -> captureAndClear(player));
            } catch (RuntimeException e) {
                captured = CompletableFuture.failedFuture(e);
            }
            if (captured == null) {
                busy.remove(uuid);
                return;
            }
            captured.whenComplete((snapshot, captureError) -> {
                if (captureError != null) {
                    busy.remove(uuid);
                    Throwable cause = unwrap(captureError);
                    if (!(cause instanceof Scheduler.EntityRetiredException)) {
                        ctx.logger().error("Capturing the inventory of {} failed; staff mode stays off",
                                player.getName(), cause);
                        reply(sender, "staff.save-failed");
                    }
                    return;
                }
                store.begin(uuid, snapshot).whenComplete((inserted, saveError) -> {
                    if (saveError == null && Boolean.TRUE.equals(inserted)) {
                        // Row is ACTIVE. If the player already left, the next join restores it.
                        runOn(player, () -> applyStaffMode(player, sender), () -> releaseAndRecheck(uuid));
                        return;
                    }
                    if (saveError != null) {
                        ctx.logger().error("Saving the staff snapshot of {} failed; giving the items back",
                                player.getName(), saveError);
                    } else {
                        ctx.logger().warn("A staff snapshot of {} appeared meanwhile; giving the items back",
                                player.getName());
                    }
                    reply(sender, "staff.save-failed");
                    giveBack(player, snapshot);
                });
            });
        });
    }

    /** Player's thread. Returns the snapshot of what was removed. Throws before clearing anything on failure. */
    private StaffSnapshot captureAndClear(Player player) {
        ctx.menus().close(player);
        // Closing returns the cursor and crafting grid items to the inventory, so they are captured too.
        player.closeInventory();
        Boolean vanished = vanish == null ? null : vanish.isVanished(player.getUniqueId());
        StaffSnapshot snapshot = StaffSnapshots.capture(player, vanished);
        // Prove that the stored form can be read back before anything is removed (size limits, codec).
        StaffSnapshotCodec.decode(StaffSnapshotCodec.encode(snapshot));
        var inventory = player.getInventory();
        try {
            inventory.setStorageContents(new ItemStack[inventory.getStorageContents().length]);
            inventory.setArmorContents(new ItemStack[inventory.getArmorContents().length]);
            inventory.setItemInOffHand(null);
        } catch (RuntimeException e) {
            dropLeftovers(player, applyItems(player, snapshot, ExtraItemsMode.KEEP));
            throw e;
        }
        return snapshot;
    }

    /** Storing a fresh snapshot failed: put the captured items back from memory. */
    private void giveBack(Player player, StaffSnapshot snapshot) {
        UUID uuid = player.getUniqueId();
        runOn(player, () -> {
            try {
                dropLeftovers(player, applyItems(player, snapshot, ExtraItemsMode.KEEP));
            } catch (RuntimeException e) {
                orphan(uuid, player.getName(), snapshot, e);
            } finally {
                busy.remove(uuid);
            }
        }, () -> {
            orphan(uuid, player.getName(), snapshot, null);
            releaseAndRecheck(uuid);
        });
    }

    private void orphan(UUID uuid, String name, StaffSnapshot snapshot, @Nullable Throwable error) {
        orphans.put(uuid, snapshot);
        ctx.logger().error("The pre-staff inventory of {} ({}) exists only in memory and is stored again on the"
                        + " next join. Encoded snapshot for manual recovery: {}", name, uuid,
                Base64.getEncoder().encodeToString(StaffSnapshotCodec.encode(snapshot)), error);
    }

    /** Runs on the player's thread, only after the snapshot was stored as ACTIVE. */
    private void applyStaffMode(Player player, CommandSender sender) {
        UUID uuid = player.getUniqueId();
        active.add(uuid);
        try {
            tools.give(player);
            if (flight != null) {
                flight.claim(player, FLIGHT_OWNER);
            }
            if (vanish != null) {
                vanish.setVanished(player, true, sender).exceptionally(error -> {
                    ctx.logger().warn("Vanishing {} for staff mode failed", player.getName(), error);
                    return false;
                });
            }
            ctx.messages().send(player, "staff.mode-enabled");
            if (!player.equals(sender)) {
                reply(sender, "staff.mode-enabled-other", Msg.unparsed("player", player.getName()));
            }
            ctx.logger().info("{} entered staff mode", player.getName());
            busy.remove(uuid);
        } catch (RuntimeException e) {
            ctx.logger().error("Entering staff mode failed for {}; restoring the saved inventory",
                    player.getName(), e);
            ctx.messages().send(player, "staff.enter-failed");
            beginExit(player, sender, false); // keeps busy until the restore finished
        }
    }

    /** Leaving (busy must be held): mark the row RESTORING and only then touch the inventory. */
    private void beginExit(Player player, @Nullable CommandSender sender, boolean announce) {
        UUID uuid = player.getUniqueId();
        store.load(uuid).whenComplete((stored, error) -> {
            if (error != null) {
                busy.remove(uuid);
                ctx.logger().error("Loading the staff snapshot of {} failed; staff mode stays on",
                        player.getName(), error);
                reply(sender, "staff.restore-failed", Msg.unparsed("player", player.getName()));
                return;
            }
            if (stored == null) {
                // The row disappeared (manual cleanup). Never leave the tools behind; the vanish state before
                // staff mode is unknown, so it is left as it is rather than revealing the player.
                runOn(player, () -> {
                    try {
                        ctx.menus().close(player);
                        player.closeInventory();
                        tools.remove(player);
                        active.remove(uuid);
                        if (flight != null) {
                            flight.release(player, FLIGHT_OWNER);
                        }
                        ctx.messages().send(player, "staff.snapshot-missing");
                    } finally {
                        busy.remove(uuid);
                    }
                }, () -> busy.remove(uuid));
                return;
            }
            if (!StaffRestorePolicy.originAllowed(serverId, stored.origin())) {
                busy.remove(uuid);
                reply(sender, "staff.snapshot-foreign",
                        Msg.unparsed("origin", StaffRestorePolicy.displayOrigin(stored.origin())));
                return;
            }
            if (stored.corrupt() || stored.state() != SnapshotLifecycle.State.ACTIVE) {
                busy.remove(uuid);
                ctx.logger().error("The staff snapshot of {} is {}; staff mode stays on until it is reviewed with"
                        + " /staffmode recover", player.getName(), stored.corrupt() ? "corrupt" : stored.state());
                reply(sender, "staff.recover-held", Msg.unparsed("player", player.getName()));
                return;
            }
            store.transition(uuid, stored, SnapshotLifecycle.State.ACTIVE, SnapshotLifecycle.State.RESTORING)
                    .whenComplete((marked, markError) -> {
                        if (markError != null || !Boolean.TRUE.equals(marked)) {
                            busy.remove(uuid);
                            ctx.logger().error("Marking the staff snapshot of {} as restoring failed; nothing was"
                                    + " restored and staff mode stays on", player.getName(), markError);
                            reply(sender, "staff.restore-failed", Msg.unparsed("player", player.getName()));
                            return;
                        }
                        applyRestore(player, stored, sender, announce, false);
                    });
        });
    }

    /**
     * The row is RESTORING (confirmed). Applies it on the player's thread and deletes the row
     * afterwards. Ownership ({@code pendingApply}) is kept through the teleport and only released
     * the moment the inventory may change, so a disable in between still reverts the untouched row
     * to ACTIVE instead of stranding it.
     */
    private void applyRestore(Player player, StaffStore.Stored stored, @Nullable CommandSender sender,
            boolean announce, boolean onJoin) {
        UUID uuid = player.getUniqueId();
        pendingApply.put(uuid, stored);
        runOn(player, () -> {
            if (!stored.equals(pendingApply.get(uuid))) {
                busy.remove(uuid);
                return;
            }
            applySnapshot(player, stored, config.get().extraItemsOnExit, sender, onJoin)
                    .whenComplete((outcome, applyError) -> {
                        StaffRestorePolicy.RestoreOutcome result = outcome;
                        if (applyError != null) {
                            result = unwrap(applyError) instanceof Scheduler.EntityRetiredException
                                    ? StaffRestorePolicy.RestoreOutcome.NOT_APPLIED
                                    : StaffRestorePolicy.RestoreOutcome.PARTIAL;
                        }
                        if (result == null) {
                            result = StaffRestorePolicy.RestoreOutcome.PARTIAL;
                        }
                        switch (result) {
                            case APPLIED -> {
                                active.remove(uuid);
                                held.add(uuid);
                                store.finish(uuid, stored).whenComplete((deleted, deleteError) -> {
                                    busy.remove(uuid);
                                    if (deleteError != null || !Boolean.TRUE.equals(deleted)) {
                                        ctx.logger().error(
                                                "Archiving/finishing the staff snapshot failed for {}; row retained",
                                                player.getName(), deleteError);
                                        tell(player, "staff.delete-failed", Msg.unparsed("player", player.getName()));
                                        return;
                                    }
                                    held.remove(uuid);
                                    ctx.logger().info("{} left staff mode", player.getName());
                                    if (onJoin) tell(player, "staff.restored-on-join");
                                    else if (announce) {
                                        tell(player, "staff.mode-disabled");
                                        if (sender != null && !player.equals(sender)) reply(sender,
                                                "staff.mode-disabled-other",
                                                Msg.unparsed("player", player.getName()));
                                    }
                                });
                            }
                            case NOT_APPLIED -> handleNotApplied(uuid, player, stored, sender, onJoin);
                            case PARTIAL -> handlePartial(uuid, player, sender, onJoin);
                        }
                    });
        }, () -> {
            if (pendingApply.remove(uuid, stored)) revertToActive(uuid, player.getName(), stored);
            else busy.remove(uuid);
        });
    }

    /**
     * Pre-apply failure: the inventory was provably not touched (teleport failed, the entity was
     * retired, the token changed, the world is missing), so one exact-token CAS back to ACTIVE is
     * safe and the row is not stranded as RESTORING. A single attempt only: no retries, no timers.
     */
    private void handleNotApplied(UUID uuid, Player context, StaffStore.Stored stored,
            @Nullable CommandSender sender, boolean onJoin) {
        pendingApply.remove(uuid, stored);
        if (onJoin) held.add(uuid);
        String name = context.getName();
        if (closing) {
            try {
                if (Boolean.TRUE.equals(store.transition(uuid, stored,
                        SnapshotLifecycle.State.RESTORING, SnapshotLifecycle.State.ACTIVE).get(5, TimeUnit.SECONDS))) {
                    ctx.logger().info("Staff restore of {} did not reach the inventory; row reverted to ACTIVE",
                            name);
                } else {
                    ctx.logger().warn("Staff restore of {} did not reach the inventory but the row changed;"
                            + " it is reviewed on the next join", name);
                }
            } catch (Exception e) {
                ctx.logger().error("Reverting the staff snapshot of {} to ACTIVE failed; it is reviewed on the next"
                        + " join", name, e);
            }
            busy.remove(uuid);
            return;
        }
        store.transition(uuid, stored, SnapshotLifecycle.State.RESTORING, SnapshotLifecycle.State.ACTIVE)
                .whenComplete((ok, error) -> {
                    if (error != null || !Boolean.TRUE.equals(ok)) {
                        busy.remove(uuid);
                        ctx.logger().error("Reverting the staff snapshot of {} to ACTIVE failed; the row stays"
                                + " RESTORING for the next join", name, error);
                        notifyReviewers(name);
                        return;
                    }
                    if (!onJoin) {
                        busy.remove(uuid);
                        ctx.logger().info("Staff restore of {} did not reach the inventory; staff mode stays on",
                                name);
                        reply(sender, "staff.restore-failed", Msg.unparsed("player", name));
                        return;
                    }
                    busy.remove(uuid);
                    // The same session with a persistent failure must not spin: only a session that
                    // rejoined meanwhile is retried now, anything else waits for the next join.
                    Player again = Bukkit.getPlayer(uuid);
                    if (again != null && again != context && !isActive(uuid)) {
                        restoreOnJoin(again);
                    }
                });
    }

    /**
     * The inventory may have been changed: never revert, never unlock. Move to the held lock so an
     * operator can use reviewed recovery without treating the damaged state as a live staff kit. The row stays
     * RESTORING for {@code /staffmode recover}.
     */
    private void handlePartial(UUID uuid, Player player, @Nullable CommandSender sender, boolean onJoin) {
        pendingApply.remove(uuid);
        active.remove(uuid);
        held.add(uuid);
        busy.remove(uuid);
        ctx.logger().error("Staff restore of {} may have reached the inventory; the row is kept as RESTORING"
                + " for /staffmode recover", player.getName());
        reply(sender, "staff.restore-failed", Msg.unparsed("player", player.getName()));
        tell(player, "staff.recover-held-self");
        notifyReviewers(player.getName());
    }

    /** RESTORING → ACTIVE for a restore whose inventory task never ran. Releases busy when done. */
    private void revertToActive(UUID uuid, String name, StaffStore.Stored stored) {
        CompletableFuture<Boolean> reverted =
                store.transition(uuid, stored, SnapshotLifecycle.State.RESTORING, SnapshotLifecycle.State.ACTIVE);
        if (closing) {
            try {
                reverted.get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                ctx.logger().error("Reverting the staff snapshot of {} to ACTIVE failed; it is reviewed on the next"
                        + " join", name, e);
            }
            busy.remove(uuid);
            return;
        }
        reverted.whenComplete((ok, error) -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                ctx.logger().error("Reverting the staff snapshot of {} to ACTIVE failed; it is reviewed on the next"
                        + " join", name, error);
            }
            releaseAndRecheck(uuid);
        });
    }

    /**
     * Releases busy after an operation whose player left. A quick rejoin was skipped by restoreOnJoin while
     * busy was held, so the join restore is started now for the new session.
     */
    private void releaseAndRecheck(UUID uuid) {
        busy.remove(uuid);
        Player again = closing ? null : Bukkit.getPlayer(uuid);
        if (again != null && !isActive(uuid)) {
            restoreOnJoin(again);
        }
    }

    /**
     * Restores a snapshot with an exact outcome. The teleport future alone proves nothing about the
     * inventory: after it completes the row is reloaded and the token plus creation time are checked
     * again, and the merge proof (staff tools still held) is read at the destination, on the
     * entity's thread, just before anything is applied. Never merges an unrelated destination
     * profile inventory, never replays a row automatically, never deletes anything here.
     */
    private CompletableFuture<StaffRestorePolicy.RestoreOutcome> applySnapshot(Player player,
            StaffStore.Stored stored, ExtraItemsMode configuredMode, @Nullable CommandSender actor, boolean onJoin) {
        UUID uuid = player.getUniqueId();
        StaffSnapshot snapshot = stored.snapshot();
        // Profile/world attachment can replace inventory during a teleport. Complete the move before
        // applying any items, and retain the row until the final entity-owned restore has succeeded.
        Location location = StaffSnapshots.location(snapshot);
        if (closing || !player.isOnline() || location == null) {
            if (location == null) tell(player, "staff.world-missing");
            return CompletableFuture.completedFuture(StaffRestorePolicy.RestoreOutcome.NOT_APPLIED);
        }
        CompletableFuture<Boolean> moved;
        try {
            moved = player.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } catch (RuntimeException error) {
            ctx.logger().warn("Staff restore teleport of {} failed before the inventory was touched",
                    player.getName(), error);
            return CompletableFuture.completedFuture(StaffRestorePolicy.RestoreOutcome.NOT_APPLIED);
        }
        return moved.thenCompose(ok -> {
            if (!Boolean.TRUE.equals(ok) || closing) {
                return CompletableFuture.completedFuture(StaffRestorePolicy.RestoreOutcome.NOT_APPLIED);
            }
            return store.load(player.getUniqueId()).thenCompose(current -> {
                if (current == null || current.state() != SnapshotLifecycle.State.RESTORING
                        || !current.token().equals(stored.token()) || current.created() != stored.created()) {
                    ctx.logger().warn("Staff snapshot of {} changed during teleport; inventory was not touched",
                            player.getName());
                    return CompletableFuture.completedFuture(StaffRestorePolicy.RestoreOutcome.NOT_APPLIED);
                }
                CompletableFuture<StaffRestorePolicy.RestoreOutcome> applied;
                try {
                    applied = ctx.scheduler().supplyOnEntity(player, () -> {
                        // Ownership is released only here, the first moment the inventory may change,
                        // so a disable during the teleport still reverts the untouched row to ACTIVE.
                        if (!pendingApply.remove(uuid, stored)) {
                            return StaffRestorePolicy.RestoreOutcome.NOT_APPLIED;
                        }
                        if (closing || !player.isOnline()) {
                            return StaffRestorePolicy.RestoreOutcome.NOT_APPLIED;
                        }
                        ExtraItemsMode mode = StaffRestorePolicy.restoreMode(configuredMode, onJoin,
                                tools.holdsStaffTool(player));
                        boolean done;
                        try {
                            done = applySnapshotHere(player, snapshot, mode, actor);
                        } catch (RuntimeException e) {
                            ctx.logger().error("Restoring the staff snapshot of {} failed part way; the row is"
                                    + " kept as RESTORING for /staffmode recover", player.getName(), e);
                            return StaffRestorePolicy.RestoreOutcome.PARTIAL;
                        }
                        return done ? StaffRestorePolicy.RestoreOutcome.APPLIED
                                : StaffRestorePolicy.RestoreOutcome.PARTIAL;
                    });
                } catch (RuntimeException e) {
                    ctx.logger().warn("Scheduling the staff restore of {} failed before the inventory was touched",
                            player.getName(), e);
                    return CompletableFuture.completedFuture(StaffRestorePolicy.RestoreOutcome.NOT_APPLIED);
                }
                return applied.handle((result, error) -> {
                    if (error != null) {
                        return unwrap(error) instanceof Scheduler.EntityRetiredException
                                ? StaffRestorePolicy.RestoreOutcome.NOT_APPLIED
                                : StaffRestorePolicy.RestoreOutcome.PARTIAL;
                    }
                    return result == null ? StaffRestorePolicy.RestoreOutcome.PARTIAL : result;
                });
            }).exceptionally(error -> {
                ctx.logger().warn("Reloading the staff snapshot of {} after teleport failed; inventory was not"
                        + " touched", player.getName(), error);
                return StaffRestorePolicy.RestoreOutcome.NOT_APPLIED;
            });
        }).exceptionally(error -> {
            ctx.logger().warn("Staff restore teleport of {} failed before the inventory was touched",
                    player.getName(), error);
            return StaffRestorePolicy.RestoreOutcome.NOT_APPLIED;
        });
    }

    private boolean applySnapshotHere(Player player, StaffSnapshot snapshot, ExtraItemsMode mode,
            @Nullable CommandSender actor) {
        try {
            ctx.menus().close(player);
            player.closeInventory();
            List<ItemStack> leftovers = applyItems(player, snapshot, mode);

            player.setLevel(snapshot.level());
            player.setExp(clampExp(snapshot.exp()));
            player.setTotalExperience(Math.max(0, snapshot.totalExperience()));

            player.clearActivePotionEffects();
            List<PotionEffect> effects = StaffSnapshots.effects(snapshot, warning ->
                    ctx.logger().warn("Staff snapshot of {}: {}", player.getName(), warning));
            for (PotionEffect effect : effects) {
                player.addPotionEffect(effect);
            }

            player.setGameMode(StaffSnapshots.gameMode(snapshot.gameMode()));
            if (flight != null) {
                // The flight service owns allowFlight: drop only our claim, other owners (/fly) keep theirs.
                if (snapshot.flying() && player.getAllowFlight()) {
                    player.setFlying(true);
                }
                flight.release(player, FLIGHT_OWNER);
            } else {
                player.setAllowFlight(snapshot.allowFlight() || player.getGameMode() == GameMode.CREATIVE
                        || player.getGameMode() == GameMode.SPECTATOR);
                if (player.getAllowFlight() && snapshot.flying()) {
                    player.setFlying(true);
                } else if (player.isFlying()) {
                    player.setFlying(false);
                }
            }

            Boolean wasVanished = snapshot.vanished();
            if (vanish != null && wasVanished != null) {
                // Exactly the state before staff mode: a staff member who was vanished stays vanished.
                vanish.setVanished(player, wasVanished, actor).exceptionally(error -> {
                    ctx.logger().warn("Restoring the vanish state of {} after staff mode failed", player.getName(),
                            error);
                    return false;
                });
            }

            dropLeftovers(player, leftovers);
            return true;
        } catch (RuntimeException e) {
            ctx.logger().error("Restoring the staff snapshot of {} failed part way; the row is kept as RESTORING"
                    + " for /staffmode recover", player.getName(), e);
            return false;
        }
    }

    /** Player's thread. Puts the snapshot's items back; returns what has to be dropped. */
    private List<ItemStack> applyItems(Player player, StaffSnapshot snapshot, ExtraItemsMode mode) {
        ItemStack[] current = normalize(currentItems(player));
        for (ItemStack item : current) {
            if (item != null) {
                tools.scrub(item, 0);
            }
        }
        var inventory = player.getInventory();
        // Restore into the live sizes: saved items past a smaller live section go into free storage slots, then
        // to the leftovers that are dropped at the player's feet. Nothing saved is ever cut off.
        int storageSize = inventory.getStorageContents().length;
        int armorSize = inventory.getArmorContents().length;
        ItemStack[] armor = normalize(StaffSnapshots.armor(snapshot));
        ItemStack[] offhand = normalize(StaffSnapshots.offhand(snapshot));
        List<ItemStack> extras = new ArrayList<>();
        for (int slot = armorSize; slot < armor.length; slot++) {
            extras.add(armor[slot]);
        }
        for (int slot = 1; slot < offhand.length; slot++) {
            extras.add(offhand[slot]);
        }
        InventoryMerge.Plan<ItemStack> plan = InventoryMerge.plan(normalize(StaffSnapshots.storage(snapshot)),
                storageSize, extras, current, tools::isStaffItem, mode);
        inventory.setStorageContents(plan.target());
        inventory.setArmorContents(fit(armor, armorSize));
        inventory.setItemInOffHand(offhand.length > 0 ? offhand[0] : null);
        return plan.leftovers();
    }

    /** Called on every join (and for online players when the module starts). Any thread. */
    void restoreOnJoin(Player player) {
        if (closing) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (isActive(player) || !busy.add(uuid)) {
            return;
        }
        StaffSnapshot orphan = orphans.remove(uuid);
        CompletableFuture<?> persisted = orphan == null
                ? CompletableFuture.completedFuture(null)
                : store.begin(uuid, orphan).handle((stored, error) -> {
                    if (error != null || !Boolean.TRUE.equals(stored)) {
                        orphan(uuid, player.getName(), orphan, error);
                    }
                    return null;
                });
        persisted.thenCompose(ignored -> store.load(uuid)).whenComplete((stored, error) -> {
            if (error != null) {
                busy.remove(uuid);
                ctx.logger().error("Loading the staff snapshot of {} on join failed", player.getName(), error);
                return;
            }
            if (stored == null) {
                held.remove(uuid);
                busy.remove(uuid);
                return;
            }
            if (!StaffRestorePolicy.originAllowed(serverId, stored.origin())) {
                busy.remove(uuid);
                tell(player, "staff.snapshot-foreign",
                        Msg.unparsed("origin", StaffRestorePolicy.displayOrigin(stored.origin())));
                return;
            }
            if (stored.corrupt()) {
                holdForReview(player);
                return;
            }
            if (stored.state() == SnapshotLifecycle.State.ACTIVE) {
                store.transition(uuid, stored, SnapshotLifecycle.State.ACTIVE, SnapshotLifecycle.State.RESTORING)
                        .whenComplete((marked, markError) -> {
                            if (markError != null || !Boolean.TRUE.equals(marked)) {
                                busy.remove(uuid);
                                ctx.logger().error("Marking the staff snapshot of {} as restoring failed; it is"
                                        + " tried again on the next join", player.getName(), markError);
                                return;
                            }
                            applyRestore(player, stored, null, false, true);
                        });
                return;
            }
            // RESTORING: a restore started earlier and may already have reached the inventory.
            runOn(player, () -> {
                boolean equal = StaffSnapshots.inventoryEquals(player, stored.snapshot());
                switch (SnapshotLifecycle.onJoin(stored.state(), false, equal)) {
                    case MARK_RESTORED -> store.finish(uuid, stored).whenComplete((deleted, deleteError) -> {
                        busy.remove(uuid);
                        if (deleteError != null) {
                            ctx.logger().error("Deleting the already restored staff snapshot of {} failed",
                                    player.getName(), deleteError);
                        } else {
                            ctx.logger().info("Staff snapshot of {} was already restored; row removed",
                                    player.getName());
                        }
                    });
                    default -> holdForReview(player);
                }
            }, () -> busy.remove(uuid));
        });
    }

    private void holdForReview(Player player) {
        held.add(player.getUniqueId());
        busy.remove(player.getUniqueId());
        ctx.logger().warn("The staff snapshot of {} needs a manual review: /staffmode recover {} list",
                player.getName(), player.getName());
        tell(player, "staff.recover-held-self");
        notifyReviewers(player.getName());
    }

    private void notifyReviewers(String name) {
        if (closing) {
            return;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission(PERMISSION_RECOVER)) {
                tell(online, "staff.recover-needed", Msg.unparsed("player", name));
            }
        }
    }

    // ---- /staffmode recover ----

    void recoverOverview(CommandSender sender) {
        store.withState(SnapshotLifecycle.State.RESTORING).whenComplete((uuids, error) -> {
            if (error != null) {
                ctx.logger().error("Listing held staff snapshots failed", error);
                reply(sender, "staff.recover-failed");
                return;
            }
            if (uuids.isEmpty()) {
                reply(sender, "staff.recover-none");
                return;
            }
            List<String> names = new ArrayList<>();
            for (UUID uuid : uuids) {
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                names.add(name == null ? uuid.toString() : name);
            }
            reply(sender, "staff.recover-overview", Msg.number("count", names.size()),
                    Msg.unparsed("players", String.join(", ", names)));
        });
    }

    void recover(CommandSender sender, UUID uuid, String name, String action) {
        store.load(uuid).whenComplete((stored, error) -> {
            if (error != null) {
                ctx.logger().error("Loading the staff snapshot of {} for review failed", name, error);
                reply(sender, "staff.recover-failed");
                return;
            }
            if (stored == null) {
                reply(sender, "staff.recover-empty", Msg.unparsed("player", name));
                return;
            }
            switch (action) {
                case "apply" -> recoverApply(sender, uuid, name, stored);
                case "discard" -> recoverDiscard(sender, uuid, name, stored);
                default -> reply(sender, "staff.recover-info", Msg.unparsed("player", name),
                        Msg.unparsed("state", stored.corrupt() ? "CORRUPT" : stored.state().name()),
                        Msg.unparsed("created", Instant.ofEpochMilli(stored.created()).toString()),
                        Msg.unparsed("location", stored.corrupt() ? "?" : String.format(Locale.ROOT,
                                "%s %.1f %.1f %.1f", stored.snapshot().world(), stored.snapshot().x(),
                                stored.snapshot().y(), stored.snapshot().z())));
            }
        });
    }

    private void recoverApply(CommandSender sender, UUID uuid, String name, StaffStore.Stored stored) {
        Player target = Bukkit.getPlayer(uuid);
        if (!SnapshotLifecycle.canApply(stored.state(), stored.corrupt(), target != null, isActive(uuid))) {
            reply(sender, "staff.recover-apply-refused", Msg.unparsed("player", name));
            return;
        }
        // Manual recovery obeys the same backend guard: a foreign or legacy row is preserved and the
        // operator is told to review it. There is no automatic adoption here.
        if (!StaffRestorePolicy.originAllowed(serverId, stored.origin())) {
            reply(sender, "staff.snapshot-foreign",
                    Msg.unparsed("origin", StaffRestorePolicy.displayOrigin(stored.origin())));
            return;
        }
        if (closing || !busy.add(uuid)) {
            reply(sender, "staff.busy");
            return;
        }
        reviewedRestores.add(uuid);
        pendingApply.put(uuid, stored);
        runOn(target, () -> applySnapshot(target, stored, ExtraItemsMode.CLEAR, sender, false)
                .whenComplete((outcome, applyError) -> {
                    pendingApply.remove(uuid, stored);
                    reviewedRestores.remove(uuid);
                    StaffRestorePolicy.RestoreOutcome result = outcome;
                    if (applyError != null) {
                        result = unwrap(applyError) instanceof Scheduler.EntityRetiredException
                                ? StaffRestorePolicy.RestoreOutcome.NOT_APPLIED
                                : StaffRestorePolicy.RestoreOutcome.PARTIAL;
                    }
                    if (result == null) {
                        result = StaffRestorePolicy.RestoreOutcome.PARTIAL;
                    }
                    switch (result) {
                        case APPLIED -> {
                            held.add(uuid);
                            store.finish(uuid, stored).whenComplete((deleted, deleteError) -> {
                                busy.remove(uuid);
                                if (deleteError != null || !Boolean.TRUE.equals(deleted)) {
                                    ctx.logger().error("Archiving the reviewed staff snapshot failed for {}", name,
                                            deleteError);
                                    reply(sender, "staff.delete-failed", Msg.unparsed("player", name));
                                    return;
                                }
                                held.remove(uuid);
                                tell(target, "staff.restored-on-join");
                                reply(sender, "staff.recover-applied", Msg.unparsed("player", name));
                            });
                        }
                        case NOT_APPLIED -> {
                            // This row was already held before the reviewed attempt. Its prior application
                            // is uncertain, so a failed new attempt must never make it auto-replayable.
                            pendingApply.remove(uuid, stored);
                            held.add(uuid);
                            busy.remove(uuid);
                            reply(sender, "staff.recover-failed");
                        }
                        case PARTIAL -> {
                            held.add(uuid);
                            busy.remove(uuid);
                            ctx.logger().warn("Reviewed staff restore failed for {}; snapshot retained", name,
                                    applyError);
                            reply(sender, "staff.recover-failed");
                        }
                    }
                }), () -> {
                    pendingApply.remove(uuid, stored);
                    reviewedRestores.remove(uuid);
                    busy.remove(uuid);
                    reply(sender, "staff.player-gone", Msg.unparsed("player", name));
                });
    }

    private void recoverDiscard(CommandSender sender, UUID uuid, String name, StaffStore.Stored stored) {
        if (!SnapshotLifecycle.canDiscard(stored.state())) {
            reply(sender, "staff.recover-discard-refused", Msg.unparsed("player", name));
            return;
        }
        // Discarding a foreign or legacy row would destroy another backend's (or an unadopted)
        // recovery copy: refuse and preserve it instead.
        if (!StaffRestorePolicy.originAllowed(serverId, stored.origin())) {
            reply(sender, "staff.snapshot-foreign",
                    Msg.unparsed("origin", StaffRestorePolicy.displayOrigin(stored.origin())));
            return;
        }
        if (closing || !busy.add(uuid)) {
            reply(sender, "staff.busy");
            return;
        }
        store.finish(uuid, stored).whenComplete((deleted, error) -> {
            busy.remove(uuid);
            if (error != null || !Boolean.TRUE.equals(deleted)) {
                ctx.logger().error("Discarding the staff snapshot of {} failed", name, error);
                reply(sender, "staff.recover-failed");
                return;
            }
            held.remove(uuid);
            ctx.logger().warn("{} discarded the held staff snapshot of {}", sender.getName(), name);
            reply(sender, "staff.recover-discarded", Msg.unparsed("player", name));
        });
    }

    // ---- lifecycle ----

    void handleQuit(Player player) {
        // busy is released by the operation that holds it (its retired callback), never here: releasing it
        // early would let a quick rejoin start a second restore of the same row. held persists too: a
        // partial restore stays locked until an operator applies or discards it.
        active.remove(player.getUniqueId());
    }

    /**
     * Module disable / server shutdown: never schedules anything. Rows stay ACTIVE (restored on the next join
     * or when the module starts again), restores that did not reach the inventory are reverted to ACTIVE
     * and in-memory snapshots are written, all synchronously. Entries already past ownership release
     * (the inventory may be changing) are never reverted.
     */
    void disable() {
        closing = true;
        for (var entry : Map.copyOf(pendingApply).entrySet()) {
            if (pendingApply.remove(entry.getKey(), entry.getValue())) {
                if (!reviewedRestores.remove(entry.getKey())) {
                    revertToActive(entry.getKey(), entry.getKey().toString(), entry.getValue());
                }
            }
        }
        reviewedRestores.clear();
        for (Map.Entry<UUID, StaffSnapshot> entry : Map.copyOf(orphans).entrySet()) {
            try {
                if (Boolean.TRUE.equals(store.begin(entry.getKey(), entry.getValue()).get(5, TimeUnit.SECONDS))) {
                    orphans.remove(entry.getKey());
                    continue;
                }
            } catch (Exception e) {
                ctx.logger().error("Storing the in-memory staff snapshot of {} failed", entry.getKey(), e);
            }
            ctx.logger().error("The in-memory staff snapshot of {} could not be stored: {}", entry.getKey(),
                    Base64.getEncoder().encodeToString(StaffSnapshotCodec.encode(entry.getValue())));
        }
        boolean pluginRunning = ctx.plugin().isEnabled();
        for (UUID uuid : active) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            ctx.logger().warn("{} is still in staff mode; the saved inventory is restored on the next join",
                    player.getName());
            if (pluginRunning && flight != null) {
                // Only a module reload: the player module is still running and owns the claim.
                try {
                    flight.release(player, FLIGHT_OWNER);
                } catch (RuntimeException e) {
                    ctx.logger().warn("Releasing the staff flight claim of {} failed", player.getName(), e);
                }
            }
        }
        active.clear();
        held.clear();
        busy.clear();
    }

    /**
     * Schedules {@code task} on the player's thread. While closing (or when the scheduler refuses) nothing
     * is scheduled and {@code retired} runs inline instead.
     */
    private void runOn(Player player, Runnable task, @Nullable Runnable retired) {
        if (!closing) {
            try {
                ctx.scheduler().entity(player, task, retired);
                return;
            } catch (RuntimeException e) {
                ctx.logger().warn("Scheduling a staff task for {} failed", player.getName(), e);
            }
        }
        if (retired != null) {
            retired.run();
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static float clampExp(float exp) {
        return Math.max(0f, Math.min(1f, exp));
    }

    private static ItemStack[] normalize(ItemStack[] items) {
        ItemStack[] copy = items == null ? new ItemStack[0] : items.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] != null && copy[i].getType().isAir()) {
                copy[i] = null;
            }
        }
        return copy;
    }

    /** Storage plus armour and off hand, so items taken during staff mode are never lost silently. */
    private static ItemStack[] currentItems(Player player) {
        var inventory = player.getInventory();
        List<ItemStack> items = new ArrayList<>();
        java.util.Collections.addAll(items, inventory.getStorageContents());
        java.util.Collections.addAll(items, inventory.getArmorContents());
        items.add(inventory.getItemInOffHand());
        return items.toArray(ItemStack[]::new);
    }

    private static ItemStack[] fit(ItemStack[] items, int size) {
        if (items.length == size) {
            return items;
        }
        ItemStack[] fitted = new ItemStack[size];
        System.arraycopy(items, 0, fitted, 0, Math.min(items.length, size));
        return fitted;
    }

    private void dropLeftovers(Player player, List<ItemStack> leftovers) {
        int dropped = 0;
        for (ItemStack item : leftovers) {
            if (item != null && !item.getType().isAir() && !tools.isStaffItem(item)) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
                dropped++;
            }
        }
        if (dropped > 0) {
            ctx.messages().send(player, "staff.extra-items-dropped", Msg.number("count", dropped));
        }
    }

    // ---- staff tools ----

    void useTool(Player actor, StaffTools.Tool tool, @Nullable Player target) {
        if (!actor.hasPermission("atrium.staff.mode")) {
            reply(actor, "staff.no-permission");
            return;
        }
        switch (tool) {
            case FREEZE -> {
                if (target == null) {
                    reply(actor, "staff.tool-needs-player");
                } else {
                    freeze(actor, target);
                }
            }
            case INSPECT -> {
                if (target == null) {
                    reply(actor, "staff.tool-needs-player");
                } else {
                    runCommand(actor, "invsee", target);
                }
            }
            case RANDOM_TELEPORT -> randomTeleport(actor);
            case VANISH -> toggleVanish(actor);
            case PLAYERS -> new StaffPlayersMenu(ctx, this).open(actor);
        }
    }

    void freeze(Player actor, Player target) {
        if (!actor.hasPermission(PERMISSION_FREEZE)) {
            reply(actor, "staff.no-permission");
            return;
        }
        if (actor.equals(target)) {
            reply(actor, "staff.freeze-self");
            return;
        }
        if (punishments == null) {
            try {
                if (!actor.performCommand("freeze " + target.getName())) {
                    reply(actor, "staff.freeze-unavailable");
                }
            } catch (RuntimeException e) {
                ctx.logger().error("Running /freeze for {} failed", actor.getName(), e);
                reply(actor, "staff.freeze-unavailable");
            }
            return;
        }
        punishments.active(target.getUniqueId()).thenCompose(records -> {
            boolean frozen = records.stream().anyMatch(record -> record.type() == PunishmentType.FREEZE
                    && record.isActive(java.time.Instant.now()));
            if (frozen) {
                return punishments.revoke(target.getUniqueId(), Set.of(PunishmentType.FREEZE),
                                actor.getUniqueId(), actor.getName(), false)
                        .thenAccept(result -> reportRevoke(actor, target, result));
            }
            PunishmentRequest request = new PunishmentRequest(PunishmentType.FREEZE, target.getUniqueId(),
                    target.getName(), null, actor.getUniqueId(), actor.getName(), "", null, false, null);
            return punishments.punish(request).thenAccept(result -> reportPunish(actor, target, result));
        }).exceptionally(error -> {
            ctx.logger().error("Freezing {} with the staff tool failed", target.getName(), error);
            reply(actor, "staff.freeze-failed");
            return null;
        });
    }

    private void reportPunish(Player actor, Player target, PunishResult result) {
        switch (result.status()) {
            case APPLIED -> reply(actor, "staff.freeze-applied", Msg.unparsed("player", target.getName()));
            case CANCELLED -> reply(actor, "staff.freeze-cancelled");
            case EXEMPT -> reply(actor, "staff.freeze-exempt", Msg.unparsed("player", target.getName()));
            default -> reply(actor, "staff.freeze-failed");
        }
    }

    private void reportRevoke(Player actor, Player target, RevokeResult result) {
        if (result.success()) {
            reply(actor, "staff.freeze-lifted", Msg.unparsed("player", target.getName()));
        } else {
            reply(actor, "staff.freeze-failed");
        }
    }

    void runCommand(Player actor, String command, Player target) {
        try {
            if (!actor.performCommand(command + " " + target.getName())) {
                reply(actor, "staff.inspect-unavailable");
            }
        } catch (RuntimeException e) {
            ctx.logger().error("Running /{} for {} failed", command, actor.getName(), e);
            reply(actor, "staff.inspect-unavailable");
        }
    }

    void enderchest(Player actor, Player target) {
        try {
            if (!actor.performCommand("enderchest " + target.getName())) {
                reply(actor, "staff.inspect-unavailable");
            }
        } catch (RuntimeException e) {
            ctx.logger().error("Running /enderchest for {} failed", actor.getName(), e);
            reply(actor, "staff.inspect-unavailable");
        }
    }

    void randomTeleport(Player actor) {
        List<Player> candidates = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(actor)) {
                continue;
            }
            if (vanish != null && vanish.isVanished(online.getUniqueId())) {
                continue;
            }
            candidates.add(online);
        }
        if (candidates.isEmpty()) {
            reply(actor, "staff.random-none");
            return;
        }
        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        ctx.scheduler().supplyOnEntity(target, target::getLocation)
                .thenAccept(location -> ctx.scheduler().entity(actor,
                        () -> actor.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                                .whenComplete((moved, error) -> {
                                    if (error != null || !Boolean.TRUE.equals(moved)) {
                                        ctx.logger().warn("Random staff teleport of {} failed", actor.getName(), error);
                                        reply(actor, "staff.random-failed");
                                        return;
                                    }
                                    reply(actor, "staff.random-teleported", Msg.unparsed("player", target.getName()));
                                }),
                        null))
                .exceptionally(error -> {
                    if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)) {
                        ctx.logger().warn("Resolving a random staff teleport target failed", error);
                    }
                    reply(actor, "staff.random-failed");
                    return null;
                });
    }

    void toggleVanish(Player actor) {
        if (vanish == null) {
            reply(actor, "staff.vanish-unavailable");
            return;
        }
        if (!actor.hasPermission("atrium.vanish")) {
            reply(actor, "staff.no-permission");
            return;
        }
        boolean next = !vanish.isVanished(actor.getUniqueId());
        vanish.setVanished(actor, next, actor).whenComplete((changed, error) -> {
            if (error != null) {
                ctx.logger().error("Toggling vanish of {} failed", actor.getName(), error);
                reply(actor, "staff.vanish-failed");
                return;
            }
            reply(actor, changed ? "staff.vanish-enabled" : "staff.vanish-disabled");
        });
    }

    // ---- helpers ----

    private void reply(@Nullable CommandSender sender, String key, TagResolver... tags) {
        if (sender == null) {
            return;
        }
        if (sender instanceof Player player) {
            tell(player, key, tags);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    private void tell(Player player, String key, TagResolver... tags) {
        runOn(player, () -> ctx.messages().send(player, key, tags), null);
    }
}
