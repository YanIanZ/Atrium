package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionResult.Status;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Publishes {@link ActionService}: a small, safe action language for admin-written command lists. Syntax is
 * documented on {@link ActionService} and in {@code modules/actions.yml}.
 */
public final class ActionsModule implements AtriumModule, ActionService {
    /** Key for sequences run without a player (console, command blocks). */
    private static final UUID NO_PLAYER = new UUID(0, 0);

    private ModuleContext ctx;
    private ConfigHandle<ActionsConfig> config;
    private final Map<UUID, Integer> running = new ConcurrentHashMap<>();
    private final Set<SequenceRunner> active = ConcurrentHashMap.newKeySet();
    private volatile boolean enabled;

    @Override
    public String id() {
        return "actions";
    }

    @Override
    public String description() {
        return "Action language for aliases, interactive commands and events";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("economy", "teleport", "warps");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(ActionsConfig.class, ActionsConfig::new);
        ctx.service(ActionService.class, this);
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
        for (SequenceRunner runner : List.copyOf(active)) {
            runner.abort(Status.FAILED, "the actions module was disabled");
        }
        active.clear();
        running.clear();
    }

    @Override
    public List<String> validate(List<String> lines) {
        return ActionParser.validate(lines);
    }

    @Override
    public CompletableFuture<ActionResult> run(List<String> lines, @Nullable Player player, Map<String, String> variables) {
        if (player == null) {
            return start(lines, null, org.bukkit.Bukkit.getConsoleSender(), variables);
        }
        return start(lines, player, player, variables);
    }

    @Override
    public CompletableFuture<ActionResult> runAs(CommandSender sender, List<String> lines, Map<String, String> variables) {
        if (sender instanceof Player player) {
            return start(lines, player, player, variables);
        }
        return start(lines, null, sender, variables);
    }

    private CompletableFuture<ActionResult> start(List<String> lines, @Nullable Player player, CommandSender audience,
            Map<String, String> variables) {
        if (!enabled) {
            return CompletableFuture.completedFuture(new ActionResult(Status.FAILED, 0, "the actions module is disabled"));
        }
        List<ActionLine> parsed;
        try {
            parsed = ActionParser.parseAll(List.copyOf(lines));
        } catch (ActionSyntaxException e) {
            ctx.logger().warn("Invalid action lines were not run: {}", e.getMessage());
            return CompletableFuture.completedFuture(new ActionResult(Status.INVALID, 0, e.getMessage()));
        }
        ActionsConfig cfg = config.get();
        int depth = SequenceRunner.currentDepth() + 1;
        if (depth > Math.max(1, cfg.maxNestingDepth)) {
            ctx.logger().warn("Action sequence not started: nesting deeper than {} (a sequence that starts itself?)",
                    cfg.maxNestingDepth);
            return CompletableFuture.completedFuture(new ActionResult(Status.LIMIT_REACHED, 0, "nesting limit reached"));
        }
        UUID owner = player == null ? NO_PLAYER : player.getUniqueId();
        int now = running.merge(owner, 1, Integer::sum);
        if (player != null && now > Math.max(1, cfg.maxRunningPerPlayer)) {
            release(owner);
            ctx.messages().send(player, "actions.too-many-running");
            return CompletableFuture.completedFuture(new ActionResult(Status.LIMIT_REACHED, 0, "too many running sequences"));
        }
        SequenceRunner runner = new SequenceRunner(parsed,
                new BukkitActionHost(ctx, cfg, player, audience, depth), variables, depth);
        active.add(runner);
        runner.result().whenComplete((result, error) -> {
            active.remove(runner);
            release(owner);
        });
        Scheduler scheduler = ctx.scheduler();
        BukkitActionHost.onOwner(scheduler, player, audience, runner::start).whenComplete((ignored, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (cause instanceof Scheduler.EntityRetiredException) {
                    runner.abort(Status.PLAYER_GONE, "player left before the sequence started");
                } else {
                    ctx.logger().error("Action sequence failed to start", cause);
                    runner.abort(Status.FAILED, "failed to start: " + cause.getMessage());
                }
            }
        });
        return runner.result();
    }

    private void release(UUID owner) {
        running.computeIfPresent(owner, (key, value) -> value <= 1 ? null : value - 1);
    }
}
