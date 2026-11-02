package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Runs the click actions of an NPC in order. Atrium directives (dialog, warp, server) are handled here;
 * every other line goes to the actions module, which validates command safety itself.
 */
final class NpcActions {
    private final ModuleContext ctx;
    private final java.util.function.Supplier<NpcsConfig> config;

    NpcActions(ModuleContext ctx, java.util.function.Supplier<NpcsConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    /** First problem with a line, or empty when it can be stored and run. */
    Optional<String> problem(String raw) {
        NpcActionLine line = NpcActionLine.parse(raw);
        Optional<String> structural = line.problem();
        if (structural.isPresent()) {
            return structural;
        }
        if (line.directive()) {
            return Optional.empty();
        }
        Optional<ActionService> actions = ctx.services().get(ActionService.class);
        if (actions.isEmpty()) {
            return Optional.of("the actions module is not enabled");
        }
        List<String> errors = actions.get().validate(List.of(line.raw()));
        return errors.isEmpty() ? Optional.empty() : Optional.of(errors.getFirst());
    }

    /** Runs the actions on the player's thread; the future completes when the sequence ended. */
    CompletableFuture<Void> run(NpcDef def, Player player, NpcInteractClick click) {
        List<String> lines = def.actions(click);
        if (lines.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String raw : lines) {
            chain = chain.thenCompose(ignored -> runLine(def, player, raw));
        }
        return chain.whenComplete((ignored, error) -> {
            if (error != null && config.get().logActionProblems) {
                ctx.logger().warn("NPC '{}': a {} click action failed: {}", def.name(), click.configName(),
                        rootMessage(error));
            }
        });
    }

    private CompletableFuture<Void> runLine(NpcDef def, Player player, String raw) {
        NpcActionLine line = NpcActionLine.parse(raw);
        if (line.raw().isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return switch (line.type()) {
            case DIALOG -> onPlayer(player, () -> player.performCommand("dialog open " + line.value()));
            case WARP -> warp(player, line.value());
            case SERVER -> server(player, line.value());
            case ACTION -> action(player, def, line.raw());
        };
    }

    private CompletableFuture<Void> onPlayer(Player player, Runnable task) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        ctx.scheduler().entity(player, () -> {
            try {
                task.run();
                done.complete(null);
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            }
        }, () -> done.completeExceptionally(new IllegalStateException("player left")));
        return done;
    }

    private CompletableFuture<Void> warp(Player player, String warp) {
        Optional<WarpService> warps = ctx.services().get(WarpService.class);
        Optional<ExtendedTeleportService> teleports = ctx.services().get(ExtendedTeleportService.class);
        if (warps.isEmpty() || teleports.isEmpty()) {
            if (config.get().logActionProblems) {
                ctx.logger().warn("NPC warp action '{}' skipped: the warps or teleport module is not enabled", warp);
            }
            return CompletableFuture.completedFuture(null);
        }
        Optional<Location> target = warps.get().location(warp).flatMap(dev.yanianz.atrium.api.teleport.StoredLocation::toLocation);
        if (target.isEmpty()) {
            if (config.get().logActionProblems) {
                ctx.logger().warn("NPC warp action '{}' skipped: no such warp", warp);
            }
            return CompletableFuture.completedFuture(null);
        }
        return onPlayer(player, () -> {})
                .thenCompose(ignored -> teleports.get().teleport(player, target.get(), TeleportCause.WARP))
                .thenApply(result -> {
                    if (result != TeleportResult.SUCCESS && config.get().logActionProblems) {
                        ctx.logger().info("NPC warp action '{}' for {} ended with {}", warp, player.getName(), result);
                    }
                    return null;
                });
    }

    private CompletableFuture<Void> server(Player player, String server) {
        Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
        if (proxy.isEmpty()) {
            if (config.get().logActionProblems) {
                ctx.logger().warn("NPC server action '{}' skipped: the proxy module is not enabled", server);
            }
            return CompletableFuture.completedFuture(null);
        }
        return onPlayer(player, () -> {
            if (!proxy.get().connect(player, server) && config.get().logActionProblems) {
                ctx.logger().warn("NPC server action '{}' was refused for {}", server, player.getName());
            }
        });
    }

    private CompletableFuture<Void> action(Player player, NpcDef def, String raw) {
        Optional<ActionService> actions = ctx.services().get(ActionService.class);
        if (actions.isEmpty()) {
            if (config.get().logActionProblems) {
                ctx.logger().warn("NPC '{}': action '{}' skipped because the actions module is not enabled",
                        def.name(), raw);
            }
            return CompletableFuture.completedFuture(null);
        }
        return actions.get().run(List.of(raw), player, Map.of("npc", def.name())).thenApply(result -> {
            if (!result.success() && config.get().logActionProblems) {
                ctx.logger().info("NPC '{}': action '{}' for {} ended with {}{}", def.name(), raw,
                        player.getName(), result.status(), result.detail().isBlank() ? "" : " (" + result.detail() + ")");
            }
            return null;
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.toString();
    }
}
