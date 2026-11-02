package dev.yanianz.atrium.api.chat;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Administrative, additive writes to the per-player ignore lists published by the {@code chat} module next
 * to {@link IgnoreService}. The importer uses it to carry ignore lists over from another plugin.
 *
 * <p>Only entries are ever added, never removed: calling this twice with the same list stores exactly the
 * same result, and an ignore a player added after an import is never undone by a later run. The owner's own
 * UUID and {@code null} entries are dropped, matching {@code /ignore} (which refuses to ignore yourself).
 *
 * <p>The completion happens after the change was stored, so a caller may report success only then. When the
 * player is online the write goes through the same atomic component update as {@code /ignore}, so it cannot
 * lose an entry the player adds at the same moment.
 */
public interface IgnoreAdminService {
    /**
     * Adds {@code ignored} to the list of {@code owner}.
     *
     * @return a future that completes with the number of entries that were not ignored before; {@code 0}
     *         means every entry was already there (nothing changed, nothing failed)
     * @throws IllegalArgumentException (as a failed future) when {@code owner} is {@code null}
     * @throws IllegalStateException (as a failed future) when Atrium has no account for {@code owner}
     */
    CompletableFuture<Integer> importIgnores(UUID owner, Collection<UUID> ignored);
}
