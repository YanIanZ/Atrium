package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Creates the optional providers. Each provider class referencing another plugin's API is touched only from
 * the method for that plugin, which the module calls only after the plugin/API was found, so a missing plugin
 * never loads a missing type.
 */
final class Hooks {
    private Hooks() {}

    /** Multiverse-Core 5 provider. */
    static WorldProvider multiverse(ModuleContext ctx, ConfigHandle<WorldsConfig> config) {
        return new MultiverseWorldProvider(ctx.scheduler(),
                () -> config.get().multiverse.respectAccessPermissions, ctx.logger());
    }

    /** Advanced Slime Paper provider with its list refresh timer, or null (logged) when the loader cannot start. */
    static @Nullable WorldProvider slime(ModuleContext ctx, WorldsConfig.Slime cfg) {
        SlimeWorldProvider provider;
        try {
            Path root = ctx.plugin().getServer().getWorldContainer().toPath();
            provider = SlimeWorldProvider.create(cfg, root, ctx.scheduler(), ctx.logger());
        } catch (Exception | LinkageError e) {
            ctx.logger().warn("Advanced Slime Paper found, but the slime provider cannot start: {}", e.getMessage(), e);
            return null;
        }
        long seconds = Math.max(10, Math.min(3600, cfg.listRefreshSeconds));
        provider.refresh();
        ctx.track(ctx.scheduler().asyncTimer(provider::refresh, Duration.ofSeconds(seconds), Duration.ofSeconds(seconds)));
        ctx.logger().info("Advanced Slime Paper found: slime worlds from loader '{}' can be listed and loaded.",
                provider.loaderName());
        return provider;
    }
}
