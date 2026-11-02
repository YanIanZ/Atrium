package dev.yanianz.atrium.module.performance;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import dev.yanianz.atrium.api.serverlist.ServerListService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Builds the server list: the MOTD override, the online/max numbers and the player sample.
 *
 * <p>The event is fired on a network thread, so nothing here may touch an entity or the world. Vanish state is
 * read from the published service, which answers online players from memory.
 */
final class ServerListListener implements Listener {
    private final ModuleContext ctx;
    private final Supplier<PerformanceConfig> config;
    private final ServerMOTD motd;
    private final VanishService vanish;
    /** Session cap set with /maxplayers; -1 = follow the server. */
    private volatile int runtimeMax = -1;
    private volatile int lastOnline;
    private volatile int lastMax;

    ServerListListener(ModuleContext ctx, Supplier<PerformanceConfig> config, ServerMOTD motd, VanishService vanish) {
        this.ctx = ctx;
        this.config = config;
        this.motd = motd;
        this.vanish = vanish;
    }

    int runtimeMax() {
        return runtimeMax;
    }

    void runtimeMax(int value) {
        this.runtimeMax = value;
    }

    int lastOnline() {
        return lastOnline;
    }

    int lastMax() {
        return lastMax;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPing(PaperServerListPingEvent event) {
        PerformanceConfig.ServerList cfg = config.get().serverList;
        if (!cfg.enabled) {
            return;
        }
        int hidden = 0;
        if (cfg.hideVanished && vanish != null) {
            Set<UUID> vanished = vanishedIds();
            if (!vanished.isEmpty()) {
                event.getListedPlayers().removeIf(info -> vanished.contains(info.id()));
                hidden = vanished.size();
            }
        }
        int max = resolvedMax(event.getMaxPlayers(), cfg.counts);
        int online = resolvedOnline(event.getNumPlayers(), hidden, cfg.counts);
        event.setMaxPlayers(max);
        event.setNumPlayers(online);
        lastOnline = online;
        lastMax = max;

        ServerMOTD.State state = motd.state();
        if (state.active()) {
            Component lines = ctx.messages().parseTrusted(
                    String.join("\n", state.lines().stream()
                            .map(line -> MotdText.withNumbers(line, online, max))
                            .toList()),
                    Msg.number("online", online), Msg.number("max", max));
            event.motd(lines);
        }
    }

    /** Online players whose vanish state hides them from the list. Empty when the service is not published. */
    private Set<UUID> vanishedIds() {
        Set<UUID> ids = new java.util.HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (vanish.isVanished(player.getUniqueId())) {
                ids.add(player.getUniqueId());
            }
        }
        return ids;
    }

    static int resolvedOnline(int reported, int hidden, PerformanceConfig.ServerList.Counts counts) {
        int override = counts.onlineOverride();
        int value = override >= 0 ? override : reported - Math.max(0, hidden) + counts.onlineOffset();
        return Math.max(0, Math.min(10_000, value));
    }

    int resolvedMax(int reported, PerformanceConfig.ServerList.Counts counts) {
        return resolveMax(reported, runtimeMax, counts);
    }

    static int resolveMax(int reported, int runtimeMax, PerformanceConfig.ServerList.Counts counts) {
        int override = counts.maxOverride();
        int base = runtimeMax > 0 ? runtimeMax : reported;
        int value = override >= 0 ? override : base + counts.maxOffset();
        return Math.max(1, Math.min(10_000, value));
    }

    List<String> motdLines() {
        return motd.state().lines();
    }

    /** Adapter published as {@link ServerListService}; other modules change the MOTD through it. */
    static final class Service implements ServerListService {
        private final ServerMOTD motd;
        private final ServerListListener listener;

        Service(ServerMOTD motd, ServerListListener listener) {
            this.motd = motd;
            this.listener = listener;
        }

        @Override
        public boolean motdEnabled() {
            return motd.state().enabled();
        }

        @Override
        public List<String> motdLines() {
            return motd.state().lines();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> setMotd(List<String> lines) {
            return motd.setLines(lines);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> setMotdEnabled(boolean enabled) {
            return motd.setEnabled(enabled);
        }

        @Override
        public int reportedOnline() {
            return listener.lastOnline();
        }

        @Override
        public int reportedMax() {
            return listener.lastMax();
        }
    }
}
