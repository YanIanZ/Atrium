package dev.yanianz.atrium.api.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Server holograms, published by the holograms module. Thread-safe. Every change is stored before the
 * returned future completes; a storage failure completes the future exceptionally and changes nothing.
 *
 * <p>Line syntax: plain text is a trusted MiniMessage template ({@code <gold>Hello}); {@code #item:<material>}
 * shows an item, {@code #block:<block data>} a block. Lines are admin templates: callers that insert
 * player-provided text must escape it first ({@code MiniMessage.miniMessage().escapeTags(text)}).
 */
public interface HologramService {
    /** Names of all holograms in their stored spelling. */
    Collection<String> names();

    /** A snapshot of the hologram (case-insensitive name). */
    Optional<HologramInfo> get(String name);

    /** Creates a hologram with one page holding {@code lines}. */
    CompletableFuture<Result> create(String name, StoredLocation location, List<String> lines);

    CompletableFuture<Result> delete(String name);

    /** Appends a line to the last page. */
    CompletableFuture<Result> addLine(String name, String line);

    /**
     * Replaces the lines of a page (1-based). {@code page = pageCount + 1} appends a new page.
     */
    CompletableFuture<Result> setPage(String name, int page, List<String> lines);

    CompletableFuture<Result> move(String name, StoredLocation location);

    enum Result {
        SUCCESS,
        NOT_FOUND,
        /** A hologram with this name already exists. */
        EXISTS,
        /** Invalid name, line, page number or a configured limit was exceeded. */
        INVALID
    }

    /** Immutable view of one hologram: pages of raw line strings. */
    record HologramInfo(String name, StoredLocation location, List<List<String>> pages) {
        public HologramInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(location, "location");
            pages = pages.stream().map(List::copyOf).toList();
        }
    }
}
