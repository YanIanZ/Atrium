package dev.yanianz.atrium.module.player;

import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Claim-based flight ownership. Claims live in memory and are applied on the player's own thread; the
 * game mode is read again every time, so creative and spectator always keep flight even without a claim.
 */
final class FlightManager implements FlightService, Listener {
    /** Owner id used by {@code /fly}. */
    static final String OWNER_FLY = "player:fly";

    private final ModuleContext ctx;
    private final Map<UUID, Set<String>> owners = new ConcurrentHashMap<>();

    FlightManager(ModuleContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void claim(Player player, String owner) {
        String id = requireOwner(owner);
        onPlayerThread(player, () -> {
            owners.computeIfAbsent(player.getUniqueId(), ignored -> ConcurrentHashMap.newKeySet()).add(id);
            apply(player);
        });
    }

    @Override
    public void release(Player player, String owner) {
        String id = requireOwner(owner);
        onPlayerThread(player, () -> {
            UUID uuid = player.getUniqueId();
            Set<String> current = owners.get(uuid);
            if (current != null) {
                current.remove(id);
                if (current.isEmpty()) {
                    owners.remove(uuid, current);
                }
            }
            apply(player);
        });
    }

    @Override
    public Set<String> claims(Player player) {
        return claims(player.getUniqueId());
    }

    Set<String> claims(UUID player) {
        Set<String> current = owners.get(player);
        return current == null ? Set.of() : Set.copyOf(current);
    }

    boolean hasClaim(Player player, String owner) {
        Set<String> current = owners.get(player.getUniqueId());
        return current != null && current.contains(owner);
    }

    /** Recomputes the flight state. Player's thread only. */
    private void apply(Player player) {
        if (!player.isOnline()) {
            return;
        }
        boolean allow = FlightPolicy.allowFlight(claims(player), player.getGameMode());
        if (allow) {
            if (!player.getAllowFlight()) {
                player.setAllowFlight(true);
            }
            return;
        }
        // Stop flying before removing permission to avoid a "flying is not enabled" disconnect.
        if (player.isFlying()) {
            player.setFlying(false);
        }
        if (player.getAllowFlight()) {
            player.setAllowFlight(false);
        }
    }

    private void onPlayerThread(Player player, Runnable action) {
        Scheduler scheduler = ctx.scheduler();
        scheduler.supplyOnEntity(player, () -> {
            action.run();
            return null;
        }).exceptionally(error -> {
            if (!(error instanceof Scheduler.EntityRetiredException)) {
                ctx.logger().error("Applying flight state of {} failed", player.getName(), error);
            }
            return null;
        });
    }

    private static String requireOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isBlank()) {
            throw new IllegalArgumentException("Flight owner must not be blank");
        }
        return owner;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        // The server applies its own flight rules with the new mode; recompute once that is done.
        Player player = event.getPlayer();
        ctx.scheduler().entityLater(player, () -> apply(player), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        owners.remove(event.getPlayer().getUniqueId());
    }

    void clear() {
        owners.clear();
    }
}
