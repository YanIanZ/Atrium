package dev.yanianz.atrium.module.seating;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Collection;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Owns every seat entity. A seat is an invisible, non-persistent marker armor stand that carries a
 * persistent-data marker, so a crash leftover can be recognised and removed when its chunk loads.
 *
 * <p>All player-facing entry points may be called from any thread; methods that touch a player or a
 * seat are moved to that entity's own region thread. Seats are never ticked and no per-tick timer is
 * registered, so an idle server does no seating work at all.
 */
final class SeatManager {
    private final ModuleContext ctx;
    private final ConfigHandle<SeatingConfig> config;
    private final NamespacedKey seatKey;
    private final SeatIndex index = new SeatIndex();
    private volatile boolean closed;
    private volatile java.util.function.Consumer<Player> beforeSeat = player -> {};

    SeatManager(ModuleContext ctx, ConfigHandle<SeatingConfig> config) {
        this.ctx = ctx;
        this.config = config;
        this.seatKey = new NamespacedKey(ctx.plugin(), "seat");
    }

    /** Why a seat was removed. Only reasons where the player stays in the world fix a lower lay seat. */
    enum ExitReason {
        TOGGLE(true), DISMOUNT(true), QUIT(false), DEATH(false), TELEPORT(false), WORLD_CHANGE(false),
        BLOCK_BROKEN(true), ENTITY_REMOVED(true), DAMAGED(true), SNEAK(true), GAMEMODE(false),
        DISABLED(false), REPLACED(false), INVALID(true);

        private final boolean correctsPosition;

        ExitReason(boolean correctsPosition) {
            this.correctsPosition = correctsPosition;
        }

        boolean correctsPosition() {
            return correctsPosition;
        }
    }

    // ---- commands ----

    /** Toggles a seat, or switches between sit and lay when the other mode is already active. */
    void toggle(Player player, SeatIndex.Mode mode, CommandSender sender) {
        if (closed) {
            return;
        }
        SeatIndex.Entry current = index.entry(player.getUniqueId());
        if (current != null) {
            if (current.mode() == mode) {
                stand(player, ExitReason.TOGGLE, true);
                done(sender, player, stoppedKey(mode));
                return;
            }
            Placement next = switchPlacement(player, current, mode);
            if (next == null) {
                return; // switchPlacement() already reported why the other mode is unavailable
            }
            removeNow(player, ExitReason.REPLACED, false);
            if (place(player, next)) {
                done(sender, player, startedKey(mode));
            }
            return;
        }
        Placement placement = resolve(player, mode);
        if (placement == null) {
            return; // resolve() already reported the reason to the player
        }
        if (place(player, placement)) {
            done(sender, player, startedKey(mode));
        }
    }

    /** Click-to-sit. Returns true when the player sat down and the click should be cancelled. */
    boolean sitOnBlock(Player player, Block block, CommandSender sender) {
        if (closed) {
            return false;
        }
        SeatingConfig cfg = config.get();
        if (!cfg.sit || !cfg.interaction.enabled || worldDisabled(player, cfg)) {
            return false;
        }
        SeatRules.Allowed allowed = new SeatRules.Allowed(cfg.interaction.stairs, cfg.interaction.slabs,
                cfg.interaction.carpets);
        BlockData data = block.getBlockData();
        SeatRules.Kind kind = kindOf(block.getType());
        SeatRules.Verdict verdict = SeatRules.check(kind, topHalf(data), doubleSlab(data),
                headroom(block), allowed);
        if (verdict != SeatRules.Verdict.OK) {
            // Empty-hand clicks on stairs/slabs have no other use; a short explanation is helpful.
            fail(sender, verdictKey(verdict));
            return false;
        }
        if (index.isSeated(player.getUniqueId()) || player.isInsideVehicle()) {
            fail(sender, "already-riding");
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            fail(sender, "spectator");
            return false;
        }
        SeatRules.Offset offset = SeatRules.offset(kind, facing(data), shape(data));
        double surface = block.getY() + offset.y();
        Location location = new Location(block.getWorld(),
                block.getX() + offset.x(), surface + cfg.seat.heightOffset, block.getZ() + offset.z(),
                player.getYaw(), 0f);
        Placement placement = new Placement(location, SeatIndex.Mode.SIT, block.getWorld().getName(),
                block.getX(), block.getY(), block.getZ(), true, location.getY(), surface);
        if (!place(player, placement)) {
            return false;
        }
        done(sender, player, "sit-started");
        return true;
    }

