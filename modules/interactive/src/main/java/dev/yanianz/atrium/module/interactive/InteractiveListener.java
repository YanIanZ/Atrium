package dev.yanianz.atrium.module.interactive;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Click handling. All interact events fire on the player's thread, which owns the clicked block/entity's region,
 * so the clicked target can be read here directly.
 *
 * <p>Events are handled even when another plugin cancelled them (protected spawn areas cancel interactions), but
 * a binding never un-cancels anything. When a binding has cancel-event, the click is cancelled also when the
 * player is rate-limited, lacks permission or is on cooldown.
 */
final class InteractiveListener implements Listener {
    private final InteractiveModule module;
    private final ModuleContext ctx;
    private final ConfigHandle<InteractiveConfig> config;
    private final ActionService actions;
    /** Last trigger per player (epoch millis). Bounded by online players: entries are removed on quit. */
    private final Map<UUID, Long> lastTrigger = new ConcurrentHashMap<>();

    InteractiveListener(InteractiveModule module, ModuleContext ctx, ConfigHandle<InteractiveConfig> config, ActionService actions) {
        this.module = module;
        this.ctx = ctx;
        this.config = config;
        this.actions = actions;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action == Action.PHYSICAL) {
            Block block = event.getClickedBlock();
            if (block != null) {
                module.registry.atBlock(BlockKey.of(block)).ifPresent(command ->
                        trigger(player, command, InteractiveCommand.Click.PHYSICAL, event, blockVariables(block)));
            }
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // the off-hand event of the same click
        }
        InteractiveCommand.Click click = action.isLeftClick() ? InteractiveCommand.Click.LEFT : InteractiveCommand.Click.RIGHT;
        Block block = event.getClickedBlock();
        if (block != null && (action == Action.LEFT_CLICK_BLOCK || action == Action.RIGHT_CLICK_BLOCK)) {
            if (selection(player, InteractiveCommand.TargetKind.BLOCK, event, BlockKey.of(block).encode(), BlockKey.of(block).toString())) {
                return;
            }
            Optional<InteractiveCommand> bound = module.registry.atBlock(BlockKey.of(block));
            if (bound.isPresent()) {
                trigger(player, bound.get(), click, event, blockVariables(block));
                return;
            }
        }
        module.itemTag(event.getItem()).flatMap(module.registry::get).ifPresent(command -> {
            Map<String, String> vars = block != null ? blockVariables(block) : new HashMap<>();
            trigger(player, command, click, event, vars);
        });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        // Also receives nothing from PlayerInteractAtEntityEvent (own handler list), see below.
        if (event.getHand() == EquipmentSlot.HAND) {
            entityClick(event.getPlayer(), event.getRightClicked(), InteractiveCommand.Click.RIGHT, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        // Armor stands are right-clicked through this event; the rate limit absorbs a following plain interact.
        if (event.getHand() == EquipmentSlot.HAND) {
            entityClick(event.getPlayer(), event.getRightClicked(), InteractiveCommand.Click.RIGHT, event);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        entityClick(event.getPlayer(), event.getAttacked(), InteractiveCommand.Click.LEFT, event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastTrigger.remove(event.getPlayer().getUniqueId());
        module.selections.remove(event.getPlayer().getUniqueId());
    }

    private void entityClick(Player player, Entity entity, InteractiveCommand.Click click, Cancellable event) {
        String description = entity.getType().getKey().getKey() + " " + entity.getUniqueId();
        if (selection(player, InteractiveCommand.TargetKind.ENTITY, event, entity.getUniqueId().toString(), description)) {
            return;
        }
        module.registry.forEntity(entity.getUniqueId()).ifPresent(command -> {
            Map<String, String> vars = locationVariables(entity.getLocation());
            vars.put("target_uuid", entity.getUniqueId().toString());
            vars.put("target_type", entity.getType().getKey().getKey());
            trigger(player, command, click, event, vars);
        });
    }

    /** Handles a pending /ic bind or /ic unbind click. Returns true when the click was consumed. */
    private boolean selection(Player player, InteractiveCommand.TargetKind kind, Cancellable event, String target, String description) {
        InteractiveModule.Selection selection = module.selections.get(player.getUniqueId());
        if (selection == null || selection.kind() != kind) {
            return false;
        }
        if (selection.expiresAt() < System.currentTimeMillis()) {
            module.selections.remove(player.getUniqueId(), selection);
            return false;
        }
        module.selections.remove(player.getUniqueId(), selection);
        event.setCancelled(true);
        if (!player.hasPermission(InteractiveModule.PERM_ADMIN)) {
            return true;
        }
        module.completeSelection(player, selection, target, description);
        return true;
    }

    private void trigger(Player player, InteractiveCommand command, InteractiveCommand.Click click, Cancellable event,
            Map<String, String> vars) {
        if (!command.trigger().matches(click)) {
            return;
        }
        if (command.cancelEvent()) {
            event.setCancelled(true);
        }
        long now = System.currentTimeMillis();
        long interval = Math.max(0, config.get().clickIntervalMillis);
        Long previous = lastTrigger.get(player.getUniqueId());
        if (previous != null && now - previous < interval) {
            return;
        }
        lastTrigger.put(player.getUniqueId(), now);
        if (!command.permission().isEmpty() && !player.hasPermission(command.permission())) {
            ctx.messages().send(player, "interactive.no-permission");
            return;
        }
        if (command.actions().isEmpty()) {
            return;
        }
        if (command.cooldown().isPositive()) {
            Optional<AtriumUser> user = ctx.users().cached(player.getUniqueId());
            if (user.isEmpty()) {
                return; // data not loaded yet: fail closed
            }
            Duration left = ctx.cooldowns().tryAcquire(user.get(), "interactive:" + command.key(), command.cooldown());
            if (left.isPositive()) {
                ctx.messages().send(player, "interactive.on-cooldown", Msg.duration("time", left));
                return;
            }
        }
        vars.put("ic", command.name());
        vars.put("click", click.name().toLowerCase(Locale.ROOT));
        actions.run(command.actions(), player, vars).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Interactive command {} failed for {}", command.name(), player.getName(), error);
            } else if (result.status() == ActionResult.Status.INVALID) {
                ctx.logger().warn("Interactive command {} has invalid actions: {}", command.name(), result.detail());
            }
        });
    }

    private static Map<String, String> blockVariables(Block block) {
        Map<String, String> vars = locationVariables(block.getLocation());
        vars.put("target_type", block.getType().getKey().getKey());
        return vars;
    }

    private static Map<String, String> locationVariables(Location location) {
        Map<String, String> vars = new HashMap<>();
        vars.put("target_world", location.getWorld() == null ? "" : location.getWorld().getName());
        vars.put("target_x", Integer.toString(location.getBlockX()));
        vars.put("target_y", Integer.toString(location.getBlockY()));
        vars.put("target_z", Integer.toString(location.getBlockZ()));
        return vars;
    }
}
