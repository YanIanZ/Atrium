package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * Common part of the DecentHolograms and FancyHolograms engines: Atrium keeps every definition and mirrors
 * it under a stable {@code atrium_} name. The external plugin is never read as a source of truth; only the
 * mirrors Atrium owns ({@link MirrorOwnership}: non-persistent and created by Atrium) are listed at start so
 * stale mirrors can be updated or removed. A prefixed hologram Atrium does not own is reported once and never
 * changed, shown, hidden or deleted.
 *
 * <p>All external calls run on the global region thread (the main thread on Paper). Hook subclasses only
 * contain the plugin-specific calls.
 */
abstract class MirroredEngine implements HologramEngine, MirrorViewer.Host {
    protected final ModuleContext ctx;
    protected final TextRenderer renderer;
    private final Map<String, Hologram> known = new ConcurrentHashMap<>();
    private final MirrorViewer viewer;
    private final MirrorOwnership ownership;
    private final Set<String> warnedWorlds = ConcurrentHashMap.newKeySet();
    private final Set<String> warnedContent = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private volatile boolean started;

    MirroredEngine(ModuleContext ctx, Supplier<HologramsConfig> config, TextRenderer renderer, boolean pageCycling) {
        this.ctx = ctx;
        this.renderer = renderer;
        this.viewer = new MirrorViewer(ctx, config, this, pageCycling);
        this.ownership = MirrorOwnership.forKind(kind());
    }

    /** One external hologram whose name carries the managed prefix (owned or not). */
    protected record Prefixed(String name, boolean persistent, Supplier<String> fingerprint) {}

    /** The serialized form this engine expects (legacy colours or MiniMessage). */
    protected abstract MirrorText.Format format();

    protected abstract void createExternal(Hologram hologram);

    protected abstract void updateExternal(Hologram hologram);

    protected abstract void deleteExternal(String externalName);

    /**
     * Every external hologram whose name carries the managed prefix, with whether the external plugin persists
     * it. Ownership is decided here, not by the hook.
     */
    protected abstract List<Prefixed> listPrefixed();

    /** Whether the external hologram is persistent; null when no hologram has this name. */
    protected abstract @Nullable Boolean persistentOf(String externalName);

    /** Extra listeners of the hook (for example the external plugin's reload event). */
    protected List<Listener> hookListeners() {
        return List.of();
    }

    protected abstract void showExternal(Player viewer, Hologram hologram, int page);

    protected abstract void hideExternal(Player viewer, Hologram hologram);

    // ---- lifecycle ----

    @Override
    public final void start(Collection<Hologram> holograms) {
        known.clear();
        for (Hologram hologram : holograms) {
            known.put(hologram.key(), hologram);
        }
        // Plugin enable runs before the global scheduler ticks on Folia, so the initial reconcile runs
        // inline on the enable thread. Later calls use the global region thread.
        reconcile(holograms);
        started = true;
        running = true;
        viewer.startAll();
    }

    @Override
    public final void stop() {
        running = false;
        viewer.stop();
        // The mirrors are non-persistent (DH saveToFile=false, FancyHolograms persistent=false); on a server
        // stop they disappear with the process, and touching the external plugin during shutdown is unsafe.
        if (!Bukkit.isStopping()) {
            try {
                if (!started || !ctx.plugin().isEnabled()) {
                    deleteAllManaged();
                } else {
                    onGlobalNow(this::deleteAllManaged);
                }
            } catch (RuntimeException e) {
                ctx.logger().warn("Hologram engine {}: could not remove its mirrors while stopping ({})", id(),
                        e.getMessage());
            }
        }
        started = false;
        known.clear();
    }

    /**
     * The external plugin is being disabled: stop without calling it. Its non-persistent mirrors disappear
     * with it, so the names are released.
     */
    final void detach() {
        running = false;
        viewer.stop();
        started = false;
        known.clear();
        ownership.releaseAll();
    }

    /**
     * The external plugin reloaded its holograms (which drops Atrium's non-persistent mirrors). Recreates the
     * mirrors and makes every viewer be shown them again.
     */
    final void externalReloaded() {
        if (!running) {
            return;
        }
        onGlobal(() -> {
            reconcile(List.copyOf(known.values()));
            viewer.reset();
        });
    }

