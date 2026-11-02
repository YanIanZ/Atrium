package dev.yanianz.atrium.api.warps;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.jspecify.annotations.Nullable;

/**
 * Administrative write access to warps, published by the {@code warps} module.
 *
 * <p>It deliberately bypasses player warp limits and command permissions: it exists for import and
 * maintenance tools, not for player-facing features. The normal {@code /setwarp} command keeps using
 * its own permission checks.
 *
 * <p>The future completes only after the write was stored. A cancelled {@link WarpCreateEvent}, an
 * existing warp with {@code overwrite == false} or a rejected name/location never stores anything.
 */
public interface WarpAdminService {
    /**
     * Creates a warp, or replaces the location and category of an existing one.
     *
     * @param name       warp name; validated with the warps module's own rules
     * @param location   destination; the world must be loaded
     * @param category   category, or {@code null} for the configured default category
     * @param permission per-warp permission node, or {@code null}. The current warps module derives
     *                   {@code atrium.warp.<name>} from the name, so a different value is only a hint
     *                   for other plugins and is not stored yet.
     * @param creator    owning player, or {@code null} for a server-owned warp
     * @param overwrite  replace an existing warp instead of leaving it untouched
     * @return {@code true} when the warp was written, {@code false} when it already exists and
     *         {@code overwrite} is false (or the create event cancelled the write). Invalid names,
     *         categories and locations fail with {@link IllegalArgumentException}.
     */
    CompletableFuture<Boolean> importWarp(String name, Location location, @Nullable String category,
            @Nullable String permission, @Nullable UUID creator, boolean overwrite);
}
