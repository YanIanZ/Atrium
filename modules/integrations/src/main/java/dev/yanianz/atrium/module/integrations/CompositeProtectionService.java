package dev.yanianz.atrium.module.integrations;

import dev.yanianz.atrium.api.protection.ProtectionService;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Composite protection check: every enabled hook must allow. A hook that throws is treated as a denial:
 * a broken protection plugin must never silently open a claim.
 */
final class CompositeProtectionService implements ProtectionService {
    static final String BYPASS_PERMISSION = "atrium.protection.bypass";

    private final List<ProtectionHook> hooks = new CopyOnWriteArrayList<>();
    private final Predicate<Player> bypass;
    private final Supplier<IntegrationsConfig> config;
    private final Logger logger;

    CompositeProtectionService(Predicate<Player> bypass, Supplier<IntegrationsConfig> config, Logger logger) {
        this.bypass = bypass;
        this.config = config;
        this.logger = logger;
    }

    void addHook(ProtectionHook hook) {
        if (hooks.stream().noneMatch(existing -> existing.id().equals(hook.id()))) {
            hooks.add(hook);
        }
    }

    List<ProtectionHook> hooks() {
        return List.copyOf(hooks);
    }

    ProtectionHook hook(String id) {
        return hooks.stream().filter(hook -> hook.id().equals(id)).findFirst().orElse(null);
    }

    @Override
    public boolean canBuild(Player player, Location location) {
        if (!config.get().checkBuild) {
            return true;
        }
        return ask(player, "build", hook -> hook.canBuild(player, location));
    }

    @Override
    public boolean canBreak(Player player, Block block) {
        if (!config.get().checkBreak) {
            return true;
        }
        return ask(player, "break", hook -> hook.canBreak(player, block));
    }

    @Override
    public boolean canInteract(Player player, Location location) {
        if (!config.get().checkInteract) {
            return true;
        }
        return ask(player, "interact", hook -> hook.canInteract(player, location));
    }

    @Override
    public boolean canUseCommandAt(Player player, Location location, String command) {
        if (!config.get().checkCommands) {
            return true;
        }
        return ask(player, "command", hook -> hook.canUseCommandAt(player, location, command));
    }

    private boolean ask(Player player, String action, HookTest test) {
        if (player != null && bypass.test(player)) {
            return true;
        }
        boolean allowed = true;
        // Ask every hook instead of stopping at the first denial: this keeps the result deterministic and
        // makes a broken hook visible even when another hook already denied the action.
        for (ProtectionHook hook : hooks) {
            try {
                if (!test.test(hook)) {
                    allowed = false;
                }
            } catch (RuntimeException | LinkageError e) {
                logger.warn("Protection hook '{}' failed while checking {}; denying", hook.id(), action, e);
                allowed = false;
            }
        }
        return allowed;
    }

    @FunctionalInterface
    private interface HookTest {
        boolean test(ProtectionHook hook);
    }
}