    @Override
    public final void create(Hologram hologram) {
        known.put(hologram.key(), hologram);
        warnUnsupported(hologram);
        onGlobal(() -> upsertMirror(hologram));
    }

    @Override
    public final void delete(Hologram hologram) {
        known.remove(hologram.key());
        viewer.forget(hologram.key());
        String external = EngineNames.externalName(hologram.name());
        onGlobal(() -> deleteMirror(external));
    }

    @Override
    public final void move(Hologram before, Hologram after) {
        known.put(after.key(), after);
        String external = EngineNames.externalName(after.name());
        onGlobal(() -> {
            if (access(external) == MirrorOwnership.Status.FOREIGN) {
                return;
            }
            // A different location is a different entity in both plugins; recreate instead of teleporting.
            deleteMirror(external);
            upsertMirror(after);
            // Show the new entity to everyone again on the next viewer check.
            viewer.forget(after.key());
        });
    }

    @Override
    public final void setPages(Hologram before, Hologram after) {
        known.put(after.key(), after);
        warnUnsupported(after);
        onGlobal(() -> upsertMirror(after));
    }

    @Override
    public final void setLines(Hologram before, Hologram after) {
        setPages(before, after);
    }

    @Override
    public final void updateText(Hologram before, Hologram after) {
        known.put(after.key(), after);
        warnUnsupported(after);
        onGlobal(() -> upsertMirror(after));
    }

    @Override
    public final void refreshAll(Collection<Hologram> holograms) {
        known.clear();
        for (Hologram hologram : holograms) {
            known.put(hologram.key(), hologram);
        }
        onGlobal(() -> {
            for (Hologram hologram : holograms) {
                warnUnsupported(hologram);
                if (hologram.location().toLocation().isPresent()) {
                    upsertMirror(hologram);
                }
            }
        });
    }

    // ---- viewer bridge ----

    @Override
    public final Collection<Hologram> holograms() {
        return List.copyOf(known.values());
    }

    @Override
    public final void show(Player player, Hologram hologram, int page) {
        onGlobal(() -> {
            if (access(EngineNames.externalName(hologram.name())) == MirrorOwnership.Status.OWNED) {
                showExternal(player, hologram, page);
            }
        });
    }

    @Override
    public final void hide(Player player, Hologram hologram) {
        onGlobal(() -> {
            if (access(EngineNames.externalName(hologram.name())) == MirrorOwnership.Status.OWNED) {
                hideExternal(player, hologram);
            }
        });
    }

    @Override
    public final void show(Player viewer, String name) {
        show(viewer, name, 0);
    }

    @Override
    public final void show(Player viewer, String name, int page) {
        Hologram hologram = known.get(Hologram.key(name));
        if (hologram != null) {
            show(viewer, hologram, page);
        }
    }

    @Override
    public final void hide(Player viewer, String name) {
        Hologram hologram = known.get(Hologram.key(name));
        if (hologram != null) {
            hide(viewer, hologram);
        }
    }

    @Override
    public final List<Listener> listeners() {
        List<Listener> listeners = new ArrayList<>();
        listeners.add(viewer);
        listeners.addAll(hookListeners());
        return List.copyOf(listeners);
    }

    // ---- ownership (global thread) ----

    /** OWNED, FOREIGN (reported once) or UNRELATED; UNRELATED here means no hologram has this name. */
    private MirrorOwnership.Status access(String external) {
        Boolean persistent = persistentOf(external);
        if (persistent == null) {
            return MirrorOwnership.Status.UNRELATED;
        }
        MirrorOwnership.Status status = ownership.status(external, persistent);
        if (status == MirrorOwnership.Status.FOREIGN) {
            warnForeign(external);
        }
        return status;
    }

    private void warnForeign(String external) {
        if (ownership.warnOnce(external)) {
            ctx.logger().warn("Hologram engine {}: '{}' already exists there but was not created by Atrium (it is saved "
                    + "or belongs to another plugin); Atrium will not change or delete it. Rename it to let Atrium "
                    + "mirror its own hologram.", id(), external);
        }
    }

