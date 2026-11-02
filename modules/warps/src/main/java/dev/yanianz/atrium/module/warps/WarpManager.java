package dev.yanianz.atrium.module.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.warps.WarpCreateEvent;
import dev.yanianz.atrium.api.warps.WarpDeleteEvent;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * In-memory warps backed by {@link WarpStorage}. Every change follows the same order:
 * validate → call the cancellable event → write storage → update memory → report. A cancelled event
 * or a failed write leaves memory and storage exactly as they were, so a cancelled rename can never
 * lose the original warp.
 *
 * <p>Two concurrent commands can never create the same name twice: a create reserves its name until its
 * write finished, and the write that stores the warp also releases that reservation and the owner's
 * pending slot. A concurrent create therefore always observes either the stored warp or the still-counted
 * slot — never neither — so it cannot pass the owner limit by accident.
 */
final class WarpManager {
    enum Outcome { CREATED, MOVED, DELETED, RENAMED, CANCELLED, NOT_FOUND, EXISTS, INVALID_NAME, INVALID_PERMISSION, LIMIT_REACHED }

    private final Map<String, Warp> warps = new ConcurrentHashMap<>();
    /** Names whose create is in flight, so two concurrent creates of one name cannot both write. */
    private final Set<String> pendingNames = ConcurrentHashMap.newKeySet();
    /** Creates that passed the limit check but are not stored yet, per owner. */
    private final Map<UUID, Integer> pendingCreates = new ConcurrentHashMap<>();
    /** Guards the limit check and every memory update together (see the class comment). */
    private final Object lock = new Object();
    private final WarpStorage storage;
    private final Predicate<WarpCreateEvent> createGate;
    private final Predicate<WarpDeleteEvent> deleteGate;

    /**
     * @param createGate calls the event and returns true when it was <em>not</em> cancelled
     * @param deleteGate same for deletions
     */
    WarpManager(WarpStorage storage, Predicate<WarpCreateEvent> createGate, Predicate<WarpDeleteEvent> deleteGate) {
        this.storage = storage;
        this.createGate = createGate;
        this.deleteGate = deleteGate;
    }

    CompletableFuture<Void> load() {
        return storage.loadAll().thenAccept(list -> {
            warps.clear();
            list.forEach(w -> warps.put(w.key(), w));
        });
    }

