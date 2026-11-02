package dev.yanianz.atrium.module.holograms;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which external holograms Atrium may touch. The {@code atrium_} prefix alone is not proof of ownership: an
 * admin can name a DecentHolograms/FancyHolograms hologram {@code atrium_spawn} by hand. A hologram is
 * Atrium's only when it is non-persistent in the external plugin (DH: not saved to file; FH:
 * {@code persistent=false}) <em>and</em> Atrium created that name itself in this server process.
 *
 * <p>The mirrors are non-persistent, so they never outlive the process; the created names are therefore kept
 * in memory only. The shared registry is per engine kind and survives a module reload (same class loader), so
 * a mirror left behind by a failed stop is still recognised on the next start.
 */
final class MirrorOwnership {
    enum Status {
        /** Created by Atrium in this process and non-persistent: Atrium may update, show, hide and delete it. */
        OWNED,
        /** Carries the prefix but belongs to an admin or another plugin: never touched. */
        FOREIGN,
        /** No managed prefix at all: not Atrium's business. */
        UNRELATED
    }

    private static final Map<EngineKind, MirrorOwnership> SHARED = new EnumMap<>(EngineKind.class);

    private final Set<String> created = ConcurrentHashMap.newKeySet();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    MirrorOwnership() {}

    /** The process-wide registry of one external engine. */
    static MirrorOwnership forKind(EngineKind kind) {
        synchronized (SHARED) {
            return SHARED.computeIfAbsent(kind, k -> new MirrorOwnership());
        }
    }

    Status status(String externalName, boolean persistent) {
        if (!EngineNames.isManaged(externalName)) {
            return Status.UNRELATED;
        }
        return !persistent && created.contains(externalName) ? Status.OWNED : Status.FOREIGN;
    }

    boolean owns(String externalName, boolean persistent) {
        return status(externalName, persistent) == Status.OWNED;
    }

    /** Records a mirror Atrium has just created. */
    void claim(String externalName) {
        created.add(externalName);
        warned.remove(externalName);
    }

    /** Forgets a mirror Atrium has deleted (or that disappeared with its plugin). */
    void release(String externalName) {
        created.remove(externalName);
    }

    /** The external plugin went away and took every non-persistent mirror with it. */
    void releaseAll() {
        created.clear();
    }

    /** True the first time a foreign hologram with this name is reported; later reports stay quiet. */
    boolean warnOnce(String externalName) {
        return warned.add(externalName);
    }
}
