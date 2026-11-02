package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The /back history of one player: a bounded ring of locations, newest first. Pushing onto a full history
 * drops the oldest entry. Immutable; every change returns a new instance.
 */
public record BackHistory(List<Entry> entries) {
    public static final BackHistory EMPTY = new BackHistory(List.of());
    /** Hard upper bound, whatever the configuration says. */
    public static final int MAX_CAPACITY = 50;

    /**
     * @param location   where /back leads
     * @param death      a death location (needs atrium.back.ondeath)
     * @param recordedAt epoch millis
     */
    public record Entry(StoredLocation location, boolean death, long recordedAt) {
        public Entry {
            Objects.requireNonNull(location, "location");
        }
    }

    public BackHistory {
        entries = List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** Newest entry, if any. */
    public Optional<Entry> latest() {
        return get(1);
    }

    /** Entry {@code n}, 1 = newest. */
    public Optional<Entry> get(int n) {
        return n >= 1 && n <= entries.size() ? Optional.of(entries.get(n - 1)) : Optional.empty();
    }

    /**
     * Adds {@code entry} as the newest and keeps at most {@code capacity} entries (oldest are dropped). When
     * the newest entry already is the same spot (one teleport reported twice) it is replaced, not duplicated.
     */
    public BackHistory push(Entry entry, int capacity) {
        Objects.requireNonNull(entry, "entry");
        int cap = clampCapacity(capacity);
        int skip = !entries.isEmpty() && entries.getFirst().location().equals(entry.location()) ? 1 : 0;
        List<Entry> next = new ArrayList<>(Math.min(cap, entries.size() + 1));
        next.add(entry);
        for (int i = skip; i < entries.size() && next.size() < cap; i++) {
            next.add(entries.get(i));
        }
        return new BackHistory(next);
    }

    /** Removes the first entry equal to {@code entry} (after /back used it). */
    public BackHistory without(Entry entry) {
        int index = entries.indexOf(entry);
        if (index < 0) {
            return this;
        }
        List<Entry> next = new ArrayList<>(entries);
        next.remove(index);
        return new BackHistory(next);
    }

    /** Keeps only the newest {@code capacity} entries (used when the configured size shrinks). */
    public BackHistory trim(int capacity) {
        int cap = clampCapacity(capacity);
        return entries.size() <= cap ? this : new BackHistory(entries.subList(0, cap));
    }

    /** Entries recorded at or after {@code oldestAllowed} (epoch millis). */
    public BackHistory recordedSince(long oldestAllowed) {
        if (entries.stream().allMatch(e -> e.recordedAt() >= oldestAllowed)) {
            return this;
        }
        return new BackHistory(entries.stream().filter(e -> e.recordedAt() >= oldestAllowed).toList());
    }

    static int clampCapacity(int capacity) {
        return Math.max(1, Math.min(MAX_CAPACITY, capacity));
    }

    /**
     * Locale-independent text form: one entry per line, {@code <recordedAt>;<0|1>;<StoredLocation#encode>}.
     * Entries whose world name contains a line break are not stored.
     */
    public String encode() {
        StringBuilder out = new StringBuilder();
        for (Entry entry : entries) {
            if (entry.location().world().indexOf('\n') >= 0 || entry.location().world().indexOf('\r') >= 0) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(entry.recordedAt()).append(';').append(entry.death() ? '1' : '0').append(';')
                    .append(entry.location().encode());
        }
        return out.toString();
    }

    /** Parses {@link #encode()}; malformed lines are skipped. */
    public static BackHistory decode(String text) {
        if (text == null || text.isBlank()) {
            return EMPTY;
        }
        List<Entry> entries = new ArrayList<>();
        for (String line : text.split("\n")) {
            int first = line.indexOf(';');
            int second = first < 0 ? -1 : line.indexOf(';', first + 1);
            if (second < 0) {
                continue;
            }
            try {
                long at = Long.parseLong(line.substring(0, first));
                boolean death = line.substring(first + 1, second).equals("1");
                Optional<StoredLocation> location = StoredLocation.decode(line.substring(second + 1));
                location.ifPresent(l -> entries.add(new Entry(l, death, at)));
            } catch (NumberFormatException e) {
                // skip the malformed line
            }
        }
        return entries.size() > MAX_CAPACITY ? new BackHistory(entries.subList(0, MAX_CAPACITY)) : new BackHistory(entries);
    }
}
