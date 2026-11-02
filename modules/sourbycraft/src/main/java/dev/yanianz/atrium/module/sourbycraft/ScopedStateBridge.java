package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.api.profile.ProfileScopedData;
import dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Captures and restores the configured profile-scoped data of one player as one {@link ProfileEnvelope}.
 * Runs on the player's entity thread inside SourbyCraft's capture/apply task: everything here is synchronous,
 * in-memory and storage-free. Persisting a restored value happens in the background; its failure is logged
 * and retried by Atrium's save loop (the value stays dirty), it never blocks the switch.
 *
 * <p>Every envelope records the profile the data belongs to ({@link ProfileEnvelope#OWNER_ENTRY}); see
 * {@link ProfileOwnership}.
 *
 * <p>Restore order: the stored data is decoded and every entry's version checked <em>before</em> anything is
 * changed, so a corrupt or newer snapshot fails without touching the player's data.
 */
final class ScopedStateBridge {
    private final ProfileScopedDataRegistry registry;
    private final Supplier<List<String>> scopes;
    private final Supplier<ProfileScopes.MissingState> missingState;
    private final Logger logger;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    ScopedStateBridge(ProfileScopedDataRegistry registry, Supplier<List<String>> scopes,
            Supplier<ProfileScopes.MissingState> missingState, Logger logger) {
        this.registry = registry;
        this.scopes = scopes;
        this.missingState = missingState;
        this.logger = logger;
    }

    /** Configured ids whose provider is currently registered, in config order. */
    List<ProfileScopedData> activeProviders() {
        List<ProfileScopedData> providers = new ArrayList<>();
        for (String id : scopes.get()) {
            Optional<ProfileScopedData> provider = registry.get(id);
            if (provider.isPresent()) {
                providers.add(provider.get());
            } else if (warned.add("missing:" + id)) {
                logger.warn("profile-scoped lists '{}' but no enabled Atrium module provides it; it is not stored "
                        + "with profiles", id);
            }
        }
        return providers;
    }

    /** Result of a restore: the profile the restored data belongs to (null = not recorded) and its storage. */
    record Restored(@Nullable UUID owner, CompletableFuture<Void> stored) {}

    /**
     * The player's profile-scoped state, tagged with the profile it belongs to ({@code owner}, may be null when
     * unknown). Never null or empty, also without any data: SourbyCraft skips an adapter whose capture is
     * empty and then never calls {@code apply} for it, so an explicit "empty" envelope is what makes a profile
     * without homes restore as "no homes" instead of keeping the previous profile's.
     */
    byte[] capture(UUID player, @Nullable UUID owner) throws Exception {
        Map<String, ProfileEnvelope.Entry> entries = new LinkedHashMap<>();
        for (ProfileScopedData provider : activeProviders()) {
            byte[] data;
            try {
                data = provider.capture(player);
            } catch (Exception e) {
                throw new IllegalStateException("Capturing Atrium '" + provider.id() + "' data of " + player + " failed", e);
            }
            entries.put(provider.id(), new ProfileEnvelope.Entry(provider.version(), data));
        }
        return ProfileEnvelope.encode(ProfileEnvelope.withOwner(entries, owner));
    }

    /** Restores stored state; configured data absent from the snapshot starts empty. */
    Restored apply(UUID player, byte[] data) throws Exception {
        Map<String, ProfileEnvelope.Entry> stored = ProfileEnvelope.decode(data);
        UUID owner = ProfileEnvelope.owner(stored);
        List<ProfileScopedData> providers = activeProviders();
        for (ProfileScopedData provider : providers) {
            ProfileEnvelope.Entry entry = stored.get(provider.id());
            if (entry != null && entry.version() > provider.version()) {
                throw new IllegalStateException("Stored Atrium '" + provider.id() + "' data has version " + entry.version()
                        + ", this Atrium understands up to " + provider.version() + "; refusing to restore it");
            }
        }
        List<Exception> failures = new ArrayList<>();
        List<CompletableFuture<Void>> stores = new ArrayList<>();
        for (ProfileScopedData provider : providers) {
            ProfileEnvelope.Entry entry = stored.get(provider.id());
            restore(player, provider, entry == null ? null : entry.data(), failures, stores);
        }
        throwIfFailed(player, failures);
        return new Restored(owner, all(stores));
    }

    /**
     * A profile without Atrium data (extended API {@code applyMissing}): start empty, or keep the live data
     * with {@code KEEP}. The owner is not known here.
     */
    Restored applyMissing(UUID player) throws Exception {
        if (missingState.get() == ProfileScopes.MissingState.KEEP) {
            return new Restored(null, CompletableFuture.completedFuture(null));
        }
        return new Restored(null, clear(player));
    }

    /** Sets every configured data of the player to empty; the future completes once that is stored. */
    CompletableFuture<Void> clear(UUID player) throws Exception {
        List<Exception> failures = new ArrayList<>();
        List<CompletableFuture<Void>> stores = new ArrayList<>();
        for (ProfileScopedData provider : activeProviders()) {
            restore(player, provider, null, failures, stores);
        }
        throwIfFailed(player, failures);
        return all(stores);
    }

    private void restore(UUID player, ProfileScopedData provider, byte @Nullable [] data, List<Exception> failures,
            List<CompletableFuture<Void>> stores) {
        CompletableFuture<Void> stored;
        try {
            stored = provider.apply(player, data);
        } catch (Exception e) {
            failures.add(new IllegalStateException("Restoring Atrium '" + provider.id() + "' data failed", e));
            return;
        }
        if (stored != null) {
            stores.add(stored.whenComplete((ignored, error) -> {
                if (error != null) {
                    logger.error("Storing the restored '{}' data of {} failed; it stays in memory and is retried",
                            provider.id(), player, error);
                }
            }));
        }
    }

    private static CompletableFuture<Void> all(List<CompletableFuture<Void>> stores) {
        return CompletableFuture.allOf(stores.toArray(CompletableFuture[]::new));
    }

    private static void throwIfFailed(UUID player, List<Exception> failures) {
        if (failures.isEmpty()) {
            return;
        }
        IllegalStateException error = new IllegalStateException(
                "Restoring Atrium profile data of " + player + " failed for " + failures.size() + " entr(y/ies)",
                failures.getFirst());
        for (int i = 1; i < failures.size(); i++) {
            error.addSuppressed(failures.get(i));
        }
        throw error;
    }
}
