package dev.yanianz.atrium.module.holograms;

import java.util.Collection;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * A way to put an Atrium hologram into the world. Holograms, their pages and settings are always stored by
 * Atrium; an engine only mirrors the projection. That is why switching engines never changes stored data.
 *
 * <p>Methods may be called from any thread. The native engine schedules on the region that owns the
 * hologram; external engines schedule their plugin calls on the global region thread, which is the main
 * thread on Paper and the scheduler DecentHolograms 2.10+ and FancyHolograms expect on Folia.
 */
interface HologramEngine {
    EngineKind kind();

    default String id() {
        return kind().id;
    }

    EngineCapabilities capabilities();

    /** Registers every hologram and reconciles managed mirrors that were left by an earlier run. */
    void start(Collection<Hologram> holograms);

    /** Removes this engine's live or mirrored holograms. Managed external mirrors are cleaned up. */
    void stop();

    void create(Hologram hologram);

    void delete(Hologram hologram);

    void move(Hologram before, Hologram after);

    void setPages(Hologram before, Hologram after);

    /** One line changed. Engines without a cheaper line path may treat it as {@link #setPages}. */
    default void setLines(Hologram before, Hologram after) {
        setPages(before, after);
    }

    /** Settings, click actions or other non-line content changed. */
    void updateText(Hologram before, Hologram after);

    /** Per-viewer visibility where the engine supports it; the native engine uses its own tracker. */
    default void show(Player viewer, String name) {
        // native: ViewerTracker owns visibility
    }

    default void show(Player viewer, String name, int page) {
        show(viewer, name);
    }

    default void hide(Player viewer, String name) {
        // native: ViewerTracker owns visibility
    }

    /** Re-applies every definition after a config reload. */
    void refreshAll(Collection<Hologram> holograms);

    /** Event listeners this engine needs. Registered once, after {@link #start} succeeded. */
    default List<Listener> listeners() {
        return List.of();
    }

    /** Dispatches one stored change to the narrowest engine operation. */
    default void apply(@Nullable Hologram before, @Nullable Hologram after) {
        if (after == null) {
            if (before != null) {
                delete(before);
            }
            return;
        }
        if (before == null) {
            create(after);
            return;
        }
        if (!before.location().equals(after.location())) {
            move(before, after);
            return;
        }
        if (!before.pages().equals(after.pages())) {
            setPages(before, after);
            return;
        }
        if (!before.settings().equals(after.settings()) || !before.actions().equals(after.actions())) {
            updateText(before, after);
        }
    }
}
