package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.MuteInfo;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.moderation.PunishResult;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentEvent;
import dev.yanianz.atrium.api.moderation.PunishmentRequest;
import dev.yanianz.atrium.api.moderation.PunishmentRevokeEvent;
import dev.yanianz.atrium.api.moderation.PunishmentService;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.moderation.RevokeResult;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerKickEvent;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Applies, lifts and answers questions about punishments. Thread-safe. */
final class PunishmentManager implements PunishmentService, MuteService {
    static final String EXEMPT = "atrium.moderation.exempt";
    static final String NOTIFY = "atrium.moderation.notify";
    static final String LOCKIP_BYPASS = "atrium.moderation.lockip.bypass";
    private static final Set<PunishmentType> HARD_MUTES = EnumSet.of(PunishmentType.MUTE, PunishmentType.TEMPMUTE);
    private static final Set<PunishmentType> SHADOW = EnumSet.of(PunishmentType.SHADOWMUTE);
    static final Set<PunishmentType> JAIL = EnumSet.of(PunishmentType.JAIL);
    static final Set<PunishmentType> FREEZE = EnumSet.of(PunishmentType.FREEZE);

    private final ModuleContext ctx;
    private final ConfigHandle<ModerationConfig> config;
    private final PunishmentStore store;
    private final JailStore jails;
    private final Messages messages;
    private final Logger logger;
    private final ActiveCache cache = new ActiveCache();
    private @Nullable ModerationSync sync;

