package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.api.holograms.HologramService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/** One NPC display backend. Every method runs on the region thread that owns the NPC location. */
interface NpcBackend {
    NpcType type();

    /** True while the backing plugin (or the native API) is usable. */
    boolean available();

    /** Creates or adopts the display for this definition. */
    void spawn(NpcDef def);

    /** Removes the display; the stored definition is not touched. */
    void despawn(NpcDef def);

    /** Re-applies changed properties, respawning only when the backend cannot update in place. */
    void refresh(NpcDef def);

    /** Moves an already spawned display to the stored location. */
    void move(NpcDef def);

    /**
     * The Atrium NPC key for an entity owned by this backend, or {@code null}. Used by the exact
     * ray trace of {@code /npc select}; returning {@code null} simply leaves the cone fallback in charge.
     */
    default @Nullable String npcKey(Entity entity) {
        return null;
    }

    void shutdown();

    /** Shared nameplate hologram helper: the holograms module is an optional dependency. */
    final class Nameplates {
        private static final String PREFIX = "npc_";

        private final ModuleContext ctx;
        private final java.util.function.Supplier<NpcsConfig> config;
        private final java.util.Map<String, java.util.concurrent.CompletableFuture<?>> chains =
                new java.util.concurrent.ConcurrentHashMap<>();

        Nameplates(ModuleContext ctx, java.util.function.Supplier<NpcsConfig> config) {
            this.ctx = ctx;
            this.config = config;
        }

        private Optional<HologramService> service() {
            return ctx.services().get(HologramService.class);
        }

        /** True when a separate hologram is used (so the backend must hide its own name). */
        boolean active() {
            return config.get().nameplateHologram && service().isPresent();
        }

        /** Deterministic, valid hologram name for an NPC (max 32 characters, no collisions for realistic names). */
        static String hologramName(String npcName) {
            String safe = npcName.replaceAll("[^A-Za-z0-9_-]", "_");
            String shortName = safe.length() <= 20 ? safe : safe.substring(0, 20);
            int hash = npcName.toLowerCase(Locale.ROOT).hashCode() & 0xffffff;
            return PREFIX + shortName + "_" + String.format(Locale.ROOT, "%06x", hash);
        }

        void apply(NpcDef def, StoredLocation location) {
            String name = hologramName(def.name());
            chain(name, () -> {
                Optional<HologramService> service = service();
                if (service.isEmpty() || !config.get().nameplateHologram) {
                    return delete(service, name);
                }
                HologramService holograms = service.get();
                StoredLocation above = new StoredLocation(location.world(), location.x(),
                        location.y() + config.get().nameplateHeight, location.z(), location.yaw(), 0f);
                List<String> lines = List.of(def.displayName());
                if (holograms.get(name).isPresent()) {
                    return holograms.setPage(name, 1, lines).thenCombine(holograms.move(name, above), (a, b) -> null);
                }
                return holograms.create(name, above, lines).thenApply(result -> null);
            });
        }

        void remove(NpcDef def) {
            String name = hologramName(def.name());
            chain(name, () -> delete(service(), name));
        }

        private java.util.concurrent.CompletableFuture<Void> delete(Optional<HologramService> service, String name) {
            if (service.isEmpty() || service.get().get(name).isEmpty()) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            return service.get().delete(name).thenApply(result -> null);
        }

        private void chain(String name, java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> task) {
            chains.compute(name, (key, previous) -> {
                java.util.concurrent.CompletableFuture<Void> start = previous == null
                        ? java.util.concurrent.CompletableFuture.completedFuture(null)
                        : previous.handle((ignored, error) -> null).thenApply(ignored -> null);
                return start.thenCompose(ignored -> task.get()).whenComplete((ignored, error) -> report(name, error));
            });
        }

        private void report(String name, Throwable error) {
            if (error == null) {
                return;
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            ctx.logger().warn("Could not update nameplate hologram '{}': {}", name, cause.toString());
        }
    }
}