    /** Creates or updates an Atrium mirror; a foreign hologram with the same name is left alone. */
    private void upsertMirror(Hologram hologram) {
        String external = EngineNames.externalName(hologram.name());
        MirrorOwnership.Status status = access(external);
        if (status == MirrorOwnership.Status.FOREIGN) {
            return;
        }
        if (status == MirrorOwnership.Status.OWNED) {
            updateExternal(hologram);
            return;
        }
        if (hologram.location().toLocation().isEmpty()) {
            return;
        }
        // Claim first: the hook may look the name up again while creating it.
        ownership.claim(external);
        createExternal(hologram);
    }

    /** Deletes an Atrium mirror; never a hologram Atrium does not own. */
    private void deleteMirror(String external) {
        MirrorOwnership.Status status = access(external);
        if (status == MirrorOwnership.Status.OWNED) {
            deleteExternal(external);
        }
        // Gone, deleted, or now a foreign hologram (saved or replaced): Atrium no longer owns this name.
        ownership.release(external);
    }

    private List<Reconcile.Existing> existingOwned() {
        List<Reconcile.Existing> owned = new ArrayList<>();
        for (Prefixed entry : listPrefixed()) {
            switch (ownership.status(entry.name(), entry.persistent())) {
                case OWNED -> owned.add(new Reconcile.Existing(entry.name(), entry.fingerprint().get()));
                case FOREIGN -> warnForeign(entry.name());
                case UNRELATED -> { }
            }
        }
        return owned;
    }

    // ---- reconcile ----

    private void reconcile(Collection<Hologram> holograms) {
        List<Reconcile.Existing> existing = existingOwned();
        Map<String, Reconcile.Existing> existingByName = new HashMap<>();
        for (Reconcile.Existing entry : existing) {
            existingByName.put(entry.name(), entry);
        }
        List<Reconcile.Desired> desired = new ArrayList<>();
        Map<String, Hologram> byExternal = new HashMap<>();
        Set<String> unloaded = new HashSet<>();
        for (Hologram hologram : holograms) {
            warnUnsupported(hologram);
            String external = EngineNames.externalName(hologram.name());
            if (!EngineNames.validExternalName(external)) {
                ctx.logger().warn("Hologram {} cannot be mirrored: derived name '{}' is not valid", hologram.name(), external);
                continue;
            }
            byExternal.put(external, hologram);
            if (hologram.location().toLocation().isEmpty()) {
                warnWorld(hologram);
                Reconcile.Existing current = existingByName.get(external);
                // Keep an existing mirror as it is; do not delete it just because its world is unloaded.
                desired.add(new Reconcile.Desired(external, current == null ? "" : current.fingerprint()));
                unloaded.add(external);
            } else {
                desired.add(new Reconcile.Desired(external, fingerprint(hologram)));
            }
        }
        Reconcile.Plan plan = Reconcile.plan(desired, existing);
        // Each step re-checks ownership: a create whose name is taken by a foreign hologram is skipped.
        for (String name : plan.create()) {
            if (!unloaded.contains(name)) {
                upsertMirror(byExternal.get(name));
            }
        }
        for (String name : plan.update()) {
            if (!unloaded.contains(name)) {
                upsertMirror(byExternal.get(name));
            }
        }
        for (String name : plan.delete()) {
            deleteMirror(name);
        }
        if (!plan.isEmpty()) {
            ctx.logger().info("Hologram engine {}: reconciled mirrors ({} created, {} updated, {} removed)",
                    id(), plan.create().size(), plan.update().size(), plan.delete().size());
        }
    }

    private String fingerprint(Hologram hologram) {
        return Reconcile.fingerprint(hologram.location(),
                MirrorText.pages(hologram, renderer, capabilities(), format(), ctx.logger()));
    }

    private void warnWorld(Hologram hologram) {
        if (warnedWorlds.add(hologram.location().world())) {
            ctx.logger().warn("Hologram {} is in world '{}', which is not loaded; it stays in Atrium and is not mirrored "
                    + "until the world is loaded and the module reloads", hologram.name(), hologram.location().world());
        }
    }

