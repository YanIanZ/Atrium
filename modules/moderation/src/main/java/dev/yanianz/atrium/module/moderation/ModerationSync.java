package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.storage.Dialect;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.bukkit.event.player.PlayerKickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Cross-server moderation, sent over the authenticated {@code moderation} topic of the {@code proxy} module.
 *
 * <p>Changes are applied to the receiver's own memory: bans reload from the shared database when the server
 * uses MySQL, while SQLite installations can only share kicks and mutes live (the ban row stays on the server
 * that issued it). Received payloads are validated and messages from our own server are ignored.
 */
final class ModerationSync {
    static final String TOPIC = "moderation";

    private final ModuleContext ctx;
    private final ConfigHandle<ModerationConfig> config;
    private final PunishmentManager manager;
    private final boolean sharedDatabase;
    private volatile @Nullable ProxyService proxy;
    private volatile ProxyService.Subscription subscription;

    ModerationSync(ModuleContext ctx, ConfigHandle<ModerationConfig> config, PunishmentManager manager) {
        this.ctx = ctx;
        this.config = config;
        this.manager = manager;
        this.sharedDatabase = ctx.database().dialect() == Dialect.MYSQL;
        this.proxy = ctx.services().get(ProxyService.class).orElse(null);
    }

    void enable() {
        ProxyService service = proxy;
        if (service == null) {
            return;
        }
        subscription = service.subscribe(TOPIC, this::receive);
    }

    private final java.util.concurrent.atomic.AtomicBoolean sqliteWarned = new java.util.concurrent.atomic.AtomicBoolean();

    /** Warns once, and only when a real network is in use (more than one server known to the proxy). */
    private void warnIfSqliteNetwork(ProxyService service) {
        if (!sharedDatabase && service.servers().size() > 1 && sqliteWarned.compareAndSet(false, true)) {
            ctx.logger().warn("Cross-server moderation uses SQLite here: bans will kick the player on every "
                    + "server but are only stored on the server that issued them. Use MySQL to share bans.");
        }
    }

    void disable() {
        ProxyService.Subscription current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
    }

    boolean active() {
        return proxy != null && subscription != null && config.get().sync.enabled;
    }

    /** Bans are shared through storage only when every server uses the same MySQL database. */
    boolean sharedBanStorage() {
        return sharedDatabase;
    }

    // ---- publishing ----

    void publishApplied(Punishment punishment) {
        ModerationSyncPayload.Action action = switch (punishment.type()) {
            case BAN, TEMPBAN -> ModerationSyncPayload.Action.BAN;
            case IPBAN -> ModerationSyncPayload.Action.IPBAN;
            case MUTE, TEMPMUTE, SHADOWMUTE -> ModerationSyncPayload.Action.MUTE;
            case KICK -> ModerationSyncPayload.Action.KICK;
            default -> null;
        };
        if (action != null) {
            publish(punishment, action);
        }
    }

    void publishRevoked(List<Punishment> revoked) {
        for (Punishment punishment : revoked) {
            switch (punishment.type()) {
                case BAN, TEMPBAN -> publish(punishment, ModerationSyncPayload.Action.UNBAN);
                case MUTE, TEMPMUTE, SHADOWMUTE -> publish(punishment, ModerationSyncPayload.Action.UNMUTE);
                case IPBAN -> {
                    if (punishment.ip() != null) {
                        publish(punishment, ModerationSyncPayload.Action.UNIPBAN);
                    }
                }
                default -> {
                    // Warnings, jails and freezes are server-local, they have no proxy action.
                }
            }
        }
    }

    private void publish(Punishment punishment, ModerationSyncPayload.Action action) {
        ProxyService network = proxy;
        if (network != null) {
            warnIfSqliteNetwork(network);
        }
        ProxyService service = proxy;
        if (service == null || !config.get().sync.enabled) {
            return;
        }
        String name = punishment.targetName() == null
                ? punishment.ip() == null ? "?" : punishment.ip()
                : punishment.targetName();
        ModerationSyncPayload.Payload payload = new ModerationSyncPayload.Payload(
                action,
                punishment.target(),
                clean(name, 64),
                clean(punishment.reason(), 512),
                clean(punishment.actorName() == null ? "" : punishment.actorName(), 64),
                punishment.created().toEpochMilli(),
                punishment.expires() == null ? 0 : punishment.expires().toEpochMilli(),
                punishment.ip());
        try {
            service.publish(TOPIC, ModerationSyncPayload.encode(payload));
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not publish {} of {} to the proxy", action, name, e);
        }
    }

