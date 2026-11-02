package dev.yanianz.atrium.module.worldrules;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.worldrules.BlockCycleEvent;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;

/** {@code /blockcycle}: cycles the looked-at block's state on the block's region thread. */
final class BlockCycleCommand {
    private static final String PERMISSION = "atrium.blockcycle";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<WorldRulesConfig> config;

    BlockCycleCommand(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("blockcycle", PERMISSION)
                .executes(this::cycle)
                .build(), "Cycle the block state you are looking at"));
    }

    private int cycle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> look(player), null);
        return Cmds.OK;
    }

    /** Runs on the player's region thread. */
    private void look(Player player) {
        if (!player.isOnline()) {
            return;
        }
        Location eye = player.getEyeLocation();
        int reach = config.get().blockCycleReach();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), reach,
                FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) {
            Reply.send(ctx, player, "worldrules.blockcycle-none");
            return;
        }
        Block block = hit.getHitBlock();
        BlockFace face = hit.getHitBlockFace();
        if (!Bukkit.isOwnedByCurrentRegion(block)) {
            // Reading the player or firing a BlockPlaceEvent from another region's thread is unsafe.
            Reply.send(ctx, player, "worldrules.blockcycle-not-owned");
            return;
        }
        // Snapshot everything that belongs to the player before switching to the block's state.
        Location eyeSnapshot = eye.clone();
        ItemStack hand = player.getInventory().getItemInMainHand().clone();
        apply(player, block, face, eyeSnapshot, hand);
    }

    /** Runs on the player's region thread, which also owns the block. */
    private void apply(Player player, Block block, BlockFace face, Location eye, ItemStack hand) {
        if (!player.isOnline() || !block.getWorld().equals(player.getWorld())
                || block.getLocation().distanceSquared(eye)
                        > (config.get().blockCycleReach() + 2.0) * (config.get().blockCycleReach() + 2.0)) {
            Reply.send(ctx, player, "worldrules.blockcycle-none");
            return;
        }
        BlockData old = block.getBlockData();
        BlockData next = BlockCycles.next(old);
        if (next == null || next.matches(old)) {
            Reply.send(ctx, player, "worldrules.blockcycle-unsupported");
            return;
        }

        BlockCycleEvent event = new BlockCycleEvent(player, block, old.clone(), next.clone());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            Reply.send(ctx, player, "worldrules.blockcycle-cancelled");
            return;
        }
        if (config.get().blockCycleProtectionCheck && !protectionAllows(player, block, face, hand)) {
            Reply.send(ctx, player, "worldrules.blockcycle-cancelled");
            return;
        }

        try {
            block.setBlockData(next, true);
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot cycle block {} at {}", block.getType(), block.getLocation(), e);
            Reply.send(ctx, player, "worldrules.blockcycle-failed");
            return;
        }
        Reply.send(ctx, player, "worldrules.blockcycle-done", Msg.unparsed("block", block.getType().getKey().getKey()));
    }

    /**
     * Fires Bukkit's placement event so claim/region plugins can deny the change. This is the block
     * placement API (not a synthetic break event, which would trigger drops, payouts and logs). The item
     * is a snapshot taken on the player's thread.
     */
    private static boolean protectionAllows(Player player, Block block, BlockFace face, ItemStack hand) {
        Block against = block.getRelative(face == null ? BlockFace.UP : face);
        var place = new BlockPlaceEvent(block, block.getState(), against, hand.clone(), player, true,
                EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(place);
        return !place.isCancelled();
    }
}