    boolean isSeated(Player player) {
        return index.isSeated(player.getUniqueId());
    }

    /** Called before a seat is created so another pose (the crawl) can be ended first. */
    void beforeSeat(java.util.function.Consumer<Player> hook) {
        this.beforeSeat = hook;
    }

    // ---- removal ----

    /** Stands the player up on their own thread. Safe to call from any thread. */
    void stand(Player player, ExitReason reason, boolean correctPosition) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            removeNow(player, reason, correctPosition);
        } else {
            SeatIndex.Entry entry = index.removePlayer(player.getUniqueId());
            if (entry == null) {
                return;
            }
            // The entry is already gone, so no other handler can act twice on it.
            ctx.scheduler().entity(player, () -> {
                Entity vehicle = player.getVehicle();
                if (vehicle != null && vehicle.getUniqueId().equals(entry.seatId())) {
                    player.leaveVehicle();
                }
                Entity seat = Bukkit.getEntity(entry.seatId());
                if (seat != null && seat.isValid()) {
                    seat.remove();
                }
                if (correctPosition) {
                    correctLay(player, entry);
                }
            }, () -> removeSeatEntity(entry.seatId()));
        }
    }

    /** EntityDismountEvent, MONITOR and not cancelled. */
    void handleDismount(Player player, Entity vehicle) {
        SeatIndex.Entry entry = index.entry(player.getUniqueId());
        if (entry == null || !entry.seatId().equals(vehicle.getUniqueId())) {
            return;
        }
        index.removePlayer(player.getUniqueId());
        if (vehicle.isValid()) {
            vehicle.remove();
        }
        correctLay(player, entry);
    }

    /** EntityRemoveEvent for a seat that was removed without Atrium asking for it. */
    void handleSeatRemoved(Entity seat) {
        SeatIndex.Entry entry = index.removeSeat(seat.getUniqueId());
        if (entry == null) {
            return;
        }
        Player player = Bukkit.getPlayer(entry.playerId());
        if (player != null && player.isOnline()) {
            correctLay(player, entry);
        }
    }

    /** A block that several seats may stand on was broken, burned or moved. */
    void blockChanged(Block block) {
        if (index.size() == 0) {
            return;
        }
        String world = block.getWorld().getName();
        for (SeatIndex.Entry entry : index.at(world, block.getX(), block.getY(), block.getZ())) {
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player == null) {
                if (index.removePlayer(entry.playerId()) != null) {
                    removeSeatEntity(entry.seatId());
                }
                continue;
            }
            Runnable task = () -> {
                if (index.entry(player.getUniqueId()) != null) {
                    removeNow(player, ExitReason.BLOCK_BROKEN, true);
                }
            };
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                task.run();
            } else {
                ctx.scheduler().entity(player, task, () -> removeSeatEntity(entry.seatId()));
            }
        }
    }

    /** Players who leave or die must never leave a seat behind. */
    void clearPlayer(Player player, ExitReason reason) {
        stand(player, reason, reason.correctsPosition());
    }

    // ---- orphan sweep ----

    /**
     * EntitiesLoadEvent: a seat marker that is loaded from disk is always an orphan. Persistence is
     * disabled on every seat, so a normal server restart cannot produce one.
     */
    int sweepLoaded(Collection<Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (!isSeat(entity)) {
                continue;
            }
            SeatIndex.Entry entry = index.removeSeat(entity.getUniqueId());
            if (entity.isValid()) {
                entity.remove();
            }
            if (entry != null) {
                Player player = Bukkit.getPlayer(entry.playerId());
                if (player != null) {
                    correctLay(player, entry);
                }
            }
            removed++;
        }
        if (removed > 0) {
            ctx.logger().info("Removed {} orphan seat entities at chunk load", removed);
        }
        return removed;
    }

    /** /seating sweep: removes in-memory records whose player or seat entity is gone. */
    int sweepTracked() {
        int removed = 0;
        for (SeatIndex.Entry entry : index.all()) {
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player == null) {
                if (index.removePlayer(entry.playerId()) != null) {
                    removeSeatEntity(entry.seatId());
                    removed++;
                }
                continue;
            }
            Entity seat = Bukkit.getEntity(entry.seatId());
            if (seat != null && seat.isValid()) {
                continue;
            }
            if (index.removePlayer(entry.playerId()) != null) {
                removed++;
            }
            removeSeatEntity(entry.seatId());
        }
        return removed;
    }

    /** Periodic consistency check. Does nothing while nobody is seated. */
    void checkAll() {
        if (closed || index.size() == 0) {
            return;
        }
        for (SeatIndex.Entry entry : index.all()) {
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player == null) {
                if (index.removePlayer(entry.playerId()) != null) {
                    removeSeatEntity(entry.seatId());
                }
                continue;
            }
            Runnable verify = () -> verify(player, entry);
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                verify.run();
            } else {
                ctx.scheduler().entity(player, verify, () -> removeSeatEntity(entry.seatId()));
            }
        }
    }

    private void verify(Player player, SeatIndex.Entry expected) {
        SeatIndex.Entry current = index.entry(player.getUniqueId());
        if (current == null || !current.seatId().equals(expected.seatId())) {
            return;
        }
        Entity seat = Bukkit.getEntity(expected.seatId());
        Entity vehicle = player.getVehicle();
        if (seat == null || !seat.isValid()
                || vehicle == null || !vehicle.getUniqueId().equals(expected.seatId())) {
            removeNow(player, ExitReason.INVALID, true);
        }
    }

    /** Removes every seat. Called when the module is disabled or reloaded. */
    void shutdown() {
        closed = true;
        for (SeatIndex.Entry entry : index.clear()) {
            Player player = Bukkit.getPlayer(entry.playerId());
            if (player != null) {
                Runnable task = () -> {
                    Entity vehicle = player.getVehicle();
                    if (vehicle != null && vehicle.getUniqueId().equals(entry.seatId())) {
                        player.leaveVehicle();
                    }
                    Entity seat = Bukkit.getEntity(entry.seatId());
                    if (seat != null && seat.isValid()) {
                        seat.remove();
                    }
                };
                if (Bukkit.isOwnedByCurrentRegion(player)) {
                    task.run();
                } else {
                    ctx.scheduler().entity(player, task, null);
                }
            } else {
                removeSeatEntity(entry.seatId());
            }
        }
    }

    boolean isSeat(@Nullable Entity entity) {
        return entity instanceof ArmorStand
                && entity.getPersistentDataContainer().has(seatKey, PersistentDataType.BYTE);
    }

    // ---- placement ----

    private record Placement(Location location, SeatIndex.Mode mode, String world,
            int blockX, int blockY, int blockZ, boolean blockSeat, double seatY, double exitY) {}

    /** Resolves a command placement, reporting the reason to the player when it cannot. */
    @Nullable
    private Placement resolve(Player player, SeatIndex.Mode mode) {
        SeatingConfig cfg = config.get();
        if (mode == SeatIndex.Mode.SIT && !cfg.sit || mode == SeatIndex.Mode.LAY && !cfg.lay) {
            fail(player, "feature-disabled");
            return null;
        }
        if (worldDisabled(player, cfg)) {
            fail(player, "world-disabled");
            return null;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            fail(player, "spectator");
            return null;
        }
        if (player.isInsideVehicle()) {
            fail(player, "already-riding");
            return null;
        }
        Location feet = player.getLocation();
        Block feetBlock = feet.getBlock();
        BlockData feetData = feetBlock.getBlockData();
        SeatRules.Kind kind = kindOf(feetBlock.getType());
        // /sit is not limited by the click-to-sit material toggles: those only guard interactions.
        SeatRules.Allowed allowed = new SeatRules.Allowed(true, true, true);
        if (kind != null) {
            // The player is already standing here, so headroom above the block is not required.
            SeatRules.Verdict verdict = SeatRules.check(kind, topHalf(feetData), doubleSlab(feetData),
                    true, allowed);
            if (verdict == SeatRules.Verdict.OK) {
                SeatRules.Offset offset = SeatRules.offset(kind, facing(feetData), shape(feetData));
                double surface = feetBlock.getY() + offset.y();
                Location seat = new Location(feet.getWorld(), feetBlock.getX() + offset.x(),
                        surface + cfg.seat.heightOffset - depth(cfg, mode), feetBlock.getZ(),
                        player.getYaw(), 0f);
                return new Placement(seat, mode, feet.getWorld().getName(), feetBlock.getX(),
                        feetBlock.getY(), feetBlock.getZ(), true, seat.getY(), surface);
            }
        }
        // No usable seat block: /sit works anywhere on solid ground, like a chair pulled out of thin air.
        if (!feetBlock.isPassable()) {
            fail(player, "cannot-sit-here");
            return null;
        }
        if (!onSolidGround(player) && !player.isInWater() && !player.isSwimming()) {
            fail(player, "cannot-sit-here");
            return null;
        }
        double surface = feet.getY();
        Location seat = feet.clone();
        seat.setY(surface + cfg.seat.heightOffset - depth(cfg, mode));
        seat.setPitch(0f);
        return new Placement(seat, mode, feet.getWorld().getName(), feetBlock.getX(), feetBlock.getY(),
                feetBlock.getZ(), false, seat.getY(), surface);
    }

    /** Keeps the surface and block of the current seat when switching between /sit and /lay. */
    @Nullable
    private Placement switchPlacement(Player player, SeatIndex.Entry current, SeatIndex.Mode mode) {
        SeatingConfig cfg = config.get();
        if (mode == SeatIndex.Mode.SIT && !cfg.sit || mode == SeatIndex.Mode.LAY && !cfg.lay) {
            fail(player, "feature-disabled");
            return null;
        }
        if (worldDisabled(player, cfg)) {
            fail(player, "world-disabled");
            return null;
        }
        Location base = player.getLocation();
        double surface = current.exitY();
        Location seat = new Location(base.getWorld(), base.getX(),
                surface + cfg.seat.heightOffset - depth(cfg, mode), base.getZ(), player.getYaw(), 0f);
        return new Placement(seat, mode, current.world(), current.blockX(), current.blockY(),
                current.blockZ(), current.blockSeat(), seat.getY(), surface);
    }

    private boolean place(Player player, Placement placement) {
        if (closed) {
            return false;
        }
        // Sitting and crawling use conflicting poses; end the crawl before mounting.
        beforeSeat.accept(player);
        if (index.isSeated(player.getUniqueId())) {
            // Never leak a previous seat if a caller raced a command with an interaction.
            removeNow(player, ExitReason.REPLACED, false);
        }
        Location location = placement.location().clone();
        location.setYaw(player.getYaw());
        location.setPitch(0f);
        ArmorStand seat;
        try {
            seat = location.getWorld().spawn(location, ArmorStand.class, stand -> configure(stand, player));
        } catch (RuntimeException e) {
            ctx.logger().error("Could not spawn a seat for {} at {}", player.getName(), location, e);
            fail(player, "cannot-sit-here");
            return false;
        }
        if (seat == null) {
            // Another plugin cancelled the spawn (entity limit or protection plugin).
            fail(player, "cannot-sit-here");
            return false;
        }
        boolean mounted;
        try {
            mounted = seat.addPassenger(player);
        } catch (RuntimeException e) {
            ctx.logger().error("Could not mount {} on a seat at {}", player.getName(), location, e);
            seat.remove();
            fail(player, "cannot-sit-here");
            return false;
        }
        if (!mounted) {
            seat.remove();
            fail(player, "cannot-sit-here");
            return false;
        }
        index.put(new SeatIndex.Entry(player.getUniqueId(), seat.getUniqueId(), placement.mode(),
                placement.world(), placement.blockX(), placement.blockY(), placement.blockZ(),
                placement.blockSeat(), placement.seatY(), placement.exitY()));
        return true;
    }

    private void configure(ArmorStand stand, Player player) {
        stand.setMarker(true);
        stand.setInvisible(true);
        stand.setSmall(true);
        stand.setArms(false);
        stand.setBasePlate(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setPersistent(false);
        stand.setSilent(true);
        stand.setCollidable(false);
        stand.setCanMove(false);
        stand.setCustomNameVisible(false);
        stand.setRotation(player.getYaw(), 0f);
        stand.getPersistentDataContainer().set(seatKey, PersistentDataType.BYTE, (byte) 1);
    }

    private SeatIndex.Entry removeNow(Player player, ExitReason reason, boolean correctPosition) {
        SeatIndex.Entry entry = index.removePlayer(player.getUniqueId());
        if (entry == null) {
            return null;
        }
        Entity vehicle = player.getVehicle();
        if (vehicle != null && vehicle.getUniqueId().equals(entry.seatId())) {
            player.leaveVehicle();
            // The dismount event sees an empty index, so it cannot remove the seat a second time.
        }
        // verify() can end a seat from another region; the direct remove() would throw and leave an
        // invisible stand behind, so always use the region-safe path.
        removeSeatEntity(entry.seatId());
        if (correctPosition) {
            correctLay(player, entry);
        }
        return entry;
    }

    /**
     * /lay is a lower seat. Move the player back to the real surface after standing up, but never
     * interfere with a teleport that is already moving them somewhere else.
     */
    private void correctLay(Player player, SeatIndex.Entry entry) {
        if (entry.mode() != SeatIndex.Mode.LAY || !player.isOnline()) {
            return;
        }
        ctx.scheduler().entityLater(player, () -> {
            if (closed || !player.isOnline() || player.isInsideVehicle()) {
                return;
            }
            if (!player.getWorld().getName().equals(entry.world())) {
                return;
            }
            Location location = player.getLocation();
            if (location.getY() + 0.05 < entry.exitY()) {
                location.setY(entry.exitY());
                player.teleportAsync(location).exceptionally(error -> {
                    ctx.logger().warn("Could not move {} back to the seat surface", player.getName(), error);
                    return false;
                });
            }
        }, null, 1);
    }

    private void removeSeatEntity(UUID seatId) {
        Entity seat = Bukkit.getEntity(seatId);
        if (seat == null || !seat.isValid()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(seat)) {
            seat.remove();
        } else {
            ctx.scheduler().entity(seat, seat::remove, null);
        }
    }

    // ---- helpers ----

    private static double depth(SeatingConfig cfg, SeatIndex.Mode mode) {
        return mode == SeatIndex.Mode.LAY ? Math.clamp(cfg.seat.layDepth, 0.0, 1.0) : 0.0;
    }

    private static boolean onSolidGround(Player player) {
        return player.getLocation().subtract(0.0, 0.1, 0.0).getBlock().getType().isSolid();
    }

    private static boolean headroom(Block block) {
        Block above = block.getRelative(BlockFace.UP);
        return above.isPassable() && !above.getType().isSolid();
    }

    static SeatRules.Kind kindOf(Material material) {
        if (Tag.STAIRS.isTagged(material)) {
            return SeatRules.Kind.STAIRS;
        }
        if (Tag.SLABS.isTagged(material)) {
            return SeatRules.Kind.SLAB;
        }
        if (Tag.WOOL_CARPETS.isTagged(material)
                || material == Material.MOSS_CARPET || material == Material.PALE_MOSS_CARPET) {
            return SeatRules.Kind.CARPET;
        }
        return null;
    }

    private static boolean topHalf(BlockData data) {
        if (data instanceof Bisected bisected) {
            return bisected.getHalf() == Bisected.Half.TOP;
        }
        return false;
    }

    private static boolean doubleSlab(BlockData data) {
        return data instanceof Slab slab && slab.getType() == Slab.Type.DOUBLE;
    }

    private static SeatRules.Facing facing(BlockData data) {
        if (data instanceof Stairs stairs) {
            return switch (stairs.getFacing()) {
                case NORTH -> SeatRules.Facing.NORTH;
                case EAST -> SeatRules.Facing.EAST;
                case SOUTH -> SeatRules.Facing.SOUTH;
                case WEST -> SeatRules.Facing.WEST;
                default -> SeatRules.Facing.SOUTH;
            };
        }
        return SeatRules.Facing.SOUTH;
    }

    private static SeatRules.Shape shape(BlockData data) {
        if (data instanceof Stairs stairs) {
            return switch (stairs.getShape()) {
                case INNER_LEFT -> SeatRules.Shape.INNER_LEFT;
                case INNER_RIGHT -> SeatRules.Shape.INNER_RIGHT;
                case OUTER_LEFT -> SeatRules.Shape.OUTER_LEFT;
                case OUTER_RIGHT -> SeatRules.Shape.OUTER_RIGHT;
                default -> SeatRules.Shape.STRAIGHT;
            };
        }
        return SeatRules.Shape.STRAIGHT;
    }

    private static String verdictKey(SeatRules.Verdict verdict) {
        return switch (verdict) {
            case OK -> "cannot-sit-here";
            case NOT_A_SEAT -> "not-a-seat";
            case TOP_HALF -> "top-half";
            case DISABLED -> "feature-disabled";
            case NO_HEADROOM -> "no-headroom";
        };
    }

    private static String startedKey(SeatIndex.Mode mode) {
        return mode == SeatIndex.Mode.LAY ? "lay-started" : "sit-started";
    }

    private static String stoppedKey(SeatIndex.Mode mode) {
        return mode == SeatIndex.Mode.LAY ? "lay-stopped" : "sit-stopped";
    }

    static boolean worldDisabled(Player player, SeatingConfig cfg) {
        if (player.hasPermission(SeatingModule.PERM_WORLD_BYPASS) || cfg.disabledWorlds == null) {
            return false;
        }
        String world = player.getWorld().getName();
        for (String disabled : cfg.disabledWorlds) {
            if (disabled != null && disabled.trim().equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    private void done(CommandSender sender, Player player, String key, TagResolver... tags) {
        send(player, key, tags);
        if (!sender.equals(player)) {
            send(sender, key + "-other", TagResolver.resolver(
                    Msg.unparsed("player", player.getName()), TagResolver.resolver(tags)));
        }
    }

    private void fail(CommandSender recipient, String key, TagResolver... tags) {
        send(recipient, key, tags);
    }

    private void send(CommandSender recipient, String key, TagResolver... tags) {
        String fullKey = "seating." + key;
        if (recipient instanceof Player player) {
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                ctx.messages().send(player, fullKey, tags);
            } else {
                ctx.scheduler().entity(player, () -> ctx.messages().send(player, fullKey, tags), null);
            }
        } else {
            ctx.messages().send(recipient, fullKey, tags);
        }
    }
}
