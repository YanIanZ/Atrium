package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * First-class support for SourbyCraft (region-threaded Paper fork): AWF MultiProfile (profile-scoped Atrium
 * data through one {@code ProfileStateAdapter}, active profile for placeholders and {@code /atrium profile},
 * economy mode check), Aurora World Fabric (worlds loaded on demand for homes, warps and /back; async unload
 * notification) and SourbyCraft's region metrics in /lag.
 *
 * <p>Isolation: this class references no SourbyCraft type. The hook classes ({@code ProfileHook},
 * {@code WorldHook}, {@code UnloadHook}, {@code MetricsHook} and what they use) are loaded only after their
 * API classes were found, and each of them checks that its service is registered. On plain Paper/Folia the
 * module logs once and stays idle. Atrium needs no Aurora Compatibility Bridge: it is region-aware
 * ({@code folia-supported: true}).
 */
public final class SourbyCraftModule implements AtriumModule {
    static final String PROFILES_API = "dev.iyanz.sourbycraft.api.profile.AuroraProfiles";
    static final String WORLDS_API = "dev.iyanz.sourbycraft.api.world.AuroraWorlds";
    static final String UNLOAD_EVENT_API = "dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent";
    static final String METRICS_API = "dev.iyanz.sourbycraft.api.metrics.SourbyMetrics";

    private ModuleContext ctx;
    private ConfigHandle<SourbyCraftConfig> config;
    private volatile List<String> scopes = List.of();
    private volatile ProfileScopes.MissingState missingState = ProfileScopes.MissingState.EMPTY;
    private volatile AwfWorldResolver.Settings worldSettings =
            new AwfWorldResolver.Settings(true, Duration.ofSeconds(30), Set.of());
    private volatile String economyMode = "not checked yet";
    private AutoCloseable profileHook;
    private final List<String> active = new ArrayList<>();

    @Override
    public String id() {
        return "sourbycraft";
    }

    @Override
    public String description() {
        return "SourbyCraft support: AWF MultiProfile, on-demand AWF worlds, region metrics (idle elsewhere)";
    }

    @Override
    public Set<String> softDependencies() {
        // Providers of profile-scoped data and the services consumed here enable first when present.
        return Set.of("homes", "kits", "teleport", "economy");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(SourbyCraftConfig.class, SourbyCraftConfig::new);
        applyConfig();
        active.clear();

        boolean profilesApi = present(PROFILES_API);
        boolean worldsApi = present(WORLDS_API);
        boolean unloadApi = present(UNLOAD_EVENT_API);
        boolean metricsApi = present(METRICS_API);
        if (!profilesApi && !worldsApi && !unloadApi && !metricsApi) {
            context.logger().info("SourbyCraft API not found (plain Paper/Folia): the sourbycraft module stays idle.");
            return;
        }
        SourbyCraftConfig cfg = config.get();
        if (profilesApi) {
            profileHook = ProfileHook.start(this);
            if (profileHook != null) {
                active.add("multiprofile");
            }
        }
        if (worldsApi && WorldHook.start(this)) {
            active.add("awf-worlds");
        }
        if (unloadApi && cfg.worldUnloadNotify) {
            UnloadHook.start(this);
            active.add("async-unload");
        }
        if (metricsApi && cfg.regionMetrics && MetricsHook.start(this)) {
            active.add("region-metrics");
        }
        if (active.isEmpty()) {
            context.logger().info("SourbyCraft API present but no SourbyCraft service is registered; the sourbycraft module stays idle.");
        } else {
            context.logger().info("SourbyCraft integration active: {}", String.join(", ", active));
        }
    }

    @Override
    public void reload() {
        applyConfig();
    }

    @Override
    public void disable() {
        AutoCloseable hook = profileHook;
        profileHook = null;
        if (hook != null) {
            try {
                hook.close();
            } catch (Exception e) {
                ctx.logger().warn("Stopping the SourbyCraft profile integration failed", e);
            }
        }
        active.clear();
    }

    private void applyConfig() {
        SourbyCraftConfig cfg = config.get();
        scopes = ProfileScopes.parse(cfg.profileScoped, warning -> ctx.logger().warn("sourbycraft.yml {}", warning));
        missingState = ProfileScopes.missingState(cfg.profileMissingState,
                warning -> ctx.logger().warn("sourbycraft.yml {}", warning));
        int timeout = Math.max(5, Math.min(120, cfg.worldsLoadTimeoutSeconds));
        worldSettings = new AwfWorldResolver.Settings(cfg.worldsLoadOnDemand, Duration.ofSeconds(timeout),
                Set.copyOf(new HashSet<>(cfg.worldsOnDemandDeny == null ? List.of() : cfg.worldsOnDemandDeny)));
    }

    /** Whether a class is visible to Atrium, without initialising it. */
    static boolean present(String className) {
        try {
            Class.forName(className, false, SourbyCraftModule.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    // ---- for the hook classes ----

    ModuleContext context() {
        return ctx;
    }

    SourbyCraftConfig config() {
        return config.get();
    }

    List<String> scopes() {
        return scopes;
    }

    ProfileScopes.MissingState missingState() {
        return missingState;
    }

    AwfWorldResolver.Settings worldSettings() {
        return worldSettings;
    }

    String economyMode() {
        return economyMode;
    }

    void economyMode(String mode) {
        this.economyMode = mode;
    }
}
