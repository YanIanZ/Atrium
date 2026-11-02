package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Worlds the server already loaded. Knows nothing it cannot see, so it never loads anything. */
final class BukkitWorldProvider implements WorldProvider {
    @Override
    public String id() {
        return BUKKIT;
    }

    @Override
    public String displayName() {
        return "Bukkit";
    }

    @Override
    public List<KnownWorld> knownWorlds() {
        List<KnownWorld> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            result.add(new KnownWorld(world.getName(), BUKKIT, true, "", world.getEnvironment()));
        }
        return result;
    }

    @Override
    public CompletableFuture<WorldResolver.Resolution> load(String world) {
        World loaded = Bukkit.getWorld(world);
        return CompletableFuture.completedFuture(loaded != null
                ? WorldResolver.Resolution.loaded(loaded)
                : WorldResolver.Resolution.failed(world, WorldResolver.Status.NOT_FOUND, "not loaded"));
    }
}
