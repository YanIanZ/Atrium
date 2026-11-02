package dev.yanianz.atrium.module.npcs;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;

/**
 * Resolves skins through Paper's profile service and caches the signed texture in {@code npcs_skin_cache}.
 * Atrium never fetches a texture URL itself; the client loads the skin from the value Mojang signed.
 *
 * <p>Lookups run on the I/O executor (never a region thread). A failed lookup falls back to a stale cache
 * row; when there is none the NPC is still spawned without a custom skin, so a Mojang outage never hides an
 * NPC. The same name is looked up only once at a time.
 */
final class NpcSkinResolver {
    private static final Pattern ACCOUNT = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final String TEXTURES = "textures";

    record ResolvedSkin(String name, @Nullable UUID profileId, @Nullable String value, @Nullable String signature) {
        boolean hasTexture() {
            return value != null && !value.isEmpty();
        }
    }

    private final ModuleContext ctx;
    private final NpcStore store;
    private final Supplier<NpcsConfig> config;
    private final Map<String, CompletableFuture<Optional<ResolvedSkin>>> inFlight = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    NpcSkinResolver(ModuleContext ctx, NpcStore store, Supplier<NpcsConfig> config) {
        this.ctx = ctx;
        this.store = store;
        this.config = config;
    }

    static boolean validAccount(String name) {
        return name != null && ACCOUNT.matcher(name).matches();
    }

    static boolean wellFormed(String value) {
        if (value == null || value.isEmpty() || value.length() > 32_768) {
            return false;
        }
        try {
            return Base64.getDecoder().decode(value).length > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Resolves a name; completes empty when the account has no texture or the lookup failed. Never fails. */
    CompletableFuture<Optional<ResolvedSkin>> resolve(String name) {
        if (!validAccount(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String key = name.toLowerCase(Locale.ROOT);
        CompletableFuture<Optional<ResolvedSkin>> created = new CompletableFuture<>();
        CompletableFuture<Optional<ResolvedSkin>> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        created.whenComplete((result, error) -> inFlight.remove(key, created));
        lookup(name).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().warn("Skin lookup for NPC skin '{}' failed: {}", name, error.toString());
                created.complete(Optional.empty());
            } else {
                created.complete(result);
            }
        });
        return created;
    }

    private CompletableFuture<Optional<ResolvedSkin>> lookup(String name) {
        long now = System.currentTimeMillis();
        Duration ttl = Duration.ofHours(Math.max(1, Math.min(8760, config.get().skinCacheHours)));
        return store.cachedSkin(name).thenCompose(cached -> {
            if (cached.isPresent() && now - cached.get().fetchedAt() < ttl.toMillis()) {
                return CompletableFuture.completedFuture(Optional.of(fromCache(cached.get())));
            }
            return fetch(name).handle((fetched, error) -> {
                if (error == null) {
                    return fetched;
                }
                if (cached.isPresent()) {
                    ctx.logger().warn("Skin lookup for '{}' failed ({}); using the cached texture", name,
                            error.toString());
                    return Optional.of(fromCache(cached.get()));
                }
                ctx.logger().warn("Skin lookup for '{}' failed: {}", name, error.toString());
                return Optional.<ResolvedSkin>empty();
            });
        });
    }

    private static ResolvedSkin fromCache(NpcStore.CachedSkin cached) {
        return new ResolvedSkin(cached.name(), cached.profileId(), cached.value(), cached.signature());
    }

    /** Asks Paper's profile service. Runs entirely off the region threads. */
    private CompletableFuture<Optional<ResolvedSkin>> fetch(String name) {
        int timeout = Math.max(1, Math.min(60, config.get().skinTimeoutSeconds));
        return ctx.scheduler().supplyIo(() -> Bukkit.createProfile(name))
                .thenCompose(PlayerProfile::update)
                .orTimeout(timeout, TimeUnit.SECONDS)
                .thenCompose(profile -> {
                    String properName = validAccount(profile.getName()) ? profile.getName() : name;
                    UUID id = profile.getId();
                    String value = null;
                    String signature = null;
                    for (ProfileProperty property : profile.getProperties()) {
                        if (!TEXTURES.equals(property.getName())) {
                            continue;
                        }
                        if (wellFormed(property.getValue())) {
                            value = property.getValue();
                            signature = property.getSignature();
                            break;
                        }
                        ctx.logger().warn("Profile '{}' returned a malformed textures property; ignoring it", properName);
                    }
                    ResolvedSkin resolved = new ResolvedSkin(properName, id, value, signature);
                    if (!resolved.hasTexture()) {
                        return CompletableFuture.completedFuture(Optional.<ResolvedSkin>empty());
                    }
                    NpcStore.CachedSkin row = new NpcStore.CachedSkin(properName, id, value, signature,
                            System.currentTimeMillis());
                    return store.putCachedSkin(row).handle((ignored, error) -> {
                        if (error != null) {
                            ctx.logger().warn("Could not cache the skin of '{}'", properName, error);
                        }
                        return Optional.of(resolved);
                    });
                });
    }

    void stop() {
        stopping = true;
        inFlight.clear();
    }

    boolean stopping() {
        return stopping;
    }
}
