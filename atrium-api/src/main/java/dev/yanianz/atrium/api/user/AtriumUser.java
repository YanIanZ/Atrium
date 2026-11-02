package dev.yanianz.atrium.api.user;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.entity.Player;

/** A player known to Atrium, online or loaded offline. Thread-safe. */
public interface AtriumUser {
    UUID uuid();

    /** Last known name. */
    String name();

    Instant firstJoin();

    Instant lastSeen();

    /** Locale used for messages; the client locale while online. */
    Locale locale();

    Optional<Player> player();

    /** Address of the last login (normalised textual IP), if known. */
    Optional<String> lastAddress();

    default boolean isOnline() {
        return player().isPresent();
    }

    /** Current value of a registered component. Throws if the component type is not registered. */
    <T> T get(UserComponentType<T> type);

    /** Atomically replaces a component value and schedules it for saving. Returns the new value. */
    <T> T update(UserComponentType<T> type, UnaryOperator<T> change);
}
