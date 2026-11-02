package dev.yanianz.atrium.vault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/** Builds the plain-text report shown by {@code /vault-info}. */
public final class VaultInfo {
    private VaultInfo() {
    }

    /** Registrations of {@code service}, highest priority first (the order Bukkit picks the active one). */
    public static <T> List<RegisteredServiceProvider<T>> registrations(ServicesManager services, Class<T> service) {
        List<RegisteredServiceProvider<T>> list = new ArrayList<>(services.getRegistrations(service));
        list.sort(Comparator.comparing(RegisteredServiceProvider<T>::getPriority).reversed());
        return list;
    }

    static String describe(RegisteredServiceProvider<?> registration) {
        Plugin owner = registration.getPlugin();
        return name(registration.getProvider()) + " [plugin " + (owner == null ? "?" : owner.getName())
                + ", priority " + registration.getPriority() + "]";
    }

    private static String name(Object provider) {
        try {
            return switch (provider) {
                case GuardedEconomy ignored -> "GuardedEconomy";
                case Economy economy -> economy.getName();
                case Permission permission -> permission.getName();
                case Chat chat -> chat.getName();
                default -> provider.getClass().getName();
            };
        } catch (RuntimeException e) {
            return provider.getClass().getName() + " (getName failed: " + e.getClass().getSimpleName() + ")";
        }
    }

    public static List<String> lines(Plugin vault, String apiVersion, GuardedEconomy guard, AuditLog audit) {
        Server server = vault.getServer();
        ServicesManager services = server.getServicesManager();
        List<String> out = new ArrayList<>();
        out.add("Vault " + vault.getPluginMeta().getVersion() + " (Atrium Vault, VaultAPI " + apiVersion + ") on "
                + server.getName() + " " + server.getVersion());
        section(out, services, "Economy", Economy.class);
        section(out, services, "Permission", Permission.class);
        section(out, services, "Chat", Chat.class);
        if (guard == null) {
            out.add("Guard: disabled (guard.enabled=false); plugins talk to the provider directly");
        } else {
            Economy delegate = guard.delegate();
            out.add("Guard: enabled, delegate=" + (delegate == null ? "none (economy reported as disabled)"
                    : name(delegate) + " (" + delegate.getClass().getName() + ")")
                    + ", max-amount=" + guard.settings().maxAmount()
                    + ", rejected=" + guard.rejectedCount() + ", provider-errors=" + guard.failureCount()
                    + ", slow-tick-calls=" + guard.slowTickCallCount());
            out.add("Audit: " + (audit == null ? "off" : "on, dir=" + audit.directory() + ", written=" + audit.written()
                    + ", queued=" + audit.queued() + ", dropped=" + audit.dropped()));
        }
        return out;
    }

    private static <T> void section(List<String> out, ServicesManager services, String label, Class<T> type) {
        List<RegisteredServiceProvider<T>> list = registrations(services, type);
        RegisteredServiceProvider<T> active = services.getRegistration(type);
        out.add(label + ": " + (list.isEmpty() ? "no provider registered" : list.size() + " provider(s)"));
        for (RegisteredServiceProvider<T> registration : list) {
            boolean isActive = active != null && active.getProvider() == registration.getProvider();
            out.add((isActive ? "  * " : "  - ") + describe(registration) + (isActive ? " <- active" : ""));
        }
    }
}
