package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.StoredTeleportService;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.world.WorldResolver;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * {@link StoredTeleportService}: a stored destination whose world is not loaded is first resolved through
 * the {@link WorldResolver} service (SourbyCraft loads AWF/profile worlds on demand), then the normal
 * pipeline runs. Nothing is joined: the lookup continues with callbacks and messages go out on the
 * player's own thread.
 */
final class StoredTeleports implements StoredTeleportService {
    private final ModuleContext ctx;
    private final TeleportServiceImpl service;

    StoredTeleports(ModuleContext ctx, TeleportServiceImpl service) {
        this.ctx = ctx;
        this.service = service;
    }

    @Override
    public CompletableFuture<StoredTeleportResult> teleport(Player player, StoredLocation target, TeleportCause cause,
            TeleportOptions options) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Optional<Location> loaded = target.toLocation();
        if (loaded.isPresent()) {
            return service.teleport(player, loaded.get(), cause, options)
                    .thenApply(result -> new StoredTeleportResult(result, WorldResolver.Status.LOADED));
        }
        WorldResolver resolver = ctx.services().get(WorldResolver.class).orElseGet(WorldResolver::bukkit);
        CompletableFuture<WorldResolver.Resolution> lookup;
        try {
            lookup = resolver.lookup(target.world());
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        if (!lookup.isDone()) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, "teleport.world-loading",
                    Msg.unparsed("world", target.world())), null);
        }
        CompletableFuture<StoredTeleportResult> result = new CompletableFuture<>();
        lookup.whenComplete((resolution, error) -> {
            WorldResolver.Resolution outcome = resolution;
            if (error != null || outcome == null) {
                ctx.logger().error("Resolving world {} for the teleport of {} failed", target.world(), player.getName(), error);
                outcome = WorldResolver.Resolution.failed(target.world(), WorldResolver.Status.FAILED,
                        error == null ? "no result" : String.valueOf(error.getMessage()));
            }
            if (!outcome.available()) {
                WorldResolver.Status status = outcome.status();
                ctx.scheduler().entity(player, () -> ctx.messages().send(player, messageKey(status),
                        Msg.unparsed("world", target.world())), null);
                result.complete(new StoredTeleportResult(TeleportResult.FAILED, status));
                return;
            }
            WorldResolver.Status status = outcome.status();
            Location location = new Location(outcome.world(), target.x(), target.y(), target.z(), target.yaw(), target.pitch());
            service.teleport(player, location, cause, options).whenComplete((moved, moveError) -> {
                if (moveError != null) {
                    ctx.logger().error("Teleport of {} into {} failed", player.getName(), target.world(), moveError);
                }
                result.complete(new StoredTeleportResult(moveError != null || moved == null ? TeleportResult.FAILED : moved, status));
            });
        });
        return result;
    }

    /** Message sent to the player when the world is not available. */
    static String messageKey(WorldResolver.Status status) {
        return switch (status) {
            case LOADED, LOADED_ON_DEMAND, NOT_FOUND -> "teleport.world-not-loaded";
            default -> "teleport.world-unavailable-" + status.name().toLowerCase(Locale.ROOT).replace('_', '-');
        };
    }
}
