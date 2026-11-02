package dev.yanianz.atrium.api.world;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * One world manager in the {@link WorldProviderRegistry} chain: plain Bukkit worlds, SourbyCraft Aurora World
 * Fabric, Multiverse-Core, Advanced Slime Paper, or another plugin's own. Register it with
 * {@link WorldProviderRegistry#register} and remove it again when the owner stops.
 *
 * <p>Threads: every method may be called from any thread (command, tab completion, async). Nothing may block:
 * {@link #knownWorlds()} answers from memory (a provider whose listing needs I/O keeps a cached copy), and
 * {@link #load} returns at once and completes later. Loading must never run on a region thread; a provider
 * whose platform only loads on the main thread hops to the global scheduler itself.
 */
public interface WorldProvider {
    /** Id of Bukkit's already loaded worlds. */
    String BUKKIT = "bukkit";
    /** Id of SourbyCraft Aurora World Fabric worlds. */
    String AWF = "awf";
    /** Id of Multiverse-Core worlds. */
    String MULTIVERSE = "multiverse";
    /** Id of Advanced Slime Paper worlds. */
    String SLIME = "slime";

    /** Stable lower-case id ({@code bukkit}, {@code awf}, {@code multiverse}, {@code slime}, ...). */
    String id();

    /** Name shown to players and admins. */
    default String displayName() {
        return id();
    }

    /** Worlds this provider knows, loaded or loadable. Cheap, non-blocking, safe from any thread. */
    Collection<KnownWorld> knownWorlds();

    /**
     * The world name this provider uses for a name or alias: an exact name first, then a name ignoring case,
     * then an alias ignoring case. Empty when the provider does not know the world.
     */
    default Optional<String> canonicalName(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isEmpty()) {
            return Optional.empty();
        }
        Collection<KnownWorld> worlds = knownWorlds();
        for (KnownWorld world : worlds) {
            if (world.name().equals(nameOrAlias)) {
                return Optional.of(world.name());
            }
        }
        for (KnownWorld world : worlds) {
            if (world.name().equalsIgnoreCase(nameOrAlias)) {
                return Optional.of(world.name());
            }
        }
        for (KnownWorld world : worlds) {
            if (world.hasAlias() && world.alias().equalsIgnoreCase(nameOrAlias)) {
                return Optional.of(world.name());
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the world, loading it when it is known but not loaded. Never blocks; the future completes on no
     * particular thread. The status is {@link WorldResolver.Status#NOT_FOUND} for a world the provider does
     * not manage.
     */
    CompletableFuture<WorldResolver.Resolution> load(String world);

    /** The provider's spawn point of a loaded world (Multiverse spawn, AWF spawn, ...); empty = the world's own spawn. */
    default Optional<Location> spawn(World world) {
        return Optional.empty();
    }

    /** The provider's own entry rule for a player (for example Multiverse access permissions). */
    default boolean mayEnter(Player player, String world) {
        return true;
    }

    /** Whether this provider can unload that (loaded) world on request. */
    default boolean canUnload(String world) {
        return false;
    }

    /**
     * Unloads the world (saving it when the provider saves). Callers check that no player is in it first; the
     * provider checks again right before unloading. Never blocks; completes on no particular thread.
     */
    default CompletableFuture<UnloadResult> unload(String world) {
        return CompletableFuture.completedFuture(new UnloadResult(UnloadStatus.UNSUPPORTED, "not supported"));
    }

    /** Outcome of {@link #unload}. */
    enum UnloadStatus { UNLOADED, NOT_LOADED, PLAYERS_PRESENT, UNSUPPORTED, FAILED }

    record UnloadResult(UnloadStatus status, String detail) {
        public UnloadResult {
            detail = detail == null ? "" : detail;
        }
    }
}
