package dev.yanianz.atrium.module.placeholders;

import dev.yanianz.atrium.api.profile.ActiveProfileService;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code %atrium_profile%}, {@code %atrium_profile_type%} and {@code %atrium_profile_role%}: the active
 * SourbyCraft MultiProfile of a player. Values come only from {@link ActiveProfileService#cached}, an
 * immutable, thread-safe cache that the sourbycraft module refreshes in the background, so a request never
 * waits for the profile system. Without the service (plain Paper/Folia) or a known profile the fallback is
 * returned.
 */
final class ProfilePlaceholders {
    static final List<String> KEYS = List.of("profile", "profile_type", "profile_role");

    private ProfilePlaceholders() {}

    static boolean handles(String key) {
        return KEYS.contains(key);
    }

    /** Value for a profile key (see {@link #handles}); the fallback when unknown. Output is sanitised plain text. */
    static String resolve(String key, Optional<ActiveProfileService.ActiveProfile> profile, String fallback) {
        if (profile.isEmpty()) {
            return fallback;
        }
        ActiveProfileService.ActiveProfile active = profile.get();
        @Nullable String raw = switch (key) {
            case "profile" -> active.name();
            case "profile_type" -> active.type();
            case "profile_role" -> active.role();
            default -> null;
        };
        return raw == null || raw.isEmpty() ? fallback : PlaceholderValues.sanitizePlayerText(raw);
    }
}
