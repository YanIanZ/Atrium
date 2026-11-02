package dev.yanianz.atrium.vault;

import java.util.function.Supplier;
import java.util.logging.Logger;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import net.milkbowl.vault2.chat.ChatUnlocked;
import net.milkbowl.vault2.permission.PermissionUnlocked;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Logs Vault v1/v2 provider (un)registrations, drops the guards' cached delegates when an economy changes and lets
 * the {@link BridgeManager} re-check which bridge is needed. Events about Atrium Vault's own guards and bridges only
 * invalidate caches: they never trigger a reconcile, so registering a bridge cannot recurse.
 */
public final class ServiceListener implements Listener {
    private final Supplier<GuardedEconomy> guard;
    private final Supplier<GuardedEconomyV2> guardV2;
    private final Supplier<BridgeManager> bridges;
    private final Logger logger;

    public ServiceListener(Supplier<GuardedEconomy> guard, Supplier<GuardedEconomyV2> guardV2,
                           Supplier<BridgeManager> bridges, Logger logger) {
        this.guard = guard;
        this.guardV2 = guardV2;
        this.bridges = bridges;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegister(ServiceRegisterEvent event) {
        handle(event, "registered");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregister(ServiceUnregisterEvent event) {
        handle(event, "unregistered");
    }

    private void handle(ServiceEvent event, String what) {
        RegisteredServiceProvider<?> provider = event.getProvider();
        Class<?> service = provider.getService();
        boolean economy = service == Economy.class || service == net.milkbowl.vault2.economy.Economy.class;
        Object instance = provider.getProvider();
        if ((economy || service == Permission.class || service == Chat.class || service == PermissionUnlocked.class
                || service == ChatUnlocked.class) && !(instance instanceof GuardedEconomy)
                && !(instance instanceof GuardedEconomyV2)) {
            String api = service.getPackageName().startsWith("net.milkbowl.vault2") ? " (v2)" : "";
            logger.info(() -> service.getSimpleName() + api + " provider " + what + ": " + VaultInfo.describe(provider));
        }
        if (!economy) return;
        GuardedEconomy v1 = guard.get();
        if (v1 != null) v1.invalidate();
        GuardedEconomyV2 v2 = guardV2.get();
        if (v2 != null) v2.invalidate();
        BridgeManager manager = bridges.get();
        if (manager == null) return;
        if (BridgeManager.isOwn(instance)) manager.invalidate();
        else manager.reconcile();
    }
}
