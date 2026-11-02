package dev.yanianz.atrium.vault;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.milkbowl.vault.chat.Chat;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.permission.Permission;
import net.milkbowl.vault2.chat.ChatUnlocked;
import net.milkbowl.vault2.permission.PermissionUnlocked;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/** Builds the plain-text report shown by {@code /vault-info}. */
public final class VaultInfo {
    private VaultInfo() {
    }

    /** API versions shaded into the jar. */
    public record Versions(String vaultApi, String vaultUnlockedApi) {
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

    static String name(Object provider) {
        try {
            return switch (provider) {
                case GuardedEconomy ignored -> "GuardedEconomy (v1 guard)";
                case GuardedEconomyV2 ignored -> "GuardedEconomyV2 (v2 guard)";
                case BridgedEconomy bridge -> "BRIDGED: " + bridge.bridgeDescription();
                case Economy economy -> economy.getName();
                case net.milkbowl.vault2.economy.Economy economy -> economy.getName();
                case Permission permission -> permission.getName();
                case PermissionUnlocked permission -> permission.getName();
                case Chat chat -> chat.getName();
                case ChatUnlocked chat -> chat.getName();
                default -> provider.getClass().getName();
            };
        } catch (RuntimeException | AbstractMethodError e) {
            return provider.getClass().getName() + " (getName failed: " + e.getClass().getSimpleName() + ")";
        }
    }

    public static List<String> lines(Plugin vault, Versions versions, GuardedEconomy guard, GuardedEconomyV2 guardV2,
                                     BridgeManager bridges, AuditLog audit) {
        Server server = vault.getServer();
        ServicesManager services = server.getServicesManager();
        List<String> out = new ArrayList<>();
        out.add("Vault " + vault.getPluginMeta().getVersion() + " (Atrium Vault, VaultAPI " + versions.vaultApi()
                + " + VaultUnlockedAPI " + versions.vaultUnlockedApi() + ") on " + server.getName() + " " + server.getVersion());
        section(out, services, "Economy v1", Economy.class);
        section(out, services, "Economy v2", net.milkbowl.vault2.economy.Economy.class);
        section(out, services, "Permission v1", Permission.class);
        section(out, services, "Permission v2", PermissionUnlocked.class);
        section(out, services, "Chat v1", Chat.class);
        section(out, services, "Chat v2", ChatUnlocked.class);
        out.add(bridges == null ? "Bridge: disabled (bridge.enabled=false)" : bridges.describe());
        if (guard == null) {
            out.add("Guard: disabled (guard.enabled=false); plugins talk to the provider directly");
        } else {
            Economy delegate = guard.delegate();
            out.add("Guard v1: enabled, delegate=" + (delegate == null ? "none (economy reported as disabled)"
                    : name(delegate) + " (" + delegate.getClass().getName() + ")")
                    + ", max-amount=" + guard.settings().maxAmount()
                    + ", rejected=" + guard.rejectedCount() + ", provider-errors=" + guard.failureCount()
                    + ", slow-tick-calls=" + guard.slowTickCallCount());
        }
        if (guardV2 != null) {
            net.milkbowl.vault2.economy.Economy delegate = guardV2.delegate();
            DecimalLimits limits = guardV2.limits();
            out.add("Guard v2: enabled, delegate=" + (delegate == null ? "none (economy reported as disabled)"
                    : name(delegate) + " (" + delegate.getClass().getName() + ")")
                    + ", max-amount=" + limits.maxAmount().toPlainString() + ", max-scale=" + limits.maxScale()
                    + ", max-precision=" + limits.maxPrecision()
                    + ", rejected=" + guardV2.rejectedCount() + ", provider-errors=" + guardV2.failureCount()
                    + ", slow-tick-calls=" + guardV2.slowTickCallCount());
        }
        if (guard != null || guardV2 != null) {
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
