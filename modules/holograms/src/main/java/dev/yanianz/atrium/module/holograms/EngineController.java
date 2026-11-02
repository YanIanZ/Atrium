package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Chooses and owns the active engine. The engine is selected once at enable; {@code /holo engine migrate}
 * can move the live mirrors to another engine and switch to it, but the configuration stays authoritative
 * for the next start (the command tells the admin which value to write).
 */
final class EngineController {
    record Migration(int count, boolean sourceCleaned) {}

    private final ModuleContext ctx;
    private final Supplier<HologramsConfig> config;
    private final TextRenderer renderer;
    private final NativeEngine nativeEngine;
    private final Map<EngineKind, HologramEngine> engines = new EnumMap<>(EngineKind.class);
    private final Set<HologramEngine> listened = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Supplier<Collection<Hologram>> current;
    /** Guards {@link #active} swaps against {@link #apply}, and the migration buffer. */
    private final Object switchLock = new Object();
    private final MigrationBuffer pending = new MigrationBuffer();
    private volatile boolean migrating;
    private boolean pluginWatcherRegistered;
    private volatile @Nullable HologramEngine active;
    private volatile EngineKind activeKind = EngineKind.NATIVE;
    private volatile EngineKind configured = EngineKind.AUTO;
    private volatile String fallbackReason = "";

    EngineController(ModuleContext ctx, Supplier<HologramsConfig> config, Displays displays, ViewerTracker tracker,
            TextRenderer renderer, Supplier<Collection<Hologram>> current) {
        this.ctx = ctx;
        this.config = config;
        this.renderer = renderer;
        this.current = current;
        this.nativeEngine = new NativeEngine(displays, tracker);
        this.engines.put(EngineKind.NATIVE, nativeEngine);
    }

    // ---- lifecycle ----

    void start(Collection<Hologram> holograms) {
        configured = readConfigured();
        fallbackReason = "";
        EngineSelection.Selection selection = selection(configured);
        HologramEngine engine = null;
        if (selection.engine() != EngineKind.NATIVE) {
            try {
                engine = engine(selection.engine());
            } catch (Throwable error) {
                fallbackReason = "start-failed";
                ctx.logger().warn("Hologram engine '{}' could not start ({}); using native", selection.engine().id,
                        error.getMessage());
            }
        }
        if (engine == null) {
            engine = nativeEngine;
            if (selection.fallback()) {
                fallbackReason = selection.reason();
                ctx.logger().warn("Hologram engine '{}' is unavailable ({}); using native", configured.id,
                        selection.reason());
            }
        }
        try {
            engine.start(holograms);
        } catch (Throwable error) {
            ctx.logger().warn("Hologram engine '{}' failed to start; using native", engine.id(), error);
            safeStop(engine);
            engine = nativeEngine;
            fallbackReason = "start-failed";
            engine.start(holograms);
        }
        synchronized (switchLock) {
            active = engine;
            activeKind = engine.kind();
        }
        ensureListeners(engine);
        if (!pluginWatcherRegistered) {
            pluginWatcherRegistered = true;
            ctx.listen(new PluginWatcher());
        }
        ctx.logger().info("Hologram engine: {} (configured: {})", activeKind.id, configured.id);
    }

    void reload(Collection<Hologram> holograms) {
        HologramEngine engine = active;
        if (engine != null) {
            engine.refreshAll(holograms);
        }
        EngineKind wanted = readConfigured();
        if (wanted != configured) {
            configured = wanted;
            ctx.logger().warn("holograms.yml: the new engine '{}' is used after the next restart; "
                    + "use /holo engine migrate to move the live mirrors now", wanted.id);
        }
    }

    /**
     * Applies one stored change to the active engine (called by the storage manager, any thread). While a
     * migration runs the change is buffered and replayed on the engine that is active after the switch.
     */
    void apply(@Nullable Hologram before, @Nullable Hologram after) {
        synchronized (switchLock) {
            if (migrating) {
                pending.record(before, after);
                return;
            }
            HologramEngine engine = active;
            if (engine != null) {
                engine.apply(before, after);
            }
        }
    }

    /** True while {@code /holo engine migrate} runs; edits are then applied after the switch. */
    boolean migrating() {
        return migrating;
    }

    void stop() {
        HologramEngine engine = active;
        if (engine != null) {
            try {
                engine.stop();
            } catch (RuntimeException error) {
                ctx.logger().warn("Hologram engine {} did not stop cleanly: {}", engine.id(), error.getMessage());
            }
        }
    }

    // ---- commands ----

    EngineKind activeKind() {
        return activeKind;
    }

    String activeId() {
        return activeKind.id;
    }

    String configuredId() {
        return configured.id;
    }