    PunishmentManager(ModuleContext ctx, ConfigHandle<ModerationConfig> config, PunishmentStore store, JailStore jails) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.jails = jails;
        this.messages = ctx.messages();
        this.logger = ctx.logger();
    }

    PunishmentStore store() {
        return store;
    }

    JailStore jails() {
        return jails;
    }

    ActiveCache cache() {
        return cache;
    }

    void sync(ModerationSync sync) {
        this.sync = sync;
    }

    // ---- MuteService ----

    @Override
    public boolean isMuted(UUID player) {
        return cache.active(player, HARD_MUTES, Instant.now()).isPresent();
    }

    @Override
    public boolean isShadowMuted(UUID player) {
        return cache.active(player, SHADOW, Instant.now()).isPresent();
    }

    @Override
    public Optional<MuteInfo> muteInfo(UUID player) {
        return cache.active(player, PunishmentType.MUTES, Instant.now()).map(MuteInfo::of);
    }

    // ---- PunishmentService: apply ----

    @Override
    public CompletableFuture<PunishResult> punish(PunishmentRequest request) {
        return punish(request, null);
    }

    /** Same as {@link #punish(PunishmentRequest)}; {@code feedback} also receives the announcement. */
    CompletableFuture<PunishResult> punish(PunishmentRequest raw, @Nullable CommandSender feedback) {
        ModerationConfig cfg = config.get();
        PunishmentRequest request = raw.reason().isBlank() ? raw.withReason(cfg.defaultReason) : raw;
        if (request.actor() != null && request.actor().equals(request.target())) {
            return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.INVALID));
        }
        if (request.type() == PunishmentType.JAIL) {
            Optional<Jail> jail = jails.resolve(request.context(), cfg.jail.defaultJail);
            if (jail.isEmpty()) {
                return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.NO_JAIL));
            }
            request = request.withContext(jail.get().name());
            Duration requested = request.duration();
            Duration capped = EditorInput.cap(requested, jail.get().maxSentence());
            if (!java.util.Objects.equals(capped, requested)) {
                request = request.withDuration(capped);
                if (feedback != null) {
                    messages.send(feedback, "moderation.jail.capped",
                            Msg.unparsed("jail", jail.get().name()),
                            Msg.duration("max", jail.get().maxSentence()));
                }
            }
        }
        if (request.type() == PunishmentType.KICK && online(request.target()) == null) {
            return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.INVALID));
        }
        if (request.type() == PunishmentType.IPBAN && request.ip() != null) {
            Optional<String> range = IpAddresses.normalizeRange(request.ip());
            if (range.isEmpty()) {
                return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.INVALID));
            }
            request = request.withIp(range.get());
        }
        PunishmentRequest prepared = request;
        return withIp(prepared).thenCompose(withIp -> {
            if (withIp.isEmpty()) {
                return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.NO_KNOWN_IP));
            }
            return isExempt(withIp.get()).thenCompose(exempt -> {
                if (exempt) {
                    return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.EXEMPT));
                }
                // Fired before anything is stored or enforced; a cancelled event means nothing happens.
                PunishmentEvent event = new PunishmentEvent(!Bukkit.isPrimaryThread(), withIp.get());
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) {
                    return CompletableFuture.completedFuture(PunishResult.of(PunishResult.Status.CANCELLED));
                }
                PunishmentRequest finalRequest = event.request();
                Instant now = Instant.now();
                Duration duration = finalRequest.duration();
                Punishment draft = new Punishment(0, finalRequest.type(), finalRequest.target(), finalRequest.targetName(),
                        finalRequest.ip(), finalRequest.actor(), finalRequest.actorName(), finalRequest.reason(), now,
                        duration == null ? null : now.plus(duration), null, null, null, finalRequest.context());
                // Warnings accumulate; every other kind replaces the active record of its group.
                Set<PunishmentType> replaces = finalRequest.type() == PunishmentType.WARN
                        ? EnumSet.noneOf(PunishmentType.class) : finalRequest.type().group();
                return store.insert(draft, replaces).thenApply(stored -> {
                    try {
                        afterApplied(stored, finalRequest.silent(), feedback);
                        if (feedback != null) {
                            summary(feedback, stored, false);
                        }
                    } catch (RuntimeException e) {
                        logger.error("Punishment #{} was stored but enforcing it failed", stored.id(), e);
                    }
                    return new PunishResult(PunishResult.Status.APPLIED, stored);
                });
            });
        }).exceptionally(t -> {
            logger.error("Applying {} to {} failed", raw.type(), raw.target() != null ? raw.targetName() : raw.ip(), t);
            return PunishResult.of(PunishResult.Status.FAILED);
        });
    }

    /** Fills in the address of an account IP ban. Empty when it is unknown. */
    private CompletableFuture<Optional<PunishmentRequest>> withIp(PunishmentRequest request) {
        if (request.type() != PunishmentType.IPBAN || request.ip() != null) {
            return CompletableFuture.completedFuture(Optional.of(request));
        }
        Player player = online(request.target());
        if (player != null) {
            Optional<String> ip = address(player);
            if (ip.isPresent()) {
                return CompletableFuture.completedFuture(Optional.of(request.withIp(ip.get())));
            }
        }
        return store.playerInfo(request.target()).thenApply(info -> info
                .map(PunishmentStore.PlayerInfo::lastIp)
                .flatMap(IpAddresses::normalize)
                .map(request::withIp));
    }

    /** Exempt players can only be punished by the console (or plugins acting without an actor). */
    private CompletableFuture<Boolean> isExempt(PunishmentRequest request) {
        if (request.actor() == null || request.target() == null) {
            return CompletableFuture.completedFuture(false);
        }
        Player player = online(request.target());
        if (player != null) {
            return CompletableFuture.completedFuture(player.hasPermission(EXEMPT));
        }
        // Offline: permissions are unknown, so use the flag recorded at the player's last join.
        return store.playerInfo(request.target()).thenApply(info -> info.map(PunishmentStore.PlayerInfo::exempt).orElse(false));
    }

    private void afterApplied(Punishment p, boolean silent, @Nullable CommandSender feedback) {
        UUID target = p.target();
        Player player = online(target);
        if (target != null && (player != null || cache.contains(target))) {
            cache.add(p);
        }
        switch (p.type()) {
            case BAN, TEMPBAN -> {
                if (player != null) {
                    kick(player, "moderation.screen.ban", p, PlayerKickEvent.Cause.BANNED);
                }
            }
            case IPBAN -> {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    boolean protectedPlayer = p.actor() != null && online.hasPermission(EXEMPT);
                    if (!protectedPlayer && address(online).map(a -> IpAddresses.matches(p.ip(), a)).orElse(false)) {
                        kick(online, "moderation.screen.ipban", p, PlayerKickEvent.Cause.IP_BANNED);
                    }
                }
            }
            case KICK -> {
                if (player != null) {
                    kick(player, "moderation.screen.kick", p, PlayerKickEvent.Cause.KICK_COMMAND);
                }
            }
            case MUTE, TEMPMUTE -> {
                if (player != null) {
                    messages.send(player, "moderation.target.muted", details(p, player));
                }
            }
            case WARN -> {
                if (player != null) {
                    messages.send(player, "moderation.target.warned", details(p, player));
                }
                escalate(p);
            }
            case JAIL -> {
                if (player != null) {
                    sendToJail(player, p);
                    messages.send(player, "moderation.target.jailed", details(p, player));
                } else if (target != null) {
                    // The player was jailed offline: remember that a release is still due, so the join
                    // can send them to the spawn (no pre-jail location is known).
                    store.saveReleaseLocation(target, ReleaseLocation.UNKNOWN).exceptionally(t -> {
                        logger.warn("Could not store the release marker of {}", target, t);
                        return null;
                    });
                }
            }
            case FREEZE -> {
                if (player != null) {
                    messages.send(player, "moderation.target.frozen", details(p, player));
                }
            }
            case SHADOWMUTE -> {
                // The player is deliberately not told.
            }
        }
        boolean staffOnly = silent || p.type() == PunishmentType.SHADOWMUTE;
        String broadcast = "moderation.broadcast." + p.type().name().toLowerCase(Locale.ROOT);
        if (p.type() == PunishmentType.IPBAN && p.expires() != null) {
            broadcast = "moderation.broadcast.tempipban";
        }
        announce(broadcast, staffOnly, feedback,
                audience -> details(p, audience), audience -> details(p, audience, true));
        ModerationSync current = sync;
        if (current != null) {
            current.publishApplied(p);
            if (!current.sharedBanStorage() && current.active() && feedback != null
                    && (punishmentIsBan(p.type()))) {
                messages.send(feedback, "moderation.sync.sqlite-ban");
            }
        }
    }

    private static boolean punishmentIsBan(PunishmentType type) {
        return PunishmentType.BANS.contains(type) || type == PunishmentType.IPBAN;
    }

    private void escalate(Punishment warning) {
        UUID target = warning.target();
        if (target == null) {
            return;
        }
        List<ModerationConfig.EscalationRule> rules = config.get().warnings.escalation;
        if (rules.isEmpty()) {
            return;
        }
        store.active(target, Instant.now()).thenAccept(active -> {
            int after = (int) active.stream().filter(p -> p.type() == PunishmentType.WARN).count();
            List<ModerationConfig.EscalationRule> fired = Escalation.triggered(rules, r -> r.warnings, after - 1, after);
            String name = warning.targetName() != null ? warning.targetName() : target.toString();
            for (ModerationConfig.EscalationRule rule : fired) {
                for (String command : rule.commands) {
                    String line = Escalation.expand(command, name, target.toString(), after);
                    ctx.scheduler().global(() -> {
                        logger.info("Warning escalation for {} ({} warnings): {}", name, after, line);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
                    });
                }
            }
        }).exceptionally(t -> {
            logger.error("Warning escalation for {} failed", target, t);
            return null;
        });
    }

    // ---- PunishmentService: revoke ----

    @Override
    public CompletableFuture<RevokeResult> revoke(UUID target, Set<PunishmentType> types, @Nullable UUID actor,
            String actorName, boolean silent) {
        return revoke(target, types, actor, actorName, silent, null);
    }

    CompletableFuture<RevokeResult> revoke(UUID target, Set<PunishmentType> types, @Nullable UUID actor, String actorName,
            boolean silent, @Nullable CommandSender feedback) {
        return revokeMatching(() -> store.active(target, Instant.now())
                        .thenApply(list -> list.stream().filter(p -> types.contains(p.type())).toList()),
                actor, actorName, silent, false, feedback);
    }

    @Override
    public CompletableFuture<RevokeResult> revokeIp(String ipOrRange, @Nullable UUID actor, String actorName, boolean silent) {
        return revokeIp(ipOrRange, actor, actorName, silent, null);
    }

    CompletableFuture<RevokeResult> revokeIp(String ipOrRange, @Nullable UUID actor, String actorName, boolean silent,
            @Nullable CommandSender feedback) {
        Optional<String> range = IpAddresses.normalizeRange(ipOrRange);
        if (range.isEmpty()) {
            return CompletableFuture.completedFuture(RevokeResult.of(RevokeResult.Status.NOTHING_ACTIVE));
        }
        return revokeMatching(() -> store.activeIpBans(Instant.now())
                        .thenApply(list -> list.stream().filter(p -> range.get().equals(p.ip())).toList()),
                actor, actorName, silent, false, feedback);
    }

    /** Lifts the active IP bans that were taken from an account (/unbanip &lt;player&gt;). */
    CompletableFuture<RevokeResult> revokeIpOf(UUID target, @Nullable UUID actor, String actorName, boolean silent,
            @Nullable CommandSender feedback) {
        return revokeMatching(() -> store.active(target, Instant.now())
                        .thenApply(list -> list.stream().filter(p -> p.type() == PunishmentType.IPBAN).toList()),
                actor, actorName, silent, false, feedback);
    }

    @Override
    public CompletableFuture<RevokeResult> revokeById(long id, @Nullable UUID actor, String actorName, boolean silent) {
        return revokeById(id, null, actor, actorName, silent, null);
    }

    /** Lifts one record; when {@code onlyType} is set, other kinds are treated as "not found". */
    CompletableFuture<RevokeResult> revokeById(long id, @Nullable PunishmentType onlyType, @Nullable UUID actor,
            String actorName, boolean silent, @Nullable CommandSender feedback) {
        return revokeMatching(() -> store.byId(id).thenApply(found -> found
                        .filter(p -> p.isActive(Instant.now()))
                        .filter(p -> onlyType == null || p.type() == onlyType)
                        .map(List::of).orElse(List.of())),
                actor, actorName, silent, true, feedback);
    }

    private CompletableFuture<RevokeResult> revokeMatching(Supplier<CompletableFuture<List<Punishment>>> candidates,
            @Nullable UUID actor, String actorName, boolean silent, boolean single, @Nullable CommandSender feedback) {
        return candidates.get().thenCompose(list -> {
            if (list.isEmpty()) {
                return CompletableFuture.completedFuture(RevokeResult.of(RevokeResult.Status.NOTHING_ACTIVE));
            }
            PunishmentRevokeEvent event = new PunishmentRevokeEvent(!Bukkit.isPrimaryThread(), list, actor, actorName);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return CompletableFuture.completedFuture(RevokeResult.of(RevokeResult.Status.CANCELLED));
            }
            Instant now = Instant.now();
            return store.revoke(list.stream().map(Punishment::id).toList(), actor, actorName, now).thenApply(ids -> {
                if (ids.isEmpty()) {
                    return RevokeResult.of(RevokeResult.Status.NOTHING_ACTIVE);
                }
                List<Punishment> revoked = list.stream().filter(p -> ids.contains(p.id()))
                        .map(p -> p.revoked(actor, actorName, now)).toList();
                try {
                    afterRevoked(revoked, silent, single, feedback);
                    if (feedback != null) {
                        summary(feedback, revoked.getFirst(), true);
                    }
                } catch (RuntimeException e) {
                    logger.error("Punishments {} were revoked but follow-up actions failed", ids, e);
                }
                return new RevokeResult(RevokeResult.Status.REVOKED, revoked);
            });
        }).exceptionally(t -> {
            logger.error("Revoking punishments failed", t);
            return RevokeResult.of(RevokeResult.Status.FAILED);
        });
    }

    private void afterRevoked(List<Punishment> revoked, boolean silent, boolean single, @Nullable CommandSender feedback) {
        Map<UUID, List<Long>> byTarget = new LinkedHashMap<>();
        for (Punishment p : revoked) {
            if (p.target() != null) {
                byTarget.computeIfAbsent(p.target(), k -> new ArrayList<>()).add(p.id());
            }
        }
        byTarget.forEach(cache::remove);
        Punishment first = revoked.getFirst();
        Player player = online(first.target());
        Set<PunishmentType> types = EnumSet.noneOf(PunishmentType.class);
        revoked.forEach(p -> types.add(p.type()));
        if (player != null) {
            if (types.contains(PunishmentType.JAIL)) {
                release(player, first.context());
                messages.send(player, "moderation.target.unjailed", details(first, player));
            }
            if (types.contains(PunishmentType.FREEZE)) {
                messages.send(player, "moderation.target.unfrozen", details(first, player));
            }
            if (types.contains(PunishmentType.MUTE) || types.contains(PunishmentType.TEMPMUTE)) {
                messages.send(player, "moderation.target.unmuted", details(first, player));
            }
        }
        String key;
        boolean staffOnly = silent;
        if (single && first.type() == PunishmentType.WARN) {
            key = "delwarn";
        } else if (types.contains(PunishmentType.WARN)) {
            key = "clearwarns";
        } else if (types.contains(PunishmentType.IPBAN)) {
            key = "unbanip";
        } else if (types.contains(PunishmentType.BAN) || types.contains(PunishmentType.TEMPBAN)) {
            key = "unban";
        } else if (!java.util.Collections.disjoint(types, PunishmentType.MUTES)) {
            key = "unmute";
            staffOnly |= types.equals(SHADOW);
        } else if (types.contains(PunishmentType.JAIL)) {
            key = "unjail";
        } else if (types.contains(PunishmentType.FREEZE)) {
            key = "unfreeze";
        } else {
            key = "revoked";
        }
        TagResolver count = Msg.number("count", revoked.size());
        announce("moderation.broadcast." + key, staffOnly, feedback,
                audience -> TagResolver.resolver(details(first, audience), count),
                audience -> TagResolver.resolver(details(first, audience, true), count));
        ModerationSync current = sync;
        if (current != null) {
            current.publishRevoked(revoked);
        }
    }

    // ---- PunishmentService: queries ----

    @Override
    public CompletableFuture<List<Punishment>> history(UUID target, int offset, int limit) {
        return store.history(target, offset, limit);
    }

    @Override
    public CompletableFuture<Integer> historyCount(UUID target) {
        return store.count(target);
    }

    @Override
    public CompletableFuture<List<Punishment>> active(UUID target) {
        return store.active(target, Instant.now());
    }

    @Override
    public CompletableFuture<Optional<Punishment>> activeIpBan(String address) {
        Optional<String> ip = IpAddresses.normalize(address);
        if (ip.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Instant now = Instant.now();
        return store.activeIpBans(now).thenApply(list -> list.stream()
                .filter(p -> IpBanRules.matches(p, ip.get(), now))
                .findFirst());
    }

    @Override
    public Optional<Punishment> cachedActive(UUID player, Set<PunishmentType> types) {
        return cache.active(player, types, Instant.now());
    }

    // ---- login ----

    /**
     * Blocking check for {@code AsyncPlayerPreLoginEvent}: returns the kick screen when the account or its
     * address is banned, otherwise caches the player's active punishments and returns empty.
     */
    Optional<Component> checkLogin(UUID uuid, String name, InetAddress address, int timeoutSeconds) throws Exception {
        Instant now = Instant.now();
        List<Punishment> rows = store.loginCheck(uuid, now).get(timeoutSeconds, TimeUnit.SECONDS);
        String locale = Messages.normalize(ctx.coreConfig().defaultLocale);
        for (Punishment p : rows) {
            if (uuid.equals(p.target()) && PunishmentType.BANS.contains(p.type()) && p.isActive(now)) {
                return Optional.of(messages.render(locale, "moderation.screen.ban", details(p, locale, false)));
            }
        }
        String ip = IpAddresses.normalize(address).orElse(null);
        if (ip != null) {
            for (Punishment p : rows) {
                if (IpBanRules.matches(p, ip, now)) {
                    if (p.actor() != null && store.playerInfo(uuid).get(timeoutSeconds, TimeUnit.SECONDS)
                            .map(PunishmentStore.PlayerInfo::exempt).orElse(false)) {
                        continue; // exempt accounts are only affected by IP bans issued by the console
                    }
                    return Optional.of(messages.render(locale, "moderation.screen.ipban", details(p, locale, false)));
                }
            }
        }
        List<PunishmentStore.LockedAddress> locks = store.lockedAddresses(uuid).get(timeoutSeconds, TimeUnit.SECONDS);
        List<String> allowed = locks.stream().map(PunishmentStore.LockedAddress::address).toList();
        if (LockIpRules.restricted(allowed) && !LockIpRules.allows(allowed, ip)) {
            // The bypass node is a permission: it can only be evaluated while the account is online here.
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.hasPermission(LOCKIP_BYPASS)) {
                notifyLockRefusal(name, ip, locks.size());
                return Optional.of(messages.render(locale, "moderation.screen.lockip"));
            }
        }
        cache.put(uuid, rows);
        // If the login is refused later by another plugin, the player never joins: drop the entry again.
        ctx.scheduler().asyncLater(() -> {
            if (Bukkit.getPlayer(uuid) == null) {
                cache.evict(uuid);
            }
        }, Duration.ofSeconds(60));
        return Optional.empty();
    }

    Component loginCheckFailedScreen() {
        return messages.render(Messages.normalize(ctx.coreConfig().defaultLocale), "moderation.screen.check-failed");
    }

    /** Tells online staff (and the console log) that an address lock refused a login. */
    private void notifyLockRefusal(String name, @Nullable String address, int allowed) {
        if (config.get().lockIp.notifyStaff) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission(NOTIFY)) {
                    messages.send(staff, "moderation.lockip.refused-staff",
                            Msg.unparsed("player", name),
                            Msg.unparsed("ip", address == null ? "?" : address),
                            Msg.number("count", allowed));
                }
            }
        }
        logger.info("Login of {} refused by /lockip (address {}, {} allowed)", name,
                address == null ? "unknown" : address, allowed);
    }

    // ---- cross-server application (received from another server) ----

    /** Kicks the target on this server with the given screen, if it is online here. */
    void remoteKick(Punishment punishment, String key, PlayerKickEvent.Cause cause) {
        Player player = online(punishment.target());
        if (player != null) {
            kick(player, key, punishment, cause);
        }
    }

    /** Kicks every local player whose address is in the banned range (console-issued bans spare exempt staff). */
    void remoteKickIp(String range, Punishment punishment) {
        boolean fromConsole = PunishmentRequest.CONSOLE_NAME.equalsIgnoreCase(punishment.actorName());
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!fromConsole && online.hasPermission(EXEMPT)) {
                continue;
            }
            if (address(online).map(a -> IpAddresses.matches(range, a)).orElse(false)) {
                kick(online, "moderation.screen.ipban", punishment, PlayerKickEvent.Cause.IP_BANNED);
            }
        }
    }

    /** Applies a mute received from another server to the local cache and tells the target here. */
    void remoteMute(Punishment punishment) {
        if (punishment.target() == null) {
            return;
        }
        cache.add(punishment);
        Player player = online(punishment.target());
        if (player != null) {
            messages.send(player, "moderation.target.muted", details(punishment, player));
        }
    }

    /** Clears cached records of the given kinds (a remote unban/unmute). */
    void remoteClear(UUID target, Set<PunishmentType> types) {
        cache.removeTypes(target, types);
        Player player = online(target);
        if (player != null && !java.util.Collections.disjoint(types, PunishmentType.MUTES)) {
            messages.send(player, "moderation.target.unmuted");
        }
    }

    /** Re-reads the shared database into the cache after a ban/unban issued on another server. */
    void reloadRemote(UUID target) {
        store.active(target, Instant.now()).thenAccept(list -> cache.put(target, list))
                .exceptionally(t -> {
                    logger.warn("Could not reload the punishments of {} after a proxy update", target, t);
                    return null;
                });
    }

    // ---- jail ----

    Optional<Jail> jailOf(Punishment jailPunishment) {
        return jails.resolve(jailPunishment.context(), config.get().jail.defaultJail);
    }

    /** Commands allowed inside the jail that holds this punishment; an empty per-jail list means the config. */
    java.util.List<String> jailAllowedCommands(Punishment jailPunishment) {
        return jailOf(jailPunishment)
                .map(Jail::allowedCommands)
                .filter(list -> !list.isEmpty())
                .orElse(config.get().jail.allowedCommands);
    }

    /** Whether the prisoner may chat; an unknown jail falls back to the configured default. */
    boolean jailAllowsChat(Punishment jailPunishment) {
        return jailOf(jailPunishment).map(Jail::chatAllowed).orElse(config.get().jail.chatAllowed);
    }

    void sendToJail(Player player, Punishment jailPunishment) {
        Optional<Jail> area = jailOf(jailPunishment);
        Optional<Location> location = area.map(Jail::location);
        if (location.isEmpty()) {
            logger.warn("Jail '{}' of {} does not exist or its world is not loaded", jailPunishment.context(), player.getName());
            return;
        }
        // Keep the first location seen before the player was moved into the jail; later calls are escapes.
        if (!area.get().contains(player.getLocation())) {
            store.saveReleaseLocation(player.getUniqueId(), ReleaseLocation.of(player.getLocation()))
                    .exceptionally(t -> {
                        logger.warn("Could not store the release location of {}", player.getName(), t);
                        return null;
                    });
        }
        teleport(player, location.get());
    }

    /** Ensures a release is due even when the player is already inside the jail area. */
    void markRelease(Player player) {
        store.saveReleaseLocation(player.getUniqueId(), ReleaseLocation.UNKNOWN).exceptionally(t -> {
            logger.warn("Could not store the release marker of {}", player.getName(), t);
            return null;
        });
    }

    /**
     * After unjail or jail expiry: back to where the player was before the jail, then to the world spawn
     * when configured and no stored location is available. The stored row is always consumed.
     */
    void release(Player player) {
        release(player, null);
    }

    /**
     * After unjail or jail expiry: back to where the player was before the jail, then to the jail's own
     * release location, then to the world spawn when configured. The stored row is always consumed.
     */
    void release(Player player, @Nullable String jailName) {
        boolean storedAllowed = config.get().jail.releaseToStoredLocation;
        boolean spawnAllowed = config.get().jail.releaseToSpawn;
        if (!storedAllowed && !spawnAllowed) {
            store.takeReleaseLocation(player.getUniqueId()).exceptionally(t -> {
                logger.warn("Could not clear the release location of {}", player.getName(), t);
                return null;
            });
            return;
        }
        store.takeReleaseLocation(player.getUniqueId()).handle((found, error) -> {
            if (error != null) {
                logger.warn("Could not read the release location of {}", player.getName(), error);
                return Optional.<ReleaseLocation>empty();
            }
            return found;
        }).thenAccept(found -> ctx.scheduler().entity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            Location target = storedAllowed ? found.map(ReleaseLocation::toLocation).orElse(null) : null;
            if (target == null && jailName != null) {
                ReleaseLocation jailRelease = jails.get(jailName).map(Jail::release).orElse(null);
                target = jailRelease == null ? null : jailRelease.toLocation();
            }
            if (target == null && spawnAllowed) {
                target = player.getWorld().getSpawnLocation();
            }
            if (target != null) {
                teleport(player, target);
            }
        }, null));
    }

    void teleport(Player player, Location location) {
        Optional<TeleportService> service = ctx.services().get(TeleportService.class);
        if (service.isPresent()) {
            service.get().teleportNow(player, location, TeleportService.TeleportCause.PLUGIN).exceptionally(t -> {
                logger.warn("Teleporting {} failed", player.getName(), t);
                return null;
            });
            return;
        }
        ctx.scheduler().entity(player, () -> player.teleportAsync(location).exceptionally(t -> {
            logger.warn("Teleporting {} failed", player.getName(), t);
            return false;
        }), null);
    }

    // ---- presentation ----

    private void kick(Player player, String key, Punishment p, PlayerKickEvent.Cause cause) {
        Component screen = messages.render(player, key, details(p, player));
        ctx.scheduler().entity(player, () -> player.kick(screen, cause), null);
    }

    /**
     * Sends an announcement to everyone (or, when {@code staffOnly}, to {@link #NOTIFY} holders with a silent tag)
     * and to the console. {@code feedback} receives it too if it would not otherwise.
     */
    void announce(String key, boolean staffOnly, @Nullable CommandSender feedback,
            java.util.function.Function<Audience, TagResolver> publicResolvers,
            java.util.function.Function<Audience, TagResolver> staffResolvers) {
        ModerationConfig.Feedback cfg = config.get().feedback;
        boolean feedbackServed = feedback == null || feedback instanceof ConsoleCommandSender;
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean staff = player.hasPermission(NOTIFY);
            if (staffOnly) {
                if (!staff) {
                    continue;
                }
            } else if (!(staff ? cfg.broadcastStaff : cfg.broadcastPublic)) {
                continue;
            }
            send(player, key, staffOnly, staff ? staffResolvers.apply(player) : publicResolvers.apply(player));
            if (player.equals(feedback)) {
                feedbackServed = true;
            }
        }
        CommandSender console = Bukkit.getConsoleSender();
        send(console, key, staffOnly, staffResolvers.apply(console));
        if (!feedbackServed) {
            send(feedback, key, staffOnly, staffResolvers.apply(feedback));
        }
    }

    /** One-line issuer confirmation; the broadcast is a separate, configurable line. */
    private void summary(CommandSender sender, Punishment punishment, boolean revoke) {
        if (!config.get().feedback.summary) {
            return;
        }
        String key = revoke ? "moderation.summary.revoke"
                : punishment.type().isInstant() ? "moderation.summary.instant" : "moderation.summary.punish";
        messages.send(sender, key,
                details(punishment, sender, true),
                Msg.component("type-label", messages.render(sender, HistoryView.typeKey(punishment.type()))));
    }

    private void send(Audience audience, String key, boolean silent, TagResolver resolver) {
        Component line = messages.render(audience, key, resolver);
        if (line == Component.empty()) {
            return;
        }
        audience.sendMessage(silent ? messages.render(audience, "moderation.silent-tag").append(line) : line);
    }

    TagResolver details(Punishment p, Audience audience) {
        return details(p, messages.localeOf(audience), false);
    }

    TagResolver details(Punishment p, Audience audience, boolean staff) {
        return details(p, messages.localeOf(audience), staff);
    }

    /** Placeholders describing a punishment. Addresses are masked unless {@code staff}. */
    TagResolver details(Punishment p, String locale, boolean staff) {
        Instant now = Instant.now();
        Component permanent = messages.render(locale, "moderation.permanent");
        String target = p.targetName() != null ? p.targetName()
                : p.ip() != null ? (staff ? p.ip() : IpAddresses.mask(p.ip())) : "?";
        String ip = p.ip() == null ? "" : staff ? p.ip() : IpAddresses.mask(p.ip());
        Component duration = p.expires() == null ? permanent
                : Component.text(Durations.format(Duration.between(p.created(), p.expires()), 3));
        Component remaining = p.expires() == null ? permanent
                : Component.text(Durations.format(p.remaining(now).orElse(Duration.ZERO), 3));
        Component expires = p.expires() == null ? permanent : Component.text(formatDate(p.expires()));
        return TagResolver.resolver(
                Msg.unparsed("player", target),
                Msg.unparsed("ip", ip),
                Msg.unparsed("actor", p.actorName()),
                Msg.unparsed("revoker", p.revokedByName() == null ? "" : p.revokedByName()),
                Msg.unparsed("reason", p.reason()),
                Msg.unparsed("type", p.type().name().toLowerCase(Locale.ROOT)),
                Msg.unparsed("jail", p.context() == null ? "" : p.context()),
                Msg.unparsed("date", formatDate(p.created())),
                Msg.number("id", p.id()),
                Msg.component("duration", duration),
                Msg.component("remaining", remaining),
                Msg.component("expires", expires));
    }

    String formatDate(Instant instant) {
        DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(config.get().dateFormat, Locale.ROOT);
        } catch (IllegalArgumentException e) {
            formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
        }
        return formatter.withZone(ZoneId.systemDefault()).format(instant);
    }

    // ---- helpers ----

    static @Nullable Player online(@Nullable UUID uuid) {
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    static Optional<String> address(Player player) {
        InetSocketAddress socket = player.getAddress();
        return socket == null || socket.getAddress() == null ? Optional.empty() : IpAddresses.normalize(socket.getAddress());
    }

    static String actorName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : PunishmentRequest.CONSOLE_NAME;
    }

    static @Nullable UUID actorId(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }
}
