package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.jspecify.annotations.Nullable;

/** Enforcement: login bans, mutes, jail confinement, freeze. */
final class ModerationListener implements Listener {
    private static final Set<PunishmentType> HARD_MUTES = EnumSet.of(PunishmentType.MUTE, PunishmentType.TEMPMUTE);
    private static final Set<PlayerTeleportEvent.TeleportCause> STAFF_TELEPORTS =
            EnumSet.of(PlayerTeleportEvent.TeleportCause.PLUGIN, PlayerTeleportEvent.TeleportCause.COMMAND);
    private static final long NOTICE_INTERVAL_MS = 1500;

    private final ModuleContext ctx;
    private final ConfigHandle<ModerationConfig> config;
    private final PunishmentManager manager;
    private final ActiveCache cache;
    private final Messages messages;
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    ModerationListener(ModuleContext ctx, ConfigHandle<ModerationConfig> config, PunishmentManager manager) {
        this.ctx = ctx;
        this.config = config;
        this.manager = manager;
        this.cache = manager.cache();
        this.messages = ctx.messages();
    }

    // ---- login ----

    /**
     * LOWEST: the ban check runs before the core {@code UserManager} (priority HIGH) loads player data. A banned
     * player is refused here, and UserManager skips loading because the result is no longer ALLOWED, so no data
     * is loaded (and left pending) for players who will never join. If an earlier plugin already refused the
     * login there is nothing to check.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            Optional<Component> screen = manager.checkLogin(event.getUniqueId(), event.getName(), event.getAddress(),
                    ctx.coreConfig().userLoadTimeoutSeconds);
            screen.ifPresent(s -> event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, s));
        } catch (Exception e) {
            if (config.get().refuseLoginWhenCheckFails) {
                ctx.logger().error("Ban check for {} ({}) failed; refusing login", event.getName(), event.getUniqueId(), e);
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, manager.loginCheckFailedScreen());
            } else {
                ctx.logger().error("Ban check for {} ({}) failed; letting the player in (refuseLoginWhenCheckFails=false)",
                        event.getName(), event.getUniqueId(), e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        manager.store().recordPlayer(player.getUniqueId(), player.getName(),
                        PunishmentManager.address(player).orElse(null), player.hasPermission(PunishmentManager.EXEMPT))
                .exceptionally(t -> {
                    ctx.logger().warn("Could not record address of {}", player.getName(), t);
                    return null;
                });
        // The marker survives a logout while jailed; without one there is no sentence to release.
        manager.store().hasReleaseLocation(player.getUniqueId()).handle((marker, error) -> {
            if (error != null) {
                ctx.logger().warn("Could not read the jail marker of {}", player.getName(), error);
                return false;
            }
            return marker;
        }).thenAccept(marker -> ctx.scheduler().entity(player, () -> joined(player, marker), null));
    }

    /** Runs on the player's thread once the jail marker is known. */
    private void joined(Player player, boolean marker) {
        if (!player.isOnline()) {
            return;
        }
        Instant now = Instant.now();
        if (releaseIfExpired(player, now, marker)) {
            return;
        }
        cache.active(player.getUniqueId(), PunishmentManager.JAIL, now).ifPresent(jail -> {
            Optional<Jail> area = manager.jailOf(jail);
            if (area.isPresent() && !area.get().contains(player.getLocation())) {
                manager.sendToJail(player, jail);
            } else if (!marker) {
                // Already inside the area (or the world is unloaded): still remember that a release is due.
                manager.markRelease(player);
            }
            messages.send(player, "moderation.target.still-jailed", manager.details(jail, player));
        });
        cache.active(player.getUniqueId(), PunishmentManager.FREEZE, now)
                .ifPresent(freeze -> messages.send(player, "moderation.target.frozen", manager.details(freeze, player)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cache.evict(event.getPlayer().getUniqueId());
        lastNotice.remove(event.getPlayer().getUniqueId());
    }

    // ---- mute ----

    /** LOWEST so a hard-muted message is stopped before formatting or relaying plugins do any work. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Optional<Punishment> mute = cache.active(player.getUniqueId(), HARD_MUTES, Instant.now());
        if (mute.isPresent()) {
            event.setCancelled(true);
            messages.send(player, "moderation.muted", manager.details(mute.get(), player));
            return;
        }
        Optional<Punishment> jail = cache.active(player.getUniqueId(), PunishmentManager.JAIL, Instant.now());
        if (jail.isPresent() && !manager.jailAllowsChat(jail.get())) {
            event.setCancelled(true);
            messages.send(player, "moderation.jail.chat-blocked", manager.details(jail.get(), player));
        }
    }

    /**
     * Shadow mute at HIGHEST: the message is rendered with the final renderer (so the sender sees exactly the
     * normal format), shown to the sender only, and the event is cancelled. Cancelling, rather than only clearing
     * the viewers, keeps MONITOR listeners with ignoreCancelled (proxy relays, Discord bridges, web maps) from
     * forwarding the message anywhere else.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShadowChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!manager.isShadowMuted(player.getUniqueId())) {
            return;
        }
        Component rendered = event.renderer().render(player, player.displayName(), event.message(), player);
        event.setCancelled(true);
        player.sendMessage(rendered);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String line = event.getMessage();
        Instant now = Instant.now();
        ModerationConfig cfg = config.get();
        Optional<Punishment> mute = cache.active(player.getUniqueId(), PunishmentType.MUTES, now);
        if (mute.isPresent() && CommandMatcher.matches(line, cfg.mute.blockedCommands)) {
            event.setCancelled(true);
            if (mute.get().type() != PunishmentType.SHADOWMUTE) {
                messages.send(player, "moderation.muted", manager.details(mute.get(), player));
            }
            return;
        }
        releaseIfExpired(player, now);
        Optional<Punishment> jail = cache.active(player.getUniqueId(), PunishmentManager.JAIL, now);
        if (jail.isPresent() && CommandMatcher.blocked(line, cfg.jail.blockedCommands,
                manager.jailAllowedCommands(jail.get()))) {
            event.setCancelled(true);
            messages.send(player, "moderation.jail.command-blocked", manager.details(jail.get(), player));
            return;
        }
        Optional<Punishment> freeze = cache.active(player.getUniqueId(), PunishmentManager.FREEZE, now);
        if (freeze.isPresent() && CommandMatcher.blocked(line, cfg.freeze.blockedCommands, cfg.freeze.allowedCommands)) {
            event.setCancelled(true);
            messages.send(player, "moderation.freeze.command-blocked", manager.details(freeze.get(), player));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (!config.get().mute.blockSigns) {
            return;
        }
        boolean hasText = event.lines().stream().anyMatch(line -> line != null && !line.equals(Component.empty()));
        if (hasText) {
            blockMuted(event.getPlayer(), () -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        if (config.get().mute.blockBooks) {
            blockMuted(event.getPlayer(), () -> event.setCancelled(true));
        }
    }

    private void blockMuted(Player player, Runnable cancel) {
        Optional<Punishment> mute = cache.active(player.getUniqueId(), PunishmentType.MUTES, Instant.now());
        if (mute.isEmpty()) {
            return;
        }
        cancel.run();
        if (mute.get().type() != PunishmentType.SHADOWMUTE) {
            messages.send(player, "moderation.muted", manager.details(mute.get(), player));
        }
    }

    // ---- jail & freeze: movement ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        Instant now = Instant.now();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (cache.active(player.getUniqueId(), PunishmentManager.FREEZE, now).isPresent()) {
            boolean horizontal = from.getX() != to.getX() || from.getZ() != to.getZ();
            if (horizontal || to.getY() > from.getY()) {
                Location back = from.clone();
                back.setYaw(to.getYaw());
                back.setPitch(to.getPitch());
                if (to.getY() < from.getY()) {
                    back.setY(to.getY()); // falling is allowed; hovering would get the player kicked for flying
                }
                event.setTo(back);
                notice(player, "moderation.freeze.notice");
            }
            return;
        }
        if (!event.hasChangedBlock()) {
            return;
        }
        if (releaseIfExpired(player, now)) {
            return;
        }
        Optional<Punishment> jail = cache.active(player.getUniqueId(), PunishmentManager.JAIL, now);
        if (jail.isEmpty()) {
            return;
        }
        Optional<Jail> area = manager.jailOf(jail.get());
        if (area.isEmpty() || area.get().contains(to)) {
            return;
        }
        if (area.get().contains(from)) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        } else {
            manager.sendToJail(player, jail.get());
        }
        notice(player, "moderation.jail.notice");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Instant now = Instant.now();
        if (cache.active(player.getUniqueId(), PunishmentManager.FREEZE, now).isPresent()
                && !STAFF_TELEPORTS.contains(event.getCause())) {
            event.setCancelled(true);
            return;
        }
        Optional<Punishment> jail = cache.active(player.getUniqueId(), PunishmentManager.JAIL, now);
        if (jail.isPresent()) {
            Optional<Jail> area = manager.jailOf(jail.get());
            if (area.isPresent() && !area.get().contains(event.getTo())) {
                event.setCancelled(true);
                notice(player, "moderation.jail.notice");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        cache.active(player.getUniqueId(), PunishmentManager.JAIL, Instant.now())
                .flatMap(manager::jailOf)
                .map(Jail::location)
                .ifPresent(event::setRespawnLocation);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && cannotRide(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player && cannotRide(player)) {
            event.setCancelled(true);
        }
    }

    private boolean cannotRide(Player player) {
        Instant now = Instant.now();
        UUID id = player.getUniqueId();
        return cache.active(id, PunishmentManager.FREEZE, now).isPresent()
                || (config.get().jail.blockVehicles && cache.active(id, PunishmentManager.JAIL, now).isPresent());
    }

    // ---- freeze: interaction ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (frozen(event.getPlayer())) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (frozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        if (damager instanceof Player player && frozen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && config.get().freeze.invulnerable && frozen(player)) {
            event.setCancelled(true);
        }
    }

    // ---- helpers ----

    private boolean frozen(Player player) {
        return cache.active(player.getUniqueId(), PunishmentManager.FREEZE, Instant.now()).isPresent();
    }

    /**
     * Handles a jail that ran out while the player was online or offline: forgets it once, tells the player and
     * releases them. Expiry is only detected here (by time comparison); nothing polls for it.
     */
    private boolean releaseIfExpired(Player player, Instant now) {
        return releaseIfExpired(player, now, false);
    }

    /**
     * Handles a jail that ran out while the player was offline: forgets it once, tells the player and
     * releases them. Expiry is only detected by comparing times; nothing polls for it.
     */
    private boolean releaseIfExpired(Player player, Instant now, boolean marker) {
        JailRules.Decision decision = JailRules.decide(cache.records(player.getUniqueId()), now, marker);
        if (!decision.release()) {
            if (!decision.expired().isEmpty()) {
                cache.remove(player.getUniqueId(), decision.expired()); // drop stale rows lazily
            }
            return false;
        }
        String jailName = cache.records(player.getUniqueId()).stream()
                .filter(p -> p.type() == PunishmentType.JAIL)
                .map(Punishment::context)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        cache.remove(player.getUniqueId(), decision.expired());
        messages.send(player, "moderation.target.jail-expired");
        manager.release(player, jailName);
        return true;
    }

    private void notice(Player player, String key) {
        long now = System.currentTimeMillis();
        @Nullable Long last = lastNotice.get(player.getUniqueId());
        if (last == null || now - last >= NOTICE_INTERVAL_MS) {
            lastNotice.put(player.getUniqueId(), now);
            messages.actionBar(player, key);
        }
    }
}
