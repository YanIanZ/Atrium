package dev.yanianz.atrium.module.teleport;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Respawn destination order: the first token in the configured list that is available wins. Pure logic,
 * so the ordering rules are unit tested without a server.
 */
final class RespawnPriority {
    /** Respawn anchor (the vanilla anchor spawn). */
    static final String ANCHOR = "anchor";
    /** Bed spawn. */
    static final String BED = "bed";
    /** A spawn set with /setspawn (world or default). */
    static final String SPAWN = "spawn";
    /** The player's home, when the homes module is installed. */
    static final String HOME = "home";
    /** The vanilla world spawn. */
    static final String WORLD_SPAWN = "worldspawn";
    /** Prefix for a named warp, e.g. {@code warp:lobby}. */
    static final String WARP_PREFIX = "warp:";

    private RespawnPriority() {}

    /** The order for {@code worldName}: its own entry, else {@code "*"}, else the built-in default. */
    static List<String> orderFor(Map<String, List<String>> orders, String worldName) {
        if (orders != null && !orders.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : orders.entrySet()) {
                if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(worldName)) {
                    List<String> value = entry.getValue();
                    if (value != null && !value.isEmpty()) {
                        return normalise(value);
                    }
                }
            }
            for (Map.Entry<String, List<String>> entry : orders.entrySet()) {
                if (entry.getKey() != null && entry.getKey().trim().equals("*")) {
                    List<String> value = entry.getValue();
                    if (value != null && !value.isEmpty()) {
                        return normalise(value);
                    }
                }
            }
        }
        return List.of(ANCHOR, BED, SPAWN, HOME, WORLD_SPAWN);
    }

    /** Lowercase, trimmed tokens; unknown tokens are kept so the caller can log them. */
    static List<String> normalise(List<String> tokens) {
        return tokens.stream()
                .filter(token -> token != null && !token.isBlank())
                .map(token -> token.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    /** The first chosen token, or empty when no token is available. */
    static Optional<String> choose(List<String> order, Predicate<String> available) {
        for (String token : normalise(order)) {
            if (available.test(token)) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    /** Warp name of a {@code warp:<name>} token, empty for every other token. */
    static Optional<String> warpName(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String lower = token.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith(WARP_PREFIX)) {
            return Optional.empty();
        }
        String name = token.trim().substring(WARP_PREFIX.length()).trim();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }
}
