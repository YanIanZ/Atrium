package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.ModerationImportService;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.config.ConfigHandle;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * {@link ModerationImportService}: stores jails and punishment records from another plugin. A record is
 * written directly (no event, no announcement, no kick); it is enforced like any stored record, and an
 * online player's cached state is refreshed so it applies immediately.
 */
final class ModerationImporter implements ModerationImportService {
    private static final Pattern JAIL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    /** Kinds that can be carried over; kicks and warnings are history only, freeze/shadow mute are Atrium-only. */
    static final Set<PunishmentType> IMPORTABLE = EnumSet.of(PunishmentType.BAN, PunishmentType.TEMPBAN,
            PunishmentType.IPBAN, PunishmentType.MUTE, PunishmentType.TEMPMUTE, PunishmentType.JAIL);

    private final PunishmentManager manager;
    private final ConfigHandle<ModerationConfig> config;

    ModerationImporter(PunishmentManager manager, ConfigHandle<ModerationConfig> config) {
        this.manager = manager;
        this.config = config;
    }

    @Override
    public Collection<String> jailNames() {
        return manager.jails().names();
    }

    @Override
    public CompletableFuture<Boolean> importJail(JailImport input, boolean overwrite) {
        String name = input.name() == null ? "" : input.name().trim();
        if (!JAIL_NAME.matcher(name).matches()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid jail name: " + input.name()));
        }
        double radius = input.radius();
        if (!Double.isFinite(radius) || radius <= 0) {
            radius = config.get().jail.defaultRadius;
        }
        StoredLocation at = input.location();
        ReleaseLocation release = input.release() == null ? null : new ReleaseLocation(input.release().world(),
                input.release().x(), input.release().y(), input.release().z(), input.release().yaw(),
                input.release().pitch());
        Optional<Jail> existing = manager.jails().get(name);
        if (existing.isPresent()) {
            if (!overwrite) {
                return CompletableFuture.completedFuture(false);
            }
            double finalRadius = radius;
            // Options edited with /jailedit (commands, max sentence, chat) are kept.
            return manager.jails().update(name, current -> new Jail(current.name(), at.world(), at.x(), at.y(), at.z(),
                    at.yaw(), at.pitch(), finalRadius, release != null ? release : current.release(),
                    current.allowedCommands(), current.maxSentence(), current.chatAllowed()))
                    .thenApply(Optional::isPresent);
        }
        Jail jail = new Jail(name, at.world(), at.x(), at.y(), at.z(), at.yaw(), at.pitch(), radius, release, List.of(),
                null, config.get().jail.chatAllowed);
        return manager.jails().save(jail).thenApply(ignored -> true);
    }

    @Override
    public CompletableFuture<Status> importPunishment(PunishmentImport input) {
        if (!IMPORTABLE.contains(input.type())) {
            return CompletableFuture.completedFuture(Status.INVALID);
        }
        Instant now = Instant.now();
        if (input.expires() != null && !input.expires().isAfter(now)) {
            return CompletableFuture.completedFuture(Status.EXPIRED);
        }
        String context = null;
        if (input.type() == PunishmentType.JAIL) {
            Optional<Jail> jail = manager.jails().resolve(input.context(), config.get().jail.defaultJail);
            if (jail.isEmpty()) {
                return CompletableFuture.completedFuture(Status.NO_JAIL);
            }
            context = jail.get().name();
        }
        String ip = null;
        if (input.type() == PunishmentType.IPBAN) {
            Optional<String> normalized = input.ip() == null ? Optional.empty() : IpAddresses.normalizeRange(input.ip());
            if (normalized.isEmpty()) {
                return CompletableFuture.completedFuture(Status.INVALID);
            }
            ip = normalized.get();
        } else if (input.target() == null) {
            return CompletableFuture.completedFuture(Status.INVALID);
        }
        Punishment draft = new Punishment(0, input.type(), input.target(), cut(input.targetName(), 16), ip, null,
                cut(input.actorName(), 64), cut(input.reason().isBlank() ? config.get().defaultReason : input.reason(), 512),
                input.created().isAfter(now) ? now : input.created(), input.expires(), null, null, null, context);
        PunishmentStore store = manager.store();
        CompletableFuture<Boolean> duplicate = input.type() == PunishmentType.IPBAN
                ? store.activeIpBans(now).thenApply(list -> list.stream().anyMatch(p -> draft.ip().equals(p.ip())))
                : store.active(input.target(), now).thenApply(list -> list.stream()
                        .anyMatch(p -> input.type().group().contains(p.type()) && p.isActive(now)));
        return duplicate.thenCompose(exists -> {
            if (exists) {
                return CompletableFuture.completedFuture(Status.DUPLICATE);
            }
            return store.insert(draft, Set.of()).thenApply(stored -> {
                if (stored.target() != null && manager.cache().contains(stored.target())) {
                    manager.cache().add(stored);
                }
                return Status.IMPORTED;
            });
        });
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
