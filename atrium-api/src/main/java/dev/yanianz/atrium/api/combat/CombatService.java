package dev.yanianz.atrium.api.combat;

import java.time.Duration;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Combat tag state, published by the {@code combat} module.
 *
 * <p>All methods are safe to call from any thread. A tag is only really applied when the module option is
 * enabled, the player's world allows combat, the player is not vanished and the player does not bypass
 * combat. {@link #tag} therefore reports nothing: the tag is applied on the player's own thread and may be
 * refused by those rules.
 */
public interface CombatService {
    /** Whether the player currently carries a combat tag. */
    boolean isTagged(UUID player);

    /** Milliseconds left on the combat tag; {@code 0} when the player is not tagged. */
    long remainingMillis(UUID player);

    /** Extends (or starts) the combat tag of the player. A non-positive duration is ignored. */
    void tag(Player player, Duration duration);

    /** Removes the combat tag without a chat message. Called by the quit and death handling. */
    void clear(UUID player);
}