    EngineCapabilities capabilities() {
        HologramEngine engine = active;
        return engine == null ? EngineCapabilities.nativeEngine() : engine.capabilities();
    }

    /** Empty when the configured engine was used directly. */
    String fallbackReason() {
        return fallbackReason;
    }

    List<String> availableIds() {
        List<String> ids = new ArrayList<>();
        ids.add(EngineKind.NATIVE.id);
        if (decentReady()) {
            ids.add(EngineKind.DECENTHOLOGRAMS.id);
        }
        if (pluginEnabled("FancyHolograms")) {
            ids.add(EngineKind.FANCYHOLOGRAMS.id);
        }
        return List.copyOf(ids);
    }

    /**
     * Recreates every stored hologram on {@code to} and removes Atrium's mirrors from {@code from}. Must run
     * on the global thread; the command wraps it. The holograms are read here, at the start of the switch;
     * changes stored while it runs are buffered and applied to the engine that is active afterwards.
     */
    Migration migrate(EngineKind from, EngineKind to) {
        if (migrating) {
            throw new EngineException("migration-running");
        }
        EngineKind sourceKind = from == EngineKind.AUTO ? activeKind : from;
        EngineKind targetKind = to == EngineKind.AUTO ? selection(EngineKind.AUTO).engine() : to;
        if (sourceKind == targetKind) {
            throw new EngineException("same-engine");
        }
        HologramEngine source = null;
        if (sourceKind == activeKind) {
            source = active;
        } else {
            try {
                source = engine(sourceKind);
            } catch (RuntimeException error) {
                ctx.logger().warn("Migration source engine '{}' is unavailable ({}); its mirrors cannot be removed",
                        sourceKind.id, error.getMessage());
            }
        }
        HologramEngine target;
        try {
            target = engine(targetKind);
        } catch (RuntimeException error) {
            throw new EngineException("unavailable:" + targetKind.id + ":" + error.getMessage(), error);
        }
        Collection<Hologram> holograms;
        synchronized (switchLock) {
            migrating = true;
            pending.clear();
            holograms = List.copyOf(current.get());
        }
        try {
            return switchEngines(sourceKind, source, targetKind, target, holograms);
        } finally {
            finishMigration(holograms);
        }
    }

    private Migration switchEngines(EngineKind sourceKind, @Nullable HologramEngine source, EngineKind targetKind,
            HologramEngine target, Collection<Hologram> holograms) {
        boolean targetWasActive = target == active;
        try {
            if (targetWasActive) {
                target.stop();
            }
            target.start(holograms);
        } catch (RuntimeException error) {
            ctx.logger().error("Migration to hologram engine {} failed", targetKind.id, error);
            safeStop(target);
            if (targetWasActive) {
                try {
                    target.start(holograms);
                } catch (RuntimeException restartError) {
                    ctx.logger().error("Could not restart the previous hologram engine {}", targetKind.id, restartError);
                    fallbackToNative(holograms, "migration-failed");
                }
            }
            throw error;
        }
        ensureListeners(target);
        boolean sourceCleaned = false;
        if (source != null && source != target) {
            try {
                source.stop();
                sourceCleaned = true;
            } catch (RuntimeException error) {
                ctx.logger().warn("Old hologram engine {} did not stop cleanly: {}", sourceKind.id, error.getMessage());
            }
        }
        synchronized (switchLock) {
            active = target;
            activeKind = targetKind;
        }
        fallbackReason = "";
        return new Migration(holograms.size(), sourceCleaned);
    }

    /** Replays the edits stored during the migration on the engine that is active now (old, new or native). */
    private void finishMigration(Collection<Hologram> started) {
        synchronized (switchLock) {
            List<MigrationBuffer.Change> changes = pending.drain(started);
            migrating = false;
            HologramEngine engine = active;
            if (engine == null) {
                return;
            }
            for (MigrationBuffer.Change change : changes) {
                try {
                    engine.apply(change.before(), change.after());
                } catch (RuntimeException error) {
                    ctx.logger().error("Hologram engine {}: could not apply an edit made during the migration",
                            engine.id(), error);
                }
            }
            if (!changes.isEmpty()) {
                ctx.logger().info("Hologram engine {}: applied {} edits made during the migration", engine.id(),
                        changes.size());
            }
        }
    }

    // ---- external plugin enable/disable ----

    /** Falls back to native when the active engine's plugin goes away, and returns when it comes back. */
    final class PluginWatcher implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onDisable(PluginDisableEvent event) {
            onPluginDisabled(event.getPlugin().getName());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onEnable(PluginEnableEvent event) {
            onPluginEnabled(event.getPlugin().getName());
        }
    }

