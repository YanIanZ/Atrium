package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diff between the Atrium definitions and the {@code atrium_} mirrors Atrium owns in an external plugin.
 * Callers pass only owned mirrors ({@link MirrorOwnership}); holograms without the managed prefix are dropped
 * here as a second guard, so Atrium cannot delete or overwrite holograms created by an admin or another plugin.
 */
final class Reconcile {
    record Desired(String name, String fingerprint) {}

    record Existing(String name, String fingerprint) {}

    /**
     * {@code create}: desired but missing, {@code update}: present with different content,
     * {@code delete}: managed but not desired any more.
     */
    record Plan(List<String> create, List<String> update, List<String> delete) {
        Plan {
            create = List.copyOf(create);
            update = List.copyOf(update);
            delete = List.copyOf(delete);
        }

        static Plan empty() {
            return new Plan(List.of(), List.of(), List.of());
        }

        int total() {
            return create.size() + update.size() + delete.size();
        }

        boolean isEmpty() {
            return total() == 0;
        }
    }

    private Reconcile() {}

    static Plan plan(List<Desired> desired, List<Existing> existing) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (Desired entry : desired) {
            if (EngineNames.isManaged(entry.name())) {
                wanted.put(entry.name(), entry.fingerprint());
            }
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (Existing entry : existing) {
            if (EngineNames.isManaged(entry.name())) {
                current.put(entry.name(), entry.fingerprint());
            }
        }
        List<String> create = new ArrayList<>();
        List<String> update = new ArrayList<>();
        List<String> delete = new ArrayList<>();
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                create.add(entry.getKey());
            } else if (!java.util.Objects.equals(current.get(entry.getKey()), entry.getValue())) {
                update.add(entry.getKey());
            }
        }
        for (String name : current.keySet()) {
            if (!wanted.containsKey(name)) {
                delete.add(name);
            }
        }
        return new Plan(create, update, delete);
    }

    /**
     * Canonical content hash of a mirror: location plus the exact strings that are written to the external
     * plugin. Two starts with the same definition therefore produce no update at all.
     */
    static String fingerprint(StoredLocation location, List<List<String>> pages) {
        StringBuilder out = new StringBuilder(128);
        out.append(location.world()).append('|')
                .append(Double.doubleToLongBits(location.x())).append('|')
                .append(Double.doubleToLongBits(location.y())).append('|')
                .append(Double.doubleToLongBits(location.z())).append('|')
                .append(Float.floatToIntBits(location.yaw())).append('|')
                .append(Float.floatToIntBits(location.pitch()));
        for (List<String> page : pages) {
            out.append('\u001e');
            for (String line : page) {
                out.append(line).append('\u001f');
            }
        }
        return out.toString();
    }
}
