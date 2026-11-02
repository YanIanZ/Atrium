package dev.yanianz.atrium.module.homes;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Objects;

/** One named home. Immutable. */
public record Home(String name, StoredLocation location, long createdAt) {
    public Home {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
    }
}
