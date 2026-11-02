package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Stored changes that arrive while {@code /holo engine migrate} runs. The new engine is started from a snapshot
 * taken when the migration began; every change stored after that is kept here (latest value per hologram)
 * and replayed on whichever engine is active once the switch is done, so no edit is lost.
 *
 * <p>Not thread-safe; the engine controller guards it with its switch lock.
 */
final class MigrationBuffer {
    /** One engine operation to replay: {@code before} is what the engine was started with. */
    record Change(@Nullable Hologram before, @Nullable Hologram after) {}

    /** Hologram key -> latest stored value (null = deleted). Insertion order = first change order. */
    private final Map<String, @Nullable Hologram> latest = new LinkedHashMap<>();

    void record(@Nullable Hologram before, @Nullable Hologram after) {
        Hologram any = after != null ? after : before;
        if (any != null) {
            latest.put(any.key(), after);
        }
    }

    boolean isEmpty() {
        return latest.isEmpty();
    }

    void clear() {
        latest.clear();
    }

    /**
     * What to apply to an engine started with {@code started}: one change per hologram from its snapshot value
     * to its latest stored value, skipping holograms that ended up unchanged. Clears the buffer.
     */
    List<Change> drain(Collection<Hologram> started) {
        Map<String, Hologram> byKey = new HashMap<>();
        for (Hologram hologram : started) {
            byKey.put(hologram.key(), hologram);
        }
        List<Change> changes = new ArrayList<>();
        for (Map.Entry<String, @Nullable Hologram> entry : latest.entrySet()) {
            Hologram before = byKey.get(entry.getKey());
            Hologram after = entry.getValue();
            if (!Objects.equals(before, after)) {
                changes.add(new Change(before, after));
            }
        }
        latest.clear();
        return changes;
    }
}