    /** One warning per hologram and engine for content the engine cannot reproduce; stored data is kept. */
    private void warnUnsupported(Hologram hologram) {
        if (!warnedContent.add(id() + ':' + hologram.key())) {
            return;
        }
        List<String> missing = new ArrayList<>();
        boolean perViewerText = false;
        boolean item = false;
        boolean block = false;
        boolean offset = false;
        for (List<HoloLine> page : hologram.pages()) {
            for (HoloLine line : page) {
                if (line.hasOffset()) {
                    offset = true;
                }
                switch (line.content()) {
                    case LineContent.Text text -> {
                        if (PlaceholderText.hasTokens(text.template()) || PlaceholderText.hasViewerTag(text.template())) {
                            perViewerText = true;
                        }
                    }
                    case LineContent.Item ignored -> item = true;
                    case LineContent.SavedItem ignored -> item = true;
                    case LineContent.Block ignored -> block = true;
                }
            }
        }
        if (perViewerText && !capabilities().perViewerText()) {
            missing.add("per-viewer placeholders");
        }
        if (item && !capabilities().itemLines()) {
            missing.add("item lines");
        }
        if (block && !capabilities().blockLines()) {
            missing.add("block lines");
        }
        if (offset && !capabilities().lineOffsets()) {
            missing.add("line offsets");
        }
        if (!capabilities().pages() && hologram.pages().size() > 1) {
            missing.add("pages after the first");
        }
        if (!capabilities().clickActions() && !hologram.actions().isEmpty()) {
            missing.add("click actions");
        }
        if (!missing.isEmpty()) {
            ctx.logger().warn("Hologram {}: engine {} cannot show {}; Atrium keeps the stored data", hologram.name(),
                    id(), String.join(", ", missing));
        }
    }

    private void deleteAllManaged() {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (Prefixed entry : listPrefixed()) {
                if (ownership.owns(entry.name(), entry.persistent())) {
                    names.add(entry.name());
                }
            }
        } catch (RuntimeException e) {
            ctx.logger().warn("Hologram engine {}: cannot list its mirrors while stopping ({})", id(), e.getMessage());
        }
        for (Hologram hologram : known.values()) {
            names.add(EngineNames.externalName(hologram.name()));
        }
        for (String name : names) {
            if (!EngineNames.isManaged(name)) {
                continue;
            }
            try {
                deleteMirror(name);
            } catch (RuntimeException e) {
                ctx.logger().warn("Hologram engine {}: could not remove mirror '{}' ({})", id(), name, e.getMessage());
            }
        }
    }

    // ---- global thread ----

    protected final void onGlobal(Runnable task) {
        if (!running) {
            return;
        }
        if (Bukkit.isGlobalTickThread() || !ctx.plugin().isEnabled()) {
            // During enable the global scheduler has not started ticking yet; the enable thread is the
            // server thread, so hook calls must run inline instead of waiting for a task that cannot run.
            safe(task);
            return;
        }
        try {
            ctx.scheduler().global(() -> safe(task));
        } catch (RuntimeException e) {
            ctx.logger().error("Hologram engine {}: could not schedule a mirror update", id(), e);
        }
    }

    protected final void onGlobalNow(Runnable task) {
        if (Bukkit.isGlobalTickThread() || !ctx.plugin().isEnabled()) {
            // Plugin enable runs before the global scheduler ticks on Folia; see onGlobal above.
            task.run();
            return;
        }
        try {
            ctx.scheduler().supplyGlobal(() -> {
                task.run();
                return null;
            }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new EngineException("engine call failed", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new EngineException("engine call timed out after 30 seconds", e);
        }
    }

    private void safe(Runnable task) {
        if (!running) {
            // The engine was stopped (or migrated away from) after this task was scheduled.
            return;
        }
        try {
            task.run();
        } catch (RuntimeException e) {
            ctx.logger().error("Hologram engine {}: mirroring a hologram failed", id(), e);
        }
    }
}
