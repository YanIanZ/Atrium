package dev.yanianz.atrium.api.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Full-fidelity warp import, published by the {@code warps} module next to {@link WarpAdminService}. Unlike
 * {@link WarpAdminService#importWarp}, it keeps every field the warps module stores, and the world does
 * not have to be loaded.
 */
public interface WarpImportService {
    /**
     * One warp to import.
     *
     * @param category       category, or {@code null} for the configured default category
     * @param permission     custom per-warp permission node, or {@code null} for the node derived from the
     *                       name ({@code atrium.warp.<name>})
     * @param owner          owning player, or {@code null} for a server-owned warp
     * @param createdAt      epoch millis of the original creation, or {@code 0} for "now"
     * @param iconMaterial   Bukkit material name of the menu icon, or {@code null} for the default icon
     * @param iconModelData  custom model data of the icon, negative for none
     */
    record WarpImport(String name, StoredLocation location, @Nullable String category, @Nullable String permission,
            @Nullable UUID owner, long createdAt, @Nullable String iconMaterial, int iconModelData) {
        public WarpImport {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * Creates the warp, or replaces an existing one (including its owner, permission, creation time and
     * icon) when {@code overwrite} is true. Invalid names, categories, permissions or icons fail with
     * {@link IllegalArgumentException}.
     *
     * @return {@code true} when written, {@code false} when it exists and {@code overwrite} is false, or a
     *         {@link WarpCreateEvent} listener cancelled it
     */
    CompletableFuture<Boolean> importWarp(WarpImport warp, boolean overwrite);
}