    // ---- receiving ----

    private void receive(ProxyMessage message) {
        ProxyService network = proxy;
        if (network != null) {
            warnIfSqliteNetwork(network);
        }
        if (!active()) {
            return;
        }
        ProxyService service = proxy;
        String own = service == null ? "unknown" : service.serverName();
        if (!"unknown".equals(own) && own.equalsIgnoreCase(message.server())) {
            return; // our own Forward/ALL echo
        }
        long now = System.currentTimeMillis();
        ModerationSyncPayload.Payload payload;
        try {
            payload = ModerationSyncPayload.decode(message.payload(), now);
        } catch (ModerationSyncPayload.InvalidPayloadException e) {
            // Never log the payload: it is untrusted.
            ctx.logger().warn("Ignored an invalid moderation sync message from {}: {}", message.server(), e.reason());
            return;
        }
        if (payload.isExpired(now) && !removal(payload.action())) {
            return;
        }
        try {
            apply(payload);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not apply {} from {} to {}", payload.action(), message.server(),
                    payload.uuid() == null ? payload.ip() : payload.uuid(), e);
        }
    }

    private void apply(ModerationSyncPayload.Payload payload) {
        UUID target = payload.uuid();
        switch (payload.action()) {
            case BAN -> {
                if (target == null) {
                    return;
                }
                if (sharedDatabase) {
                    manager.reloadRemote(target);
                }
                Punishment ban = synthetic(payload, payload.permanent() ? PunishmentType.BAN : PunishmentType.TEMPBAN);
                manager.remoteKick(ban, "moderation.screen.ban", PlayerKickEvent.Cause.BANNED);
                if (payload.ip() != null) {
                    manager.remoteKickIp(payload.ip(), ban);
                }
            }
            case IPBAN -> {
                if (payload.ip() == null) {
                    return;
                }
                if (sharedDatabase && target != null) {
                    manager.reloadRemote(target);
                }
                manager.remoteKickIp(payload.ip(), synthetic(payload, PunishmentType.IPBAN));
            }
            case UNBAN -> {
                if (target != null) {
                    if (sharedDatabase) {
                        manager.reloadRemote(target);
                    } else {
                        manager.remoteClear(target, PunishmentType.BANS);
                    }
                }
            }
            case UNIPBAN -> {
                // IP bans are read from storage on login; when it is shared the row is already gone.
                if (!sharedDatabase && target != null) {
                    manager.remoteClear(target, PunishmentType.BANS);
                }
            }
            case MUTE -> {
                if (target == null) {
                    return;
                }
                manager.remoteMute(synthetic(payload, payload.permanent() ? PunishmentType.MUTE : PunishmentType.TEMPMUTE));
            }
            case UNMUTE -> {
                if (target != null) {
                    manager.remoteClear(target, PunishmentType.MUTES);
                }
            }
            case KICK -> {
                if (target != null) {
                    manager.remoteKick(synthetic(payload, PunishmentType.KICK), "moderation.screen.kick",
                            PlayerKickEvent.Cause.KICK_COMMAND);
                }
            }
        }
    }

    private static boolean removal(ModerationSyncPayload.Action action) {
        return action == ModerationSyncPayload.Action.UNBAN
                || action == ModerationSyncPayload.Action.UNIPBAN
                || action == ModerationSyncPayload.Action.UNMUTE;
    }

    /** Keeps a payload valid: the receiver rejects control characters and section signs. */
    private static String clean(String text, int max) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), max));
        for (int i = 0; i < text.length() && out.length() < max; i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == '\u00a7' || c == '\u007f' ? ' ' : c);
        }
        return out.toString();
    }

    private static Punishment synthetic(ModerationSyncPayload.Payload payload, PunishmentType type) {
        Instant created = Instant.ofEpochMilli(payload.created());
        Instant expires = payload.permanent() ? null : Instant.ofEpochMilli(payload.expires());
        return new Punishment(0, type, payload.uuid(), payload.name(), payload.ip(), null,
                payload.actor().isBlank() ? "?" : payload.actor(), payload.reason(), created, expires,
                null, null, null, null);
    }
}
