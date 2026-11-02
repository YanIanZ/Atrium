package dev.yanianz.atrium.api.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Collection;
import java.util.Optional;

/** Read access to server warps, published by the warps module. Thread-safe. */
public interface WarpService {
    /** Names of all warps, in their stored spelling. */
    Collection<String> names();

    /** The warp location by name (case-insensitive). */
    Optional<StoredLocation> location(String name);

    /** The warp category by name (case-insensitive). */
    Optional<String> category(String name);
}
