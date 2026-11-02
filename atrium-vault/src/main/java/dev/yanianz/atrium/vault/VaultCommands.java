package dev.yanianz.atrium.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * {@code /vault-info} and {@code /vault-convert}. Folia-safe: replies go through the sender's entity scheduler (players)
 * or the global region scheduler (console and others); conversions run on Paper's async scheduler.
 */
public final class VaultCommands implements TabExecutor {
    private static final long CONFIRM_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(2);

    private record Pending(String from, String to, long expiresAt) {
    }

    /** A native economy provider, v1 or v2, as named on the command line ({@code Plugin:v1} / {@code Plugin:v2}). */
    private record Endpoint(String label, Object provider, EconomyConverter.Book book) {
    }

    private final Plugin plugin;
    private final VaultInfo.Versions versions;
    private final Supplier<GuardedEconomy> guard;
    private final Supplier<GuardedEconomyV2> guardV2;
    private final Supplier<BridgeManager> bridges;
    private final Supplier<AuditLog> audit;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean converting = new AtomicBoolean();

    public VaultCommands(Plugin plugin, VaultInfo.Versions versions, Supplier<GuardedEconomy> guard,
                         Supplier<GuardedEconomyV2> guardV2, Supplier<BridgeManager> bridges, Supplier<AuditLog> audit) {
        this.plugin = plugin;
        this.versions = versions;
        this.guard = guard;
        this.guardV2 = guardV2;
        this.bridges = bridges;
        this.audit = audit;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "vault-info" -> {
                for (String line : VaultInfo.lines(plugin, versions, guard.get(), guardV2.get(), bridges.get(), audit.get())) {
                    sender.sendMessage(Component.text(line, line.startsWith(" ") ? NamedTextColor.GRAY : NamedTextColor.GOLD));
                }
                return true;
            }
            case "vault-convert" -> {
                convert(sender, args);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void convert(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equalsIgnoreCase("confirm"))) {
            reply(sender, "Usage: /vault-convert <from>[:v1|:v2] <to>[:v1|:v2] [confirm]  (plugin names, see /vault-info)",
                    NamedTextColor.RED);
            return;
        }
        Endpoint from = endpoint(args[0]);
        Endpoint to = endpoint(args[1]);
        if (from == null || to == null) {
            reply(sender, "No native economy registered by '" + (from == null ? args[0] : args[1]) + "'. Available: "
                    + String.join(", ", economyPlugins()), NamedTextColor.RED);
            return;
        }
        if (from.provider() == to.provider()) {
            reply(sender, "Source and target are the same economy.", NamedTextColor.RED);
            return;
        }
        String key = senderKey(sender);
        String fromName = from.label();
        String toName = to.label();
        boolean apply = args.length == 3;
        if (apply) {
            Pending dryRun = pending.get(key);
            if (dryRun == null || dryRun.expiresAt() < System.currentTimeMillis()
                    || !dryRun.from().equals(fromName) || !dryRun.to().equals(toName)) {
                reply(sender, "Run '/vault-convert " + fromName + " " + toName + "' (dry run) first, then confirm within 2 minutes.",
                        NamedTextColor.RED);
                return;
            }
        }
        if (!converting.compareAndSet(false, true)) {
            reply(sender, "A conversion is already running.", NamedTextColor.RED);
            return;
        }
        reply(sender, (apply ? "Converting" : "Dry run: comparing") + " balances " + fromName + " -> " + toName
                + " for all known players (async)...", NamedTextColor.YELLOW);
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
            try {
                List<OfflinePlayer> players = Arrays.asList(plugin.getServer().getOfflinePlayers());
                // v1 -> v1 keeps the original double path; anything involving v2 runs in exact decimal.
                EconomyConverter.Report report = from.provider() instanceof Economy source && to.provider() instanceof Economy target
                        ? EconomyConverter.convert(source, target, players, apply)
                        : EconomyConverter.convert(from.book(), to.book(), players, apply);
                if (apply) pending.remove(key);
                else pending.put(key, new Pending(fromName, toName, System.currentTimeMillis() + CONFIRM_WINDOW_MILLIS));
                String summary = (apply ? "Converted " : "Dry run ") + fromName + " -> " + toName + ": players="
                        + report.players() + ", unchanged=" + report.unchanged() + ", deposits=" + report.deposits()
                        + ", withdrawals=" + report.withdrawals() + ", no-source-account=" + report.skipped()
                        + ", failed=" + report.failed() + ". Source was not modified.";
                plugin.getLogger().info(summary);
                reply(sender, summary, report.failed() == 0 ? NamedTextColor.GREEN : NamedTextColor.YELLOW);
                for (String failure : report.failures()) {
                    plugin.getLogger().warning("vault-convert failure: " + failure);
                    reply(sender, "  failed: " + failure, NamedTextColor.RED);
                }
                if (report.failed() > report.failures().size()) {
                    reply(sender, "  ... and " + (report.failed() - report.failures().size()) + " more", NamedTextColor.RED);
                }
                if (!apply) {
                    reply(sender, "Run '/vault-convert " + fromName + " " + toName + " confirm' within 2 minutes to apply.",
                            NamedTextColor.YELLOW);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "vault-convert " + fromName + " -> " + toName + " failed", e);
                reply(sender, "Conversion aborted: " + e + " (see console)", NamedTextColor.RED);
            } finally {
                converting.set(false);
            }
        });
    }

    /**
     * Resolves {@code Plugin}, {@code Plugin:v1} or {@code Plugin:v2} to a native provider (never a guard or a bridge).
     * Without a suffix the plugin's v1 provider is preferred, then its v2 provider.
     */
    private Endpoint endpoint(String argument) {
        String name = argument;
        String api = null;
        int colon = argument.lastIndexOf(':');
        if (colon > 0) {
            String suffix = argument.substring(colon + 1).toLowerCase(Locale.ROOT);
            if (suffix.equals("v1") || suffix.equals("v2")) {
                api = suffix;
                name = argument.substring(0, colon);
            }
        }
        ServicesManager services = plugin.getServer().getServicesManager();
        if (!"v2".equals(api)) {
            for (RegisteredServiceProvider<Economy> registration : VaultInfo.registrations(services, Economy.class)) {
                if (!BridgeManager.isNative(registration.getProvider())) continue;
                if (!registration.getPlugin().getName().equalsIgnoreCase(name)) continue;
                String label = registration.getPlugin().getName() + ":v1";
                return new Endpoint(label, registration.getProvider(), EconomyConverter.v1(registration.getProvider(), label));
            }
            if ("v1".equals(api)) return null;
        }
        for (RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> registration
                : VaultInfo.registrations(services, net.milkbowl.vault2.economy.Economy.class)) {
            if (!BridgeManager.isNative(registration.getProvider())) continue;
            if (!registration.getPlugin().getName().equalsIgnoreCase(name)) continue;
            String label = registration.getPlugin().getName() + ":v2";
            return new Endpoint(label, registration.getProvider(),
                    EconomyConverter.v2(registration.getProvider(), label, plugin.getName()));
        }
        return null;
    }

    /** Native economies as {@code Plugin:v1} / {@code Plugin:v2}. */
    private List<String> economyPlugins() {
        ServicesManager services = plugin.getServer().getServicesManager();
        List<String> names = new ArrayList<>();
        for (RegisteredServiceProvider<Economy> registration : VaultInfo.registrations(services, Economy.class)) {
            if (BridgeManager.isNative(registration.getProvider())) names.add(registration.getPlugin().getName() + ":v1");
        }
        for (RegisteredServiceProvider<net.milkbowl.vault2.economy.Economy> registration
                : VaultInfo.registrations(services, net.milkbowl.vault2.economy.Economy.class)) {
            if (BridgeManager.isNative(registration.getProvider())) names.add(registration.getPlugin().getName() + ":v2");
        }
        return names;
    }

    private static String senderKey(CommandSender sender) {
        return sender instanceof Entity entity ? entity.getUniqueId().toString() : sender.getName();
    }

    /** Sends a line on a thread that may touch the sender (Folia). */
    private void reply(CommandSender sender, String text, NamedTextColor color) {
        Component message = Component.text(text, color);
        if (sender instanceof Player player) {
            player.getScheduler().run(plugin, task -> player.sendMessage(message), null);
        } else {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> sender.sendMessage(message));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("vault-convert")) return List.of();
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = args.length <= 2 ? economyPlugins() : args.length == 3 ? List.of("confirm") : List.of();
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
