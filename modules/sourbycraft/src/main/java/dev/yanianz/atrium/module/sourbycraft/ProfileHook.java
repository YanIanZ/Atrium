package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import dev.yanianz.atrium.api.profile.ActiveProfileService;
import dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * AWF MultiProfile integration: registers {@link AtriumProfileAdapter}, publishes {@link ActiveProfileService},
 * keeps {@link ProfileOwnership} reconciled with the active profile and checks the economy mode. Hook class:
 * loaded only when the profile API classes exist. Uses only the official 26.2 API; extras of newer local API
 * builds go through {@link ExtendedApi}.
 *
 * <p>The official API has no profile-switch event and calls the adapter only for profiles that hold Atrium data,
 * so the active profile is looked up (never joined, callbacks only) at these points: on join after the profile is
 * ready, after every capture and restore (a switch may be under way), on world change, after SourbyCraft's async
 * teleports, periodically, and lazily before every player command (a synchronous reconciliation with the latest
 * answer, so /home, /sethome, /back and kit claims never act on another profile's data). Lookups go through
 * SourbyCraft's profile I/O lane, so at most one per player runs at a time; requests during a lookup coalesce.
 */
final class ProfileHook implements AutoCloseable, Listener {
    private static final String VAULT_ECONOMY = "net.milkbowl.vault.economy.Economy";
    private static final String TELEPORT_EVENT_API = "dev.iyanz.sourbycraft.api.event.EntityPostTeleportAsyncEvent";
    /** How long after a restore an observation of another profile is not trusted (the switch commits later). */
    static final Duration SETTLE = Duration.ofSeconds(15);
    private static final Duration FORGET_AFTER_QUIT = Duration.ofMinutes(1);

    private final SourbyCraftModule module;
    private final ModuleContext ctx;
    private final AuroraProfiles profiles;
    private final ExtendedApi extended;
    private final ProfileOwnership ownership;
    private final AtriumProfileAdapter adapter;
    private final ActiveProfiles active;
    private final ConcurrentHashMap<UUID, Gate> gates = new ConcurrentHashMap<>();

    private ProfileHook(SourbyCraftModule module, AuroraProfiles profiles, ProfileScopedDataRegistry registry,
            ProfileOwnerStore store, ExtendedApi extended) {
        this.module = module;
        this.ctx = module.context();
        this.profiles = profiles;
        this.extended = extended;
        SourbyCraftConfig cfg = module.config();
        ScopedStateBridge bridge = new ScopedStateBridge(registry, module::scopes, module::missingState, ctx.logger());
        this.ownership = new ProfileOwnership(bridge, store, module::missingState, System::currentTimeMillis,
                SETTLE.toMillis(), ctx.logger());
        this.adapter = new AtriumProfileAdapter(cfg.profileAdapterId, cfg.profileAdapterRequired, bridge, ownership,
                ctx.plugin(), new AtriumProfileAdapter.Listener() {
                    @Override
                    public void captured(Player player) {
                        // A capture starts every switch: look again once it may have committed.
                        observeLater(player.getUniqueId(), Duration.ofSeconds(1), Duration.ofSeconds(3),
                                Duration.ofSeconds(10));
                    }

                    @Override
                    public void restored(Player player) {
                        observeLater(player.getUniqueId(), Duration.ofSeconds(2), Duration.ofSeconds(10),
                                SETTLE.plusSeconds(5));
                    }
                });
        this.active = new ActiveProfiles(profiles::isEnabled, this::fetch, module::scopes, module::economyMode);
    }

    /** Starts the integration, or returns null (with a log line) when MultiProfile is not running. */
    static @Nullable ProfileHook start(SourbyCraftModule module) throws Exception {
        ModuleContext ctx = module.context();
        AuroraProfiles profiles = Bukkit.getServicesManager().load(AuroraProfiles.class);
        if (profiles == null || !profiles.isEnabled()) {
            ctx.logger().info("SourbyCraft AWF MultiProfile is not enabled on this server; profile integration idle.");
            return null;
        }
        if (!module.config().multiprofile) {
            ctx.logger().info("SourbyCraft AWF MultiProfile is enabled but 'multiprofile: false' in sourbycraft.yml; "
                    + "Atrium data stays account-wide.");
            return null;
        }
        ProfileScopedDataRegistry registry = ctx.services().get(ProfileScopedDataRegistry.class).orElse(null);
        if (registry == null) {
            ctx.logger().warn("Atrium core has no profile-scoped data registry; profile integration idle.");
            return null;
        }
        ExtendedApi extended = ExtendedApi.detect(ProfileInfo.class, AuroraProfiles.class, ProfileStateAdapter.class,
                ExtendedApi.classLoader(ProfileHook.class.getClassLoader()));
        ctx.migrate(ProfileOwnerStore.MIGRATIONS);
        ProfileOwnerStore store = new ProfileOwnerStore(ctx.database(), ctx.users(), ctx.logger());
        ctx.component(store.component);

        ProfileHook hook = new ProfileHook(module, profiles, registry, store, extended);
        profiles.registerProfileStateAdapter(ctx.plugin(), hook.adapter);
        ctx.service(ActiveProfileService.class, hook.active);
        ctx.listen(hook);
        if (SourbyCraftModule.present(TELEPORT_EVENT_API)) {
            ctx.listen(new ProfileTeleportListener(hook::observeAfterTeleport));
        }
        for (Player online : List.copyOf(Bukkit.getOnlinePlayers())) {
            hook.active.track(online.getUniqueId());
            hook.observe(online.getUniqueId());
        }
        Duration period = Duration.ofSeconds(Math.max(5, Math.min(600, module.config().profileInfoRefreshSeconds)));
        ctx.track(ctx.scheduler().asyncTimer(hook::refreshAll, period, period));
        if (module.config().economyStartupCheck) {
            // The purse backend resolves lazily after plugins enabled: check a few seconds after startup.
            ctx.track(ctx.scheduler().globalLater(hook::checkEconomy, 100L));
        }
        List<String> features = extended.features();
        ctx.logger().info("SourbyCraft AWF MultiProfile detected ({} API{}): adapter '{}' stores {} with each profile; "
                        + "a profile without Atrium data starts {}.",
                features.isEmpty() ? "official 26.2" : "extended", features.isEmpty() ? "" : " " + features,
                hook.adapter.id(), module.scopes().isEmpty() ? "nothing" : String.join(", ", module.scopes()),
                module.missingState());
        if (!extended.adapterHooks() && module.config().profileAdapterRequired) {
            ctx.logger().info("profile-adapter-required has no effect on this SourbyCraft API: a failing adapter is "
                    + "logged and skipped by the server; Atrium reconciles its data with the active profile instead.");
        }
        return hook;
    }

    private CompletableFuture<Optional<ActiveProfileService.ActiveProfile>> fetch(UUID player) {
        return profiles.getActiveProfile(player)
                .thenApply(info -> info.map(p -> ProfileMapping.toActive(p, player, extended)));
    }

    // ---- observations ----

    /** At most one lookup per player runs; a request during one makes it run once more afterwards. */
    private static final class Gate {
        private final AtomicInteger state = new AtomicInteger(); // 0 idle, 1 running, 2 running + again

        boolean tryStart() {
            while (true) {
                int current = state.get();
                if (current == 0 && state.compareAndSet(0, 1)) {
                    return true;
                }
                if (current == 2 || (current == 1 && state.compareAndSet(1, 2))) {
                    return false;
                }
            }
        }

        /** True when another lookup was requested meanwhile. */
        boolean finish() {
            return state.getAndSet(0) == 2;
        }
    }

    /** Looks up the active profile, updates the cache and reconciles the player's data on their own thread. */
    void observe(UUID uuid) {
        Gate gate = gates.computeIfAbsent(uuid, id -> new Gate());
        if (!gate.tryStart()) {
            return;
        }
        long started = ownership.now();
        CompletableFuture<Optional<ActiveProfileService.ActiveProfile>> lookup;
        try {
            lookup = active.refresh(uuid);
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        lookup.whenComplete((profile, error) -> {
            if (error != null) {
                ctx.logger().debug("Looking up the active profile of {} failed", uuid, error);
            } else {
                deliver(uuid, profile.map(ActiveProfileService.ActiveProfile::id).orElse(null), started);
            }
            if (gate.finish()) {
                observe(uuid);
            }
        });
    }

    private void deliver(UUID uuid, @Nullable UUID activeProfile, long started) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            return;
        }
        ctx.scheduler().entity(player, () -> {
            try {
                ownership.observe(uuid, activeProfile, started);
            } catch (RuntimeException e) {
                ctx.logger().error("Reconciling the profile-scoped data of {} failed", player.getName(), e);
            }
        }, null);
    }

    private void observeLater(UUID uuid, Duration... delays) {
        if (Bukkit.getPlayer(uuid) == null) {
            return; // capture on quit
        }
        for (Duration delay : delays) {
            ctx.track(ctx.scheduler().asyncLater(() -> observe(uuid), delay));
        }
    }

    private void observeAfterTeleport(Player player) {
        UUID uuid = player.getUniqueId();
        observe(uuid);
        // A switch commits after its teleport.
        observeLater(uuid, Duration.ofSeconds(2));
    }

    private void refreshAll() {
        for (UUID player : active.trackedPlayers()) {
            observe(player);
        }
    }

    // ---- events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        active.track(uuid);
        CompletableFuture<?> ready = extended.awaitReady(profiles, uuid);
        if (ready == null) {
            // Official API: no readiness signal. The lookup is queued behind the join's own profile work on
            // SourbyCraft's I/O lane; the later looks cover a recovery that restores data after it.
            ready = CompletableFuture.completedFuture(null);
        }
        ready.whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().debug("Profile restore of {} did not complete", uuid, error);
            }
            observe(uuid);
        });
        observeLater(uuid, Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        active.untrack(uuid);
        gates.remove(uuid);
        // Kept a little: SourbyCraft captures on quit too, and stores complete later.
        ctx.track(ctx.scheduler().asyncLater(() -> {
            if (Bukkit.getPlayer(uuid) == null) {
                ownership.forget(uuid);
            }
        }, FORGET_AFTER_QUIT));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        observeAfterTeleport(event.getPlayer());
    }

    /**
     * Last handler before the command runs (on the player's thread): reconcile with the latest answer, so a
     * scoped command never reads or writes another profile's data.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        try {
            ownership.reconcile(uuid);
        } catch (RuntimeException e) {
            ctx.logger().error("Reconciling the profile-scoped data of {} failed", event.getPlayer().getName(), e);
        }
        long last = ownership.latestObservation(uuid);
        long stale = Math.max(5, module.config().profileInfoRefreshSeconds) * 1000L;
        if (last < 0 || ownership.now() - last > stale) {
            observe(uuid);
        }
    }

    /** Runs on the global region thread a few seconds after startup. */
    private void checkEconomy() {
        String purseBackend = null;
        try {
            purseBackend = extended.purseBackend(type -> Bukkit.getServicesManager().load(type));
        } catch (RuntimeException | LinkageError e) {
            ctx.logger().warn("Reading the SourbyCraft purse backend failed", e);
        }
        EconomyMode.Result mode = EconomyMode.describe(profiles.isEnabled(), extended.purseApi(), purseBackend,
                vaultProvider());
        module.economyMode(mode.text());
        ctx.logger().info("Economy with profiles: {}", mode.text());
        if (mode.atriumBehindPurse()) {
            ctx.logger().warn("The purse writes balances through Vault on the player's tick thread during every profile "
                    + "switch; Atrium's Vault bridge waits for that database write (economy.vault-timeout-millis). Keep "
                    + "the database local/fast, or set the purse to economy.backend: native if switches stall.");
        }
    }

    /** Name of the plugin providing the Vault economy, found without loading Vault classes. */
    private static @Nullable String vaultProvider() {
        for (Class<?> service : Bukkit.getServicesManager().getKnownServices()) {
            if (service.getName().equals(VAULT_ECONOMY)) {
                RegisteredServiceProvider<?> registration = Bukkit.getServicesManager().getRegistration(service);
                return registration == null ? null : registration.getPlugin().getName();
            }
        }
        return null;
    }

    @Override
    public void close() {
        try {
            profiles.unregisterProfileStateAdapter(ctx.plugin(), adapter.id());
        } catch (RuntimeException e) {
            ctx.logger().warn("Unregistering the SourbyCraft profile adapter failed", e);
        }
        active.clear();
        gates.clear();
        ownership.clear();
    }
}