    private void onPluginDisabled(String plugin) {
        if (Bukkit.isStopping() || !ctx.plugin().isEnabled()
                || !EngineSelection.fallBackOnDisable(activeKind, plugin)) {
            return;
        }
        HologramEngine engine = active;
        if (engine instanceof MirroredEngine mirrored) {
            // Never call into a plugin that is being disabled; its non-persistent mirrors go away with it.
            mirrored.detach();
        }
        ctx.logger().warn("{} was disabled; holograms switch to the native engine", plugin);
        // Native start only schedules region work, so it is safe from whichever thread disables the plugin.
        fallbackToNative(current.get(), EngineSelection.PLUGIN_DISABLED);
    }

    private void onPluginEnabled(String plugin) {
        if (Bukkit.isStopping() || !ctx.plugin().isEnabled()) {
            return;
        }
        EngineKind selected = selection(configured).engine();
        if (!EngineSelection.returnOnEnable(activeKind, configured, fallbackReason, plugin, selected)) {
            return;
        }
        ctx.logger().info("{} was enabled; moving holograms back to the {} engine", plugin, selected.id);
        // Next global tick: the plugin finishes enabling first, and migrations run on the global thread.
        ctx.track(ctx.scheduler().global(() -> {
            if (!EngineSelection.returnOnEnable(activeKind, configured, fallbackReason, plugin, selected)) {
                return;
            }
            try {
                migrate(EngineKind.NATIVE, selected);
            } catch (RuntimeException error) {
                ctx.logger().warn("Could not move holograms back to {}: {}", selected.id, error.getMessage());
            }
        }));
    }

    // ---- selection ----

    private EngineKind readConfigured() {
        String raw = config.get().engine;
        Optional<EngineKind> parsed = EngineKind.parse(raw);
        if (parsed.isEmpty()) {
            ctx.logger().warn("holograms.yml: unknown engine '{}'; using auto", raw);
            return EngineKind.AUTO;
        }
        return parsed.get();
    }

    private EngineSelection.Selection selection(EngineKind requested) {
        boolean folia = ctx.scheduler().isFolia();
        return EngineSelection.select(requested, pluginEnabled("DecentHolograms"), pluginEnabled("FancyHolograms"),
                folia, decentFoliaReady(folia));
    }

    private boolean decentReady() {
        boolean folia = ctx.scheduler().isFolia();
        return pluginEnabled("DecentHolograms") && decentFoliaReady(folia);
    }

    private boolean decentFoliaReady(boolean folia) {
        return !folia || DecentHologramsVersion.supportsFolia(pluginVersion("DecentHolograms"));
    }

    private static boolean pluginEnabled(String name) {
        return Bukkit.getPluginManager().isPluginEnabled(name);
    }

    private static String pluginVersion(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin == null ? "" : plugin.getPluginMeta().getVersion();
    }

    private HologramEngine engine(EngineKind kind) {
        HologramEngine existing = engines.get(kind);
        if (existing != null) {
            return existing;
        }
        HologramEngine created = switch (kind) {
            case NATIVE -> nativeEngine;
            case DECENTHOLOGRAMS -> {
                if (!pluginEnabled("DecentHolograms")) {
                    throw new EngineException(EngineSelection.PLUGIN_MISSING);
                }
                if (ctx.scheduler().isFolia()
                        && !DecentHologramsVersion.supportsFolia(pluginVersion("DecentHolograms"))) {
                    throw new EngineException(EngineSelection.FOLIA_UNSUPPORTED);
                }
                yield DecentHologramsEngine.create(ctx, config, renderer);
            }
            case FANCYHOLOGRAMS -> {
                if (!pluginEnabled("FancyHolograms")) {
                    throw new EngineException(EngineSelection.PLUGIN_MISSING);
                }
                yield FancyHologramsEngine.create(ctx, config, renderer);
            }
            case AUTO -> throw new EngineException("auto is not a concrete engine");
        };
        engines.put(kind, created);
        return created;
    }

    private void ensureListeners(HologramEngine engine) {
        if (listened.add(engine)) {
            for (Listener listener : engine.listeners()) {
                ctx.listen(listener);
            }
        }
    }

    private void fallbackToNative(Collection<Hologram> holograms, String reason) {
        try {
            safeStop(nativeEngine);
            nativeEngine.start(holograms);
        } catch (RuntimeException error) {
            ctx.logger().error("Native hologram engine could not start ({})", reason, error);
        }
        synchronized (switchLock) {
            active = nativeEngine;
            activeKind = EngineKind.NATIVE;
        }
        fallbackReason = reason;
        ensureListeners(nativeEngine);
    }

    private void safeStop(HologramEngine engine) {
        try {
            engine.stop();
        } catch (RuntimeException error) {
            ctx.logger().warn("Hologram engine {} did not stop cleanly: {}", engine.id(), error.getMessage());
        }
    }

}
