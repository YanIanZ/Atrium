package dev.yanianz.atrium.module.skins;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * {@code /skin <name> [player]} and {@code /skin reset [player]}.
 *
 * <p>Textures come only from {@code Bukkit.createProfile(name).update()} (Paper's profile service, run off the
 * region threads); no URL is ever fetched by Atrium. Results are cached in {@code skins_cache} with an expiry, and
 * the chosen texture is persisted per player ({@code skins_choice}) so it is re-applied on join without a lookup.
 *
 * <p>Applying uses {@link Player#setPlayerProfile} on the player's own thread. Side effects (Paper behaviour): the
 * player is hidden from and re-shown to every other player (a short flicker, and they are re-added to the tab
 * list) and the player's own client is refreshed (open screens close). The player keeps their UUID and name; only
 * the {@code textures} property changes. Atrium does not dismount or teleport the player. On offline-mode servers
 * the profile service returns no textures, so lookups report "not found".
 */
public final class SkinsModule implements AtriumModule, Listener {
    static final String PERM_SKIN = "atrium.skin";
    static final String PERM_OTHERS = "atrium.skin.others";
    static final String PERM_BYPASS_COOLDOWN = "atrium.skin.bypass-cooldown";
    static final String COOLDOWN_KEY = "skins:change";
    private static final String TEXTURES = "textures";

    /** A resolved skin: the account name in its proper case and its texture. */
    record Resolved(String name, SkinTexture texture) {}

    private ModuleContext ctx;
    private ConfigHandle<SkinsConfig> config;
    private SkinStore store;
    private volatile boolean stopping;
    /** Textures each player logged in with, captured before any custom skin is applied. Empty = no textures. */
    private final Map<UUID, Optional<SkinTexture>> originals = new ConcurrentHashMap<>();
    /** One lookup per name at a time; concurrent requests share it. */
    private final Map<String, CompletableFuture<Optional<Resolved>>> inFlight = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "skins";
    }

    @Override
    public String description() {
        return "Use another account's skin, cached and re-applied on join";
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.stopping = false;
        this.config = context.config(SkinsConfig.class, SkinsConfig::new);
        context.migrate(SkinStore.MIGRATIONS);
        this.store = new SkinStore(context.database(), message -> context.logger().warn(message));
        context.component(store.component);
        context.listen(this);

        context.permission(PERM_SKIN, PermissionDefault.OP, "Change or reset your own skin");
        context.permission(PERM_OTHERS, PermissionDefault.OP, "Change or reset another player's skin");
        context.permission(PERM_BYPASS_COOLDOWN, PermissionDefault.OP, "Change skins without waiting");

        SkinCommands commands = new SkinCommands(this);
        commands.register();
        context.track(commands);

        purgeOldCache();
        // Players already online (module enabled at runtime): only a player without a stored choice is known to
        // wear their own skin, so only then is the current texture their original.
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            context.scheduler().entityLater(player, () -> {
                SkinChoice choice = choiceOrNull(player);
                if (choice != null && !choice.custom()) {
                    originals.putIfAbsent(player.getUniqueId(), currentTexture(player));
                }
            }, null, 20L);
        }
    }

    @Override
    public void disable() {
        // Skins stay applied until the players reconnect: re-sending everyone on disable would flicker for nothing.
        stopping = true;
        inFlight.clear();
    }

    ModuleContext context() {
        return ctx;
    }

    SkinsConfig config() {
        return config.get();
    }

    SkinStore store() {
        return store;
    }

    boolean stopping() {
        return stopping;
    }

    Duration ttl() {
        return Duration.ofHours(Math.max(1, Math.min(8760, config.get().cacheHours)));
    }

    // ---- lookups --------------------------------------------------------------------------------------------

    /**
     * The texture of an account: from the cache while fresh, otherwise from the profile service. When the lookup
     * fails but a stale cache row exists, the stale texture is used. Completes empty when the account has no
     * texture (unknown name, or offline mode). Never call with an unvalidated name.
     */
    CompletableFuture<Optional<Resolved>> resolve(String name) {
        if (!SkinNames.valid(name)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid account name"));
        }
        String key = SkinNames.key(name);
        CompletableFuture<Optional<Resolved>> created = new CompletableFuture<>();
        CompletableFuture<Optional<Resolved>> existing = inFlight.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }
        created.whenComplete((result, error) -> inFlight.remove(key, created));
        lookup(name).whenComplete((result, error) -> {
            if (error != null) {
                created.completeExceptionally(error);
            } else {
                created.complete(result);
            }
        });
        return created;
    }

    private CompletableFuture<Optional<Resolved>> lookup(String name) {
        long now = System.currentTimeMillis();
        return store.cached(name).thenCompose(cached -> {
            if (cached.isPresent() && SkinNames.fresh(cached.get().fetchedAt(), now, ttl())) {
                SkinStore.CacheEntry entry = cached.get();
                return CompletableFuture.completedFuture(Optional.of(new Resolved(entry.name(), entry.texture())));
            }
            return fetch(name).handle((fetched, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(fetched);
                }
                if (cached.isPresent()) {
                    ctx.logger().warn("Skin lookup for '{}' failed ({}); using the expired cached texture", name,
                            error.toString());
                    SkinStore.CacheEntry entry = cached.get();
                    return CompletableFuture.completedFuture(
                            Optional.of(new Resolved(entry.name(), entry.texture())));
                }
                return CompletableFuture.<Optional<Resolved>>failedFuture(error);
            }).thenCompose(future -> future);
        });
    }

    /** Asks Paper's profile service. Runs entirely off the region threads. */
    private CompletableFuture<Optional<Resolved>> fetch(String name) {
        int timeout = Math.max(1, Math.min(60, config.get().fetchTimeoutSeconds));
        return ctx.scheduler().supplyIo(() -> Bukkit.createProfile(name))
                .thenCompose(PlayerProfile::update)
                .orTimeout(timeout, TimeUnit.SECONDS)
                .thenCompose(profile -> {
                    SkinTexture texture = texture(profile);
                    if (texture == null || profile.getId() == null) {
                        return CompletableFuture.completedFuture(Optional.<Resolved>empty());
                    }
                    String properName = SkinNames.valid(profile.getName()) ? profile.getName() : name;
                    Resolved resolved = new Resolved(properName, texture);
                    return store.putCache(new SkinStore.CacheEntry(properName, texture, System.currentTimeMillis()))
                            .handle((ignored, error) -> {
                                if (error != null) {
                                    ctx.logger().warn("Could not cache the skin of '{}'", properName, error);
                                }
                                return Optional.of(resolved);
                            });
                });
    }

    private static @Nullable SkinTexture texture(PlayerProfile profile) {
        for (ProfileProperty property : profile.getProperties()) {
            if (TEXTURES.equals(property.getName()) && SkinTexture.wellFormed(property.getValue())) {
                return new SkinTexture(property.getValue(), property.getSignature());
            }
        }
        return null;
    }

    // ---- applying (player thread only) ----------------------------------------------------------------------

    /** Replaces the textures property; {@code null} removes it (default skin). Call on the player's thread. */
    void apply(Player player, @Nullable SkinTexture texture) {
        PlayerProfile profile = player.getPlayerProfile();
        profile.removeProperty(TEXTURES);
        if (texture != null) {
            profile.setProperty(texture.signature() == null
                    ? new ProfileProperty(TEXTURES, texture.value())
                    : new ProfileProperty(TEXTURES, texture.value(), texture.signature()));
        }
        player.setPlayerProfile(profile);
    }

    private static Optional<SkinTexture> currentTexture(Player player) {
        return Optional.ofNullable(texture(player.getPlayerProfile()));
    }

    /** The texture the player logged in with, if it was captured this session. */
    Optional<Optional<SkinTexture>> original(UUID uuid) {
        return Optional.ofNullable(originals.get(uuid));
    }

    @Nullable SkinChoice choiceOrNull(Player player) {
        try {
            return ctx.users().user(player).get(store.component);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    // ---- join / quit ----------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (stopping) {
            return;
        }
        Player player = event.getPlayer();
        // Join runs on the player's thread: capture the login textures before anything changes them.
        originals.put(player.getUniqueId(), currentTexture(player));
        if (config.get().restoreOnJoin) {
            ctx.scheduler().entityLater(player, () -> restore(player, 3), null, 1L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        originals.remove(event.getPlayer().getUniqueId());
    }

    private void restore(Player player, int retries) {
        if (stopping || !player.isOnline()) {
            return;
        }
        SkinChoice choice = choiceOrNull(player);
        if (choice == null) {
            if (retries > 0) {
                ctx.scheduler().entityLater(player, () -> restore(player, retries - 1), null, 20L);
            } else {
                ctx.logger().warn("Skin of {} is still not loaded; not restored", player.getName());
            }
            return;
        }
        if (!choice.custom()) {
            return;
        }
        try {
            apply(player, choice.texture());
        } catch (RuntimeException e) {
            ctx.logger().error("Restoring the skin of {} failed", player.getName(), e);
            return;
        }
        if (config.get().refreshStaleOnJoin
                && !SkinNames.fresh(choice.updatedAt(), System.currentTimeMillis(), ttl())) {
            refresh(player, choice);
        }
    }

    private void refresh(Player player, SkinChoice stored) {
        resolve(stored.skinName()).whenComplete((result, error) -> {
            if (stopping) {
                return;
            }
            if (error != null) {
                ctx.logger().warn("Refreshing the stored skin '{}' of {} failed: {}", stored.skinName(),
                        player.getName(), error.toString());
                return;
            }
            if (result.isEmpty()) {
                return;
            }
            Resolved fresh = result.get();
            ctx.scheduler().entity(player, () -> {
                if (stopping) {
                    return;
                }
                SkinChoice now = choiceOrNull(player);
                // Only replace what is still the same stored choice (the player may have changed it meanwhile).
                if (now == null || !stored.equals(now)) {
                    return;
                }
                SkinChoice updated = new SkinChoice(fresh.name(), fresh.texture(), System.currentTimeMillis());
                ctx.users().user(player).update(store.component, current -> current.equals(stored) ? updated : current);
                if (!fresh.texture().value().equals(stored.texture().value())) {
                    try {
                        apply(player, fresh.texture());
                    } catch (RuntimeException e) {
                        ctx.logger().error("Applying the refreshed skin of {} failed", player.getName(), e);
                    }
                }
            }, null);
        });
    }

    private void purgeOldCache() {
        int days = config.get().purgeAfterDays;
        if (days <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        store.purge(cutoff).whenComplete((removed, error) -> {
            if (error != null) {
                ctx.logger().warn("Purging the skin cache failed", error);
            } else if (removed != null && removed > 0) {
                ctx.logger().info("Removed {} old skin cache entries", removed);
            }
        });
    }
}