    Optional<Warp> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(warps.get(Warp.key(name)));
    }

    Collection<Warp> all() {
        return List.copyOf(warps.values());
    }

    /** How many warps {@code owner} created. */
    int ownedBy(UUID owner) {
        int count = 0;
        for (Warp warp : warps.values()) {
            if (owner.equals(warp.creator())) {
                count++;
            }
        }
        return count;
    }

    /** Creates a warp, or moves an existing one (keeping its creator and creation time). No create limit. */
    CompletableFuture<Outcome> set(CommandSender actor, String name, String category, StoredLocation location,
            @Nullable UUID creator) {
        return set(actor, name, category, location, creator, Integer.MAX_VALUE);
    }

    /**
     * Creates a warp, or moves an existing one. {@code createLimit} only applies to a new warp owned by
     * {@code creator}; a player over the limit gets {@link Outcome#LIMIT_REACHED} and nothing is written.
     * Moving or renaming keeps the current owner and never counts against the limit again.
     */
    CompletableFuture<Outcome> set(CommandSender actor, String name, String category, StoredLocation location,
            @Nullable UUID creator, int createLimit) {
        return set(actor, name, category, location, "", creator, createLimit);
    }

    /**
     * Creates a warp, or moves an existing one. {@code permission} is stored when non-blank (a new warp
     * starts with it); moving an existing warp without one keeps the permission it already had.
     */
    CompletableFuture<Outcome> set(CommandSender actor, String name, String category, StoredLocation location,
            String permission, @Nullable UUID creator, int createLimit) {
        if (!Warp.validName(name) || !Warp.validName(category)) {
            return CompletableFuture.completedFuture(Outcome.INVALID_NAME);
        }
        String storedPermission = permission == null ? "" : permission.strip();
        if (!storedPermission.isEmpty() && !Warp.validPermission(storedPermission)) {
            return CompletableFuture.completedFuture(Outcome.INVALID_PERMISSION);
        }
        String key = Warp.key(name);
        Optional<Warp> existing = get(name);
        if (existing.isPresent()) {
            // An explicit update: no name reservation and no create limit. A limited player may only move
            // their own warps, even when another player created this warp after the caller checked it.
            if (!mayOverwrite(existing.get(), creator, createLimit)) {
                return CompletableFuture.completedFuture(Outcome.EXISTS);
            }
            Warp updated = existing.get().moved(location, category);
            if (!storedPermission.isEmpty()) {
                updated = updated.withPermission(storedPermission);
            }
            return overwrite(actor, updated);
        }
        // The name is ours until the write finished: a concurrent create must answer EXISTS, never upsert.
        if (!pendingNames.add(key)) {
            return CompletableFuture.completedFuture(Outcome.EXISTS);
        }
        Warp warp = new Warp(name, category, location, creator, System.currentTimeMillis(), storedPermission);
        boolean limited = creator != null && createLimit != Integer.MAX_VALUE;
        // Check before calling the event, so plugins are not told about a create that cannot happen.
        // The check is repeated under the lock because another create may have started since.
        if (limited && overLimit(creator, createLimit)) {
            pendingNames.remove(key);
            return CompletableFuture.completedFuture(Outcome.LIMIT_REACHED);
        }
        if (!createGate.test(new WarpCreateEvent(actor, warp.name(), null, location, category))) {
            pendingNames.remove(key);
            return CompletableFuture.completedFuture(Outcome.CANCELLED);
        }
        synchronized (lock) {
            // A rename or an import may have added this name while the event ran.
            if (warps.containsKey(key)) {
                pendingNames.remove(key);
                return CompletableFuture.completedFuture(Outcome.EXISTS);
            }
            if (limited) {
                if (overLimit(creator, createLimit)) {
                    pendingNames.remove(key);
                    return CompletableFuture.completedFuture(Outcome.LIMIT_REACHED);
                }
                pendingCreates.merge(creator, 1, Integer::sum);
            }
        }
        return storage.upsert(warp).handle((ignored, error) -> {
            // The warp and its reservations change in one step: a concurrent create must observe either the
            // stored warp or the pending slot that is still counted against the owner's limit.
            synchronized (lock) {
                if (error == null) {
                    warps.put(key, warp);
                }
                pendingNames.remove(key);
                if (limited) {
                    releaseSlot(creator);
                }
            }
            if (error != null) {
                throw rethrow(error);
            }
            return Outcome.CREATED;
        });
    }

    /** Writes an explicit update of a warp that already exists (no create limit, no name reservation). */
    private CompletableFuture<Outcome> overwrite(CommandSender actor, Warp warp) {
        if (!createGate.test(new WarpCreateEvent(actor, warp.name(), null, warp.location(), warp.category()))) {
            return CompletableFuture.completedFuture(Outcome.CANCELLED);
        }
        return storage.upsert(warp).handle((ignored, error) -> {
            if (error != null) {
                throw rethrow(error);
            }
            synchronized (lock) {
                warps.put(warp.key(), warp);
            }
            return Outcome.MOVED;
        });
    }

    /** A finite create limit means "your own warps only"; staff and the API (no limit) may overwrite any. */
    private static boolean mayOverwrite(Warp existing, @Nullable UUID creator, int createLimit) {
        return creator == null || createLimit == Integer.MAX_VALUE || creator.equals(existing.creator());
    }

    private boolean overLimit(@Nullable UUID creator, int createLimit) {
        if (creator == null || createLimit == Integer.MAX_VALUE) {
            return false;
        }
        synchronized (lock) {
            int used = ownedBy(creator) + pendingCreates.getOrDefault(creator, 0);
            return used >= Math.max(0, createLimit);
        }
    }

    private void releaseSlot(@Nullable UUID creator) {
        if (creator == null) {
            return;
        }
        synchronized (lock) {
            pendingCreates.computeIfPresent(creator, (key, count) -> count <= 1 ? null : count - 1);
        }
    }

    private static RuntimeException rethrow(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause instanceof RuntimeException runtime ? runtime : new CompletionException(cause);
    }

    CompletableFuture<Outcome> delete(CommandSender actor, String name) {
        Optional<Warp> existing = get(name);
        if (existing.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NOT_FOUND);
        }
        if (!deleteGate.test(new WarpDeleteEvent(actor, existing.get().name()))) {
            return CompletableFuture.completedFuture(Outcome.CANCELLED);
        }
        return storage.delete(existing.get().key()).thenApply(deleted -> {
            warps.remove(existing.get().key(), existing.get());
            return deleted ? Outcome.DELETED : Outcome.NOT_FOUND;
        });
    }

    CompletableFuture<Outcome> importWarp(CommandSender actor, String name, String category,
            StoredLocation location, @Nullable UUID creator, boolean overwrite) {
        return importWarp(actor, name, category, location, null, creator, overwrite);
    }

    /**
     * Import path used by {@code WarpAdminService}: validates the name with the same rules as the
     * commands, reports an existing warp without writing when {@code overwrite} is false, and otherwise
     * creates or moves it through {@link #set}. A non-blank permission is stored on the warp.
     */
    CompletableFuture<Outcome> importWarp(CommandSender actor, String name, String category,
            StoredLocation location, @Nullable String permission, @Nullable UUID creator, boolean overwrite) {
        if (!Warp.validName(name) || !Warp.validName(category)) {
            return CompletableFuture.completedFuture(Outcome.INVALID_NAME);
        }
        String storedPermission = permission == null ? "" : permission.strip();
        if (!storedPermission.isEmpty() && !Warp.validPermission(storedPermission)) {
            return CompletableFuture.completedFuture(Outcome.INVALID_PERMISSION);
        }
        if (!overwrite && get(name).isPresent()) {
            return CompletableFuture.completedFuture(Outcome.EXISTS);
        }
        return set(actor, name, category, location, storedPermission, creator, Integer.MAX_VALUE);
    }

    /**
     * Full import used by {@code WarpImportService}: the warp is stored exactly as given (owner, permission
     * and creation time included). A new warp goes through the normal create path; an existing one is
     * replaced only with {@code overwrite}.
     */
    CompletableFuture<Outcome> importFull(CommandSender actor, Warp warp, boolean overwrite) {
        if (!Warp.validName(warp.name()) || !Warp.validName(warp.category())) {
            return CompletableFuture.completedFuture(Outcome.INVALID_NAME);
        }
        if (!warp.derivedPermission() && !Warp.validPermission(warp.storedPermission())) {
            return CompletableFuture.completedFuture(Outcome.INVALID_PERMISSION);
        }
        Optional<Warp> existing = get(warp.name());
        if (existing.isPresent()) {
            if (!overwrite) {
                return CompletableFuture.completedFuture(Outcome.EXISTS);
            }
            // Keep the stored spelling of the name so the row key does not change.
            Warp replaced = new Warp(existing.get().name(), warp.category(), warp.location(), warp.creator(),
                    warp.createdAt(), warp.storedPermission());
            return overwrite(actor, replaced);
        }
        String key = warp.key();
        if (!pendingNames.add(key)) {
            return CompletableFuture.completedFuture(Outcome.EXISTS);
        }
        if (!createGate.test(new WarpCreateEvent(actor, warp.name(), null, warp.location(), warp.category()))) {
            pendingNames.remove(key);
            return CompletableFuture.completedFuture(Outcome.CANCELLED);
        }
        synchronized (lock) {
            if (warps.containsKey(key)) {
                pendingNames.remove(key);
                return CompletableFuture.completedFuture(Outcome.EXISTS);
            }
        }
        return storage.upsert(warp).handle((ignored, error) -> {
            synchronized (lock) {
                if (error == null) {
                    warps.put(key, warp);
                }
                pendingNames.remove(key);
            }
            if (error != null) {
                throw rethrow(error);
            }
            return Outcome.CREATED;
        });
    }

    /**
     * Sets (or clears, with a blank node) the custom permission of an existing warp. It is an ordinary
     * update: the create gate runs first, storage is written, then memory changes.
     */
    CompletableFuture<Outcome> setPermission(CommandSender actor, String name, String permission) {
        Optional<Warp> existing = get(name);
        if (existing.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NOT_FOUND);
        }
        String stored = permission == null ? "" : permission.strip();
        if (!stored.isEmpty() && !Warp.validPermission(stored)) {
            return CompletableFuture.completedFuture(Outcome.INVALID_PERMISSION);
        }
        if (stored.equals(existing.get().storedPermission())) {
            return CompletableFuture.completedFuture(Outcome.MOVED);
        }
        return overwrite(actor, existing.get().withPermission(stored));
    }

    /**
     * Renames a warp. The create event for the new name is called first; nothing is removed unless it
     * passed and the single rename statement succeeded. Changing only the letter case is allowed.
     */
    CompletableFuture<Outcome> rename(CommandSender actor, String oldName, String newName) {
        if (!Warp.validName(newName)) {
            return CompletableFuture.completedFuture(Outcome.INVALID_NAME);
        }
        Optional<Warp> existing = get(oldName);
        if (existing.isEmpty()) {
            return CompletableFuture.completedFuture(Outcome.NOT_FOUND);
        }
        Warp old = existing.get();
        String newKey = Warp.key(newName);
        boolean sameKey = newKey.equals(old.key());
        // The new name is reserved while the rename is in flight, exactly like a create.
        boolean reserved = !sameKey;
        if (reserved) {
            if (!pendingNames.add(newKey)) {
                return CompletableFuture.completedFuture(Outcome.EXISTS);
            }
            if (warps.containsKey(newKey)) {
                pendingNames.remove(newKey);
                return CompletableFuture.completedFuture(Outcome.EXISTS);
            }
        }
        Warp renamed = old.renamed(newName);
        if (!createGate.test(new WarpCreateEvent(actor, newName, old.name(), old.location(), old.category()))) {
            if (reserved) {
                pendingNames.remove(newKey);
            }
            return CompletableFuture.completedFuture(Outcome.CANCELLED);
        }
        return storage.rename(old.key(), renamed).handle((done, error) -> {
            synchronized (lock) {
                if (error == null && Boolean.TRUE.equals(done)) {
                    warps.remove(old.key(), old);
                    warps.put(newKey, renamed);
                }
                if (reserved) {
                    pendingNames.remove(newKey);
                }
            }
            if (error != null) {
                throw rethrow(error);
            }
            return Boolean.TRUE.equals(done) ? Outcome.RENAMED : Outcome.NOT_FOUND;
        });
    }
}
