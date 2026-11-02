package dev.yanianz.atrium.api.nick;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Administrative nickname access published by the {@code nick} module.
 *
 * <p>The nickname argument is MiniMessage source and is validated against the module's configured rules
 * (length, allowed characters, blocked words). Only colour, decoration, gradient and reset tags are
 * interpreted; every other tag stays literal text. It exists for import and maintenance tools, not for
 * player-facing features. Implementations are thread-safe and every write completes only after it was
 * stored.
 */
public interface NickService {
    /** The stored nickname of a player, or empty when they have none. */
    CompletableFuture<Optional<NickInfo>> nick(UUID player);

    /**
     * Stores a nickname. When the player already has one and {@code overwrite} is false,
     * {@link Result#SKIPPED} is returned and nothing changes.
     */
    CompletableFuture<Result> setNick(UUID player, String nickname, boolean overwrite);

    /** Removes the stored nickname. */
    CompletableFuture<Result> clearNick(UUID player);

    /** Validates a nickname against the current rules without touching storage. */
    Result validate(String nickname);

    enum Result {
        SUCCESS,
        /** The player already has a nickname and overwrite was not requested. */
        SKIPPED,
        /** Another player already uses this visible name. */
        TAKEN,
        /** Empty, malformed or rejected by the nickname rules. */
        INVALID
    }

    /** One stored nickname: its MiniMessage source and its visible plain text. */
    record NickInfo(String nickname, String plain, long updatedAt) {
        public NickInfo {
            Objects.requireNonNull(nickname, "nickname");
            Objects.requireNonNull(plain, "plain");
        }
    }
}
