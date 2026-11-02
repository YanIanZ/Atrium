package dev.yanianz.atrium.module.holograms;

import java.util.Collection;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.event.Listener;

/**
 * The original display-entity implementation: TextDisplay/ItemDisplay/BlockDisplay plus an Interaction box,
 * with per-viewer copies. It owns no storage of its own; {@link Displays} keeps the runtimes and
 * {@link ViewerTracker} handles range, permissions and per-viewer pages.
 */
final class NativeEngine implements HologramEngine {
    private final Displays displays;
    private final ViewerTracker tracker;

    NativeEngine(Displays displays, ViewerTracker tracker) {
        this.displays = displays;
        this.tracker = tracker;
        displays.setTracker(tracker);
    }

    @Override
    public EngineKind kind() {
        return EngineKind.NATIVE;
    }

    @Override
    public EngineCapabilities capabilities() {
        return EngineCapabilities.nativeEngine();
    }

    @Override
    public void start(Collection<Hologram> holograms) {
        displays.loadAll(holograms);
        tracker.startAll(Bukkit.getOnlinePlayers());
    }

    @Override
    public void stop() {
        tracker.stop();
        displays.shutdown();
    }

    @Override
    public void create(Hologram hologram) {
        displays.changed(null, hologram);
    }

    @Override
    public void delete(Hologram hologram) {
        displays.changed(hologram, null);
    }

    @Override
    public void move(Hologram before, Hologram after) {
        displays.changed(before, after);
    }

    @Override
    public void setPages(Hologram before, Hologram after) {
        displays.changed(before, after);
    }

    @Override
    public void updateText(Hologram before, Hologram after) {
        displays.changed(before, after);
    }

    @Override
    public void refreshAll(Collection<Hologram> holograms) {
        displays.refreshAll();
    }

    @Override
    public List<Listener> listeners() {
        return List.of(displays, tracker);
    }
}
