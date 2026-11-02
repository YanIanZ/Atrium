package dev.yanianz.atrium.module.warps;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Live random-location decks, one per player and warp. The deck is transient (like a cooldown reminder): a
 * relog or restart starts a fresh shuffle, which can never lose anything. The state is stored per player, so
 * two players walking the same warp do not consume each other's locations.
 */
final class WarpDecks implements Listener {
    private final Map<UUID, Map<String, RandomDeck.State>> decks = new ConcurrentHashMap<>();
    private final Supplier<WarpsConfig> config;

    WarpDecks(Supplier<WarpsConfig> config) {
        this.config = config;
    }

    /**
     * Picks the next location index for this player and warp. {@code size} is the number of locations in the
     * pool (the warp's own location plus its extra ones) and must be at least 1.
     */
    int choose(Player player, String warpKey, int size) {
        if (size <= 1) {
            return 0;
        }
        boolean repeating = config.get().repeatingRandomLocations();
        int[] chosen = {0};
        decks.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>())
                .compute(warpKey, (key, state) -> {
                    RandomDeck.Pick pick = RandomDeck.next(state, size, repeating, ThreadLocalRandom.current());
                    chosen[0] = pick.index();
                    return pick.state();
                });
        return chosen[0];
    }

    /** Forgets every deck of a player who left; nothing persisted needs cleaning. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        decks.remove(event.getPlayer().getUniqueId());
    }
}
