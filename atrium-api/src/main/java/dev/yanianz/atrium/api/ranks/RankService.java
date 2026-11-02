package dev.yanianz.atrium.api.ranks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Read-only view of the rank ladder configured in the {@code ranks} module. */
public interface RankService {
    /** Rank ids in ladder order (lowest first). */
    List<String> ranks();

    /** Rank id given to a player who has no stored rank. */
    String startingRank();

    /**
     * Rank id of a player, or the starting rank when none is stored. The future fails when storage
     * is unavailable.
     */
    CompletableFuture<Optional<String>> currentRank(UUID player);
}
