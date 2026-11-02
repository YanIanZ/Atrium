package dev.yanianz.atrium.api.profile;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Worlds bound to a player's active profile (SourbyCraft AWF MultiProfile). Published by the {@code sourbycraft}
 * module while the profile system is enabled; uses only the official SourbyCraft API.
 *
 * <p>Futures complete on no particular thread and must never be joined on a tick thread.
 */
public interface ProfileWorldService {
    /** Role of the profile's own main world. */
    String PRIMARY = "PRIMARY";

    /** One world of a profile. {@code role} is SourbyCraft's role name (PRIMARY, NETHER, END, SHARED, AUXILIARY). */
    record ProfileWorld(String world, String role, boolean loaded) {
        public ProfileWorld {
            Objects.requireNonNull(world, "world");
            role = role == null ? "" : role;
        }

        public boolean primary() {
            return PRIMARY.equalsIgnoreCase(role);
        }
    }

    /** The active profile of a player and its worlds, in the order the profile system returned them. */
    record ProfileWorlds(UUID profileId, List<ProfileWorld> worlds) {
        public ProfileWorlds {
            Objects.requireNonNull(profileId, "profileId");
            worlds = worlds == null ? List.of() : List.copyOf(worlds);
        }

        /** The first world with role PRIMARY. */
        public Optional<ProfileWorld> primary() {
            return worlds.stream().filter(ProfileWorld::primary).findFirst();
        }
    }

    /** Whether the profile system is enabled. */
    boolean enabled();

    /** The worlds of the player's active profile; empty when the player has no active profile. */
    CompletableFuture<Optional<ProfileWorlds>> activeProfileWorlds(UUID player);
}
