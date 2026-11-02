package dev.yanianz.atrium.vault;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

/**
 * Keeps the v1/v2 economy bridges registered exactly when they are needed:
 *
 * <ul>
 *   <li>native v1 provider and no native v2 provider: {@link V1AsV2Economy} is registered as a v2 provider;</li>
 *   <li>native v2 provider and no native v1 provider: {@link V2AsV1Economy} is registered as a v1 provider;</li>
 *   <li>both native, or neither: no bridge (never a double registration).</li>
 * </ul>
 *
 * "Native" excludes the guards and the bridges themselves, so a bridge can never feed the other bridge. Bridges are
 * registered at {@link ServicePriority#Lowest}: any real provider registered later wins. {@link #reconcile()} runs at
 * enable and on every economy service (un)registration by another plugin.
 */
public final class BridgeManager {
    /** What should be registered for a given set of native providers. */
    public enum Plan {
        NONE, V1_AS_V2, V2_AS_V1;

        public static Plan of(boolean nativeV1, boolean nativeV2) {
            if (nativeV1 && !nativeV2) return V1_AS_V2;
            if (nativeV2 && !nativeV1) return V2_AS_V1;
            return NONE;
        }
    }

    private final Plugin plugin;
    private final ServicesManager services;
    private final Logger logger;
    private final ProviderCache<Economy> nativeV1;
    private final ProviderCache<net.milkbowl.vault2.economy.Economy> nativeV2;
    private final V1AsV2Economy v1AsV2;
    private final V2AsV1Economy v2AsV1;
    private boolean v1AsV2Registered; // guarded by this
    private boolean v2AsV1Registered; // guarded by this
    private volatile Plan active = Plan.NONE;

    public BridgeManager(Plugin plugin, ServicesManager services, Logger logger, RateLimitedLog log,
                         Function<UUID, OfflinePlayer> players, Function<String, UUID> names, CallerResolver callers) {
        this.plugin = plugin;
        this.services = services;
        this.logger = logger;
        this.nativeV1 = new ProviderCache<>(() -> providers(Economy.class), BridgeManager::isNative, log, "v1 economy");
        this.nativeV2 = new ProviderCache<>(() -> providers(net.milkbowl.vault2.economy.Economy.class),
                BridgeManager::isNative, log, "v2 economy");
        this.v1AsV2 = new V1AsV2Economy(nativeV1::get, players);
        this.v2AsV1 = new V2AsV1Economy(nativeV2::get, names, callers);
    }

    /** True for every provider that is not one of Atrium Vault's own guards or bridges. */
    public static boolean isNative(Object provider) {
        return provider != null && !(provider instanceof GuardedEconomy) && !(provider instanceof GuardedEconomyV2)
                && !(provider instanceof BridgedEconomy);
    }

    /** True for Atrium Vault's own economy services (guards and bridges). */
    public static boolean isOwn(Object provider) {
        return provider != null && !isNative(provider);
    }

    private <T> List<T> providers(Class<T> type) {
        return VaultInfo.registrations(services, type).stream().map(RegisteredServiceProvider::getProvider).toList();
    }

    public Economy nativeV1() {
        return nativeV1.get();
    }

    public net.milkbowl.vault2.economy.Economy nativeV2() {
        return nativeV2.get();
    }

    public V1AsV2Economy v1AsV2() {
        return v1AsV2;
    }

    public V2AsV1Economy v2AsV1() {
        return v2AsV1;
    }

    public Plan active() {
        return active;
    }

    public void invalidate() {
        nativeV1.invalidate();
        nativeV2.invalidate();
    }

    /** Registers or unregisters the bridges to match the current native providers. Idempotent. */
    public synchronized void reconcile() {
        invalidate();
        boolean v1 = nativeV1.get() != null;
        boolean v2 = nativeV2.get() != null;
        Plan plan = plugin.isEnabled() ? Plan.of(v1, v2) : Plan.NONE;
        // While the server stops, providers unregister one by one: never add a bridge for that moment.
        if (plan != Plan.NONE && stopping()) plan = Plan.NONE;
        // Unregister first: never two bridges at once, even for a moment.
        if (plan != Plan.V1_AS_V2 && v1AsV2Registered) {
            services.unregister(net.milkbowl.vault2.economy.Economy.class, v1AsV2);
            v1AsV2Registered = false;
            logger.info("Vault v1->v2 economy bridge removed (" + reason(v1, v2) + ")");
        }
        if (plan != Plan.V2_AS_V1 && v2AsV1Registered) {
            services.unregister(Economy.class, v2AsV1);
            v2AsV1Registered = false;
            logger.info("Vault v2->v1 economy bridge removed (" + reason(v1, v2) + ")");
        }
        if (plan == Plan.V1_AS_V2 && !v1AsV2Registered) {
            v1AsV2Registered = register(net.milkbowl.vault2.economy.Economy.class, v1AsV2, "v1->v2", "v2");
        }
        if (plan == Plan.V2_AS_V1 && !v2AsV1Registered) {
            v2AsV1Registered = register(Economy.class, v2AsV1, "v2->v1", "v1");
        }
        active = v1AsV2Registered ? Plan.V1_AS_V2 : v2AsV1Registered ? Plan.V2_AS_V1 : Plan.NONE;
    }

    private <T> boolean register(Class<T> type, T bridge, String direction, String missing) {
        try {
            services.register(type, bridge, plugin, ServicePriority.Lowest);
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Cannot register the Vault " + direction + " economy bridge", e);
            return false;
        }
        logger.info("Vault " + direction + " economy bridge registered: " + ((BridgedEconomy) bridge).bridgeDescription()
                + " (no native Vault " + missing + " economy)");
        return true;
    }

    private String reason(boolean v1, boolean v2) {
        if (!plugin.isEnabled() || stopping()) return "shutting down";
        if (v1 && v2) return "native v1 and v2 economies";
        if (!v1 && !v2) return "no economy registered";
        return "the other direction is needed";
    }

    private boolean stopping() {
        try {
            return plugin.getServer().isStopping();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Unregisters both bridges (plugin disable). */
    public synchronized void close() {
        if (v1AsV2Registered) services.unregister(net.milkbowl.vault2.economy.Economy.class, v1AsV2);
        if (v2AsV1Registered) services.unregister(Economy.class, v2AsV1);
        v1AsV2Registered = false;
        v2AsV1Registered = false;
        active = Plan.NONE;
    }

    /** One line for {@code /vault-info}. */
    public String describe() {
        return switch (active) {
            case NONE -> "Bridge: none (" + (nativeV1() != null && nativeV2() != null
                    ? "native v1 and v2 economies" : "no economy to bridge") + ")";
            case V1_AS_V2 -> "Bridge: v1->v2 active, " + v1AsV2.bridgeDescription() + " (no native v2 economy)";
            case V2_AS_V1 -> "Bridge: v2->v1 active, " + v2AsV1.bridgeDescription() + " (no native v1 economy)";
        };
    }
}
