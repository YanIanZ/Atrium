package dev.yanianz.atrium.module.ranks;

import java.util.LinkedHashSet;
import java.util.Set;

/** Immutable per-player rank state. {@code rewardedRanks} is the anti-farming ledger. */
record RankProfile(String rankId, Set<String> rewardedRanks) {
    RankProfile {
        rewardedRanks = Set.copyOf(rewardedRanks);
    }

    static RankProfile initial(String startingRank) {
        return new RankProfile(startingRank, Set.of());
    }

    RankProfile withRank(String id) {
        return new RankProfile(id, rewardedRanks);
    }

    RankProfile markRewarded(String id) {
        if (rewardedRanks.contains(id)) {
            return this;
        }
        Set<String> next = new LinkedHashSet<>(rewardedRanks);
        next.add(id);
        return new RankProfile(rankId, next);
    }

    boolean isRewarded(String id) {
        return rewardedRanks.contains(id);
    }
}
