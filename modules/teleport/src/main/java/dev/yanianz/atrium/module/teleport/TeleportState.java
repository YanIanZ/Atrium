package dev.yanianz.atrium.module.teleport;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted per-player teleport state. Immutable; change with the {@code with*} methods.
 *
 * @param back            /back history, newest first
 * @param acceptsRequests false after /tptoggle: /tpa, /tpahere and /tp to this player are refused
 * @param blockedRequests players whose teleport requests are silently dropped (/tpablock)
 */
public record TeleportState(BackHistory back, boolean acceptsRequests, Set<UUID> blockedRequests) {
    public static final TeleportState DEFAULT = new TeleportState(BackHistory.EMPTY, true, Set.of());

    public TeleportState {
        if (back == null) {
            back = BackHistory.EMPTY;
        }
        blockedRequests = blockedRequests == null ? Set.of() : Set.copyOf(blockedRequests);
    }

    /** Kept for callers that only know the /back slot and the request toggle. */
    public TeleportState(BackHistory back, boolean acceptsRequests) {
        this(back, acceptsRequests, Set.of());
    }

    public TeleportState withBack(BackHistory history) {
        return new TeleportState(history, acceptsRequests, blockedRequests);
    }

    public TeleportState withAcceptsRequests(boolean value) {
        return new TeleportState(back, value, blockedRequests);
    }

    public TeleportState withBlockedRequests(Set<UUID> blocked) {
        return new TeleportState(back, acceptsRequests, blocked);
    }

    /** True when {@code requester} is on this player's block list. */
    public boolean blocks(UUID requester) {
        return blockedRequests.contains(requester);
    }

    /** Comma-separated UUIDs in a stable order, or an empty string for an empty list. */
    public String encodeBlockedRequests() {
        if (blockedRequests.isEmpty()) {
            return "";
        }
        return String.join(",", blockedRequests.stream().map(UUID::toString).sorted().toList());
    }

    /** Reads {@link #encodeBlockedRequests()}; malformed entries are skipped instead of failing the load. */
    public static Set<UUID> decodeBlockedRequests(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Set.of();
        }
        Set<UUID> result = new LinkedHashSet<>();
        for (String part : encoded.split(",")) {
            String value = part.strip().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            try {
                result.add(UUID.fromString(value));
            } catch (IllegalArgumentException e) {
                // A malformed entry is dropped; the rest of the list stays usable.
            }
        }
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }
}
