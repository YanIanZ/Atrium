package dev.yanianz.atrium.api.player;

import java.util.Set;
import org.bukkit.entity.Player;

/**
 * Flight ownership by named claims, published by the {@code player} module.
 *
 * <p>Several features may need {@code allowFlight} at the same time (for example {@code /fly} and the
 * elytra double-jump launch). Each feature claims flight under its own owner id and releases only that
 * claim again. Flight stays allowed while at least one claim exists, or while the player is in creative
 * or spectator mode. Releasing a claim therefore never turns flight off while another owner still needs
 * it.
 *
 * <p>{@link #claim} and {@link #release} are safe to call from any thread: the actual flight state is
 * recomputed on the player's own thread. {@link #claims(Player)} only reads an immutable snapshot and is
 * safe from any thread.
 */
public interface FlightService {
    /**
     * Adds {@code owner} to the claims of {@code player} and recomputes flight on the player's thread.
     * Claiming twice with the same owner has no additional effect. A blank owner is rejected.
     */
    void claim(Player player, String owner);

    /**
     * Removes {@code owner} from the claims of {@code player} and recomputes flight on the player's
     * thread. Flight is only turned off when no other claim exists and the game mode does not require it.
     */
    void release(Player player, String owner);

    /** Snapshot of the current owners of the player's flight; empty when nobody claimed it. */
    Set<String> claims(Player player);
}
