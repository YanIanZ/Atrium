package dev.yanianz.atrium.api.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;

public interface UserService {
    /** The loaded user of an online player. Online users are always loaded. */
    AtriumUser user(Player player);

    /** A user that is currently loaded (online, or recently loaded offline). */
    Optional<AtriumUser> cached(UUID uuid);

    /** Loads a user from storage if needed. Completes with empty when the player never joined. */
    CompletableFuture<Optional<AtriumUser>> load(UUID uuid);

    /** Resolves by last known name (case-insensitive) and loads. */
    CompletableFuture<Optional<AtriumUser>> loadByName(String name);

    Collection<AtriumUser> online();

    /** Registers a component; values are loaded for online users and on every later load. */
    <T> void registerComponent(UserComponentType<T> type);

    /**
     * Saves every changed component of a loaded user now. Completes when all writes finished, or
     * exceptionally if one failed (the failed component stays dirty and is retried later). Use it to
     * confirm a change to the player only after it was stored.
     */
    CompletableFuture<Void> saveNow(UUID uuid);

    /**
     * Resolves a name typed in a command to an account: online player, then known users, then (when
     * {@code includeNeverJoined}) the server's profile lookup (Mojang in online mode, offline UUID otherwise).
     */
    CompletableFuture<Optional<PlayerRef>> resolveAccount(String name, boolean includeNeverJoined);

    /** Accounts whose last login came from this address (alt detection, /seen). */
    CompletableFuture<List<PlayerRef>> accountsByAddress(String address);

    /** Every account Atrium knows (uuid + last name), without loading them; at most {@code limit}. */
    Collection<PlayerRef> knownAccounts(int limit);

    /** Names of known players starting with the prefix, for command suggestions. */
    Collection<String> knownNames(String prefix, int limit);
}
