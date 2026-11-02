package dev.yanianz.atrium.module.discord;

import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentEvent;
import dev.yanianz.atrium.api.moderation.PunishmentRequest;
import dev.yanianz.atrium.api.moderation.PunishmentRevokeEvent;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.chat.StaffChatEvent;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Translates game events into relay messages. Vanished players and shadow-muted chat never leave the server:
 * a leaked vanish state or a shadow-muted line in a public Discord channel is exactly the kind of bug the
 * old DiscordSRV integration had.
 */
final class DiscordListener implements Listener {
    private final ModuleContext ctx;
    private final DiscordRelay relay;
    private final ConfigHandle<DiscordConfig> config;

    DiscordListener(ModuleContext ctx, DiscordRelay relay, ConfigHandle<DiscordConfig> config) {
        this.ctx = ctx;
        this.relay = relay;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        // A shadow-muted message looks normal to the sender but must reach nobody else, including Discord.
        if (ctx.services().get(MuteService.class).map(mutes -> mutes.isShadowMuted(id)).orElse(false)) {
            return;
        }
        if (isVanished(id)) {
            return;
        }
        // The chat module publishes the filtered text through event.message() at NORMAL, so the webhook
        // never receives a word the in-game filter replaced. Read it at MONITOR, after every filter ran.
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        relay.publish(DiscordChannel.CHAT, config.get().chatFormat,
                Map.of("message", text, "player", player.getName()), player.getName(), id);
    }

    /**
     * Staff chat reaches Discord only through {@link StaffChatEvent}: the chat module has already applied
     * mute, shadow-mute, vanish-independent staff permissions and the filter, and the event is async so the
     * non-blocking relay queue is safe here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onStaffChat(StaffChatEvent event) {
        if (event.isCancelled()) {
            return;
        }
        DiscordConfig cfg = config.get();
        if (event.remote() && !cfg.relayRemoteStaff) {
            return;
        }
        Map<String, String> fields = new HashMap<>();
        fields.put("player", event.senderName());
        fields.put("message", event.message());
        fields.put("server", event.originServer());
        relay.publish(DiscordChannel.STAFF, cfg.staffFormat, fields, event.senderName(), event.senderId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {        Player player = event.getPlayer();
        // The vanish module clears joinMessage at HIGHEST for a silent join; do not undo that.
        if (event.joinMessage() == null || isVanished(player.getUniqueId())) {
            return;
        }
        relay.publish(DiscordChannel.JOINS, config.get().joinFormat,
                Map.of("player", player.getName()), player.getName(), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        DiscordConfig cfg = config.get();
        if (!cfg.relayQuits) {
            return;
        }
        Player player = event.getPlayer();
        // quitMessage is the signal the vanish module uses for a silent quit, so trust it even if the vanish
        // state was already cleared by a MONITOR listener that ran before this one.
        if (event.quitMessage() == null || isVanished(player.getUniqueId())) {
            return;
        }
        relay.publish(DiscordChannel.JOINS, cfg.quitFormat,
                Map.of("player", player.getName()), player.getName(), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (isVanished(player.getUniqueId())) {
            return;
        }
        Component message = event.deathMessage();
        String text = message == null
                ? player.getName() + " died"
                : PlainTextComponentSerializer.plainText().serialize(message);
        relay.publish(DiscordChannel.DEATHS, config.get().deathFormat,
                Map.of("message", text, "player", player.getName()), player.getName(), player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPunishment(PunishmentEvent event) {
        PunishmentRequest request = event.request();
        if (request.silent() && !config.get().relaySilentPunishments) {
            return;
        }
        Map<String, String> fields = new HashMap<>();
        fields.put("type", request.type().name());
        fields.put("target", targetName(request));
        fields.put("actor", request.actorName());
        fields.put("reason", request.reason().isBlank() ? "no reason" : request.reason());
        fields.put("duration", duration(request));
        relay.publish(DiscordChannel.PUNISHMENTS, config.get().punishmentFormat, fields,
                request.targetName(), request.target());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRevoke(PunishmentRevokeEvent event) {
        List<Punishment> punishments = event.punishments();
        if (punishments.isEmpty()) {
            return;
        }
        Punishment first = punishments.getFirst();
        Map<String, String> fields = new HashMap<>();
        fields.put("type", punishments.stream().map(p -> p.type().name()).distinct().reduce((a, b) -> a + ", " + b)
                .orElse("?"));
        fields.put("target", first.targetName() == null ? "unknown" : first.targetName());
        fields.put("actor", event.actorName());
        fields.put("id", Long.toString(first.id()));
        fields.put("count", Integer.toString(punishments.size()));
        relay.publish(DiscordChannel.PUNISHMENTS, config.get().revokeFormat, fields,
                first.targetName(), first.target());
    }

    private boolean isVanished(UUID player) {
        return ctx.services().get(VanishService.class).map(vanish -> vanish.isVanished(player)).orElse(false);
    }

    private static String targetName(PunishmentRequest request) {
        if (request.targetName() != null && !request.targetName().isBlank()) {
            return request.targetName();
        }
        // Never put the address of an IP ban in a Discord channel.
        return request.type() == PunishmentType.IPBAN ? "IP ban" : "unknown";
    }

    private static String duration(PunishmentRequest request) {
        if (request.type().isInstant()) {
            return "instant";
        }
        if (request.duration() == null) {
            return "permanent";
        }
        return Durations.format(request.duration(), 2);
    }
}
