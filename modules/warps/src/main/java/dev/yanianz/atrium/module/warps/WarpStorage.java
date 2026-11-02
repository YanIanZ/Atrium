package dev.yanianz.atrium.module.warps;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Persistence of warps. Every method is a single atomic write. */
interface WarpStorage {
    CompletableFuture<List<Warp>> loadAll();

    CompletableFuture<Void> upsert(Warp warp);

    CompletableFuture<Boolean> delete(String key);

    /** Renames in one statement; the old row is never deleted on its own. */
    CompletableFuture<Boolean> rename(String oldKey, Warp renamed);
}
