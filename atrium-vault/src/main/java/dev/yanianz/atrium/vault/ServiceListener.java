package dev.yanianz.atrium.vault;

import java.util.logging.Logger;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Logs Vault provider (un)registrations and drops the guard's cached delegate when an economy changes. */
public final class ServiceListener implements Listener {
    private final GuardedEconomy guard;
    private final Logger logger;

    public ServiceListener(GuardedEconomy guard, Logger logger) {
        this.guard = guard;
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
        if (service == Economy.class && guard != null) guard.invalidate();
        if (service != Economy.class && service != Permission.class && service != Chat.class) return;
        if (provider.getProvider() instanceof GuardedEconomy) return;
        logger.info(() -> service.getSimpleName() + " provider " + what + ": " + VaultInfo.describe(provider));
    }
}
