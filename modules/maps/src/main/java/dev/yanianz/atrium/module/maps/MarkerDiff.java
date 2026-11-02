package dev.yanianz.atrium.module.maps;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of comparing the markers a backend currently shows with the markers it should show.
 *
 * <p>Comparing ids first means a moved or renamed marker is reported as one change rather than a
 * removal and an addition. Results are sorted by id so logs and tests are deterministic.
 */
public record MarkerDiff(List<MapMarker> added, List<MapMarker> changed, List<String> removed) {
    public MarkerDiff {
        added = List.copyOf(added);
        changed = List.copyOf(changed);
        removed = List.copyOf(removed);
    }

    public static MarkerDiff diff(Map<String, MapMarker> previous, Map<String, MapMarker> next) {
        Map<String, MapMarker> before = previous == null ? Map.of() : previous;
        Map<String, MapMarker> after = next == null ? Map.of() : next;
        List<MapMarker> added = new ArrayList<>();
        List<MapMarker> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, MapMarker> entry : after.entrySet()) {
            MapMarker old = before.get(entry.getKey());
            if (old == null) {
                added.add(entry.getValue());
            } else if (!old.equals(entry.getValue())) {
                changed.add(entry.getValue());
            }
        }
        for (String id : before.keySet()) {
            if (!after.containsKey(id)) {
                removed.add(id);
            }
        }
        added.sort(Comparator.comparing(MapMarker::id));
        changed.sort(Comparator.comparing(MapMarker::id));
        removed.sort(Comparator.naturalOrder());
        return new MarkerDiff(added, changed, removed);
    }

    /** Indexes markers by id; later duplicates replace earlier ones, which matches the backends. */
    public static Map<String, MapMarker> index(Iterable<MapMarker> markers) {
        Map<String, MapMarker> byId = new LinkedHashMap<>();
        if (markers != null) {
            for (MapMarker marker : markers) {
                byId.put(marker.id(), marker);
            }
        }
        return byId;
    }

    public boolean isEmpty() {
        return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
    }

    public int changeCount() {
        return added.size() + changed.size() + removed.size();
    }
}
