package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.api.afk.AfkService;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Staff patrol: {@code /patrol} visits the player who was visited least recently, so a round walks the whole
 * server before repeating.
 *
 * <p>Unlike a naive implementation, the patrolling staff member is never a candidate, vanished players are
 * skipped (optionally), AFK players are skipped (optionally) and players with
 * {@code atrium.staff.patrol.bypass} are never visited. A visit teleports without warmup or cooldown and is
 * only recorded when the teleport actually succeeded. The visit times live in memory on purpose: a restart
 * simply starts a fresh patrol round.
 */
final class Patrol {
    static final String PERM_USE = "atrium.staff.patrol";
    static final String PERM_OTHERS = "atrium.staff.patrol.others";
    static final String PERM_BYPASS = "atrium.staff.patrol.bypass";

    private final ModuleContext ctx;
    private final Supplier<StaffConfig> config;
    private final Map<UUID, Long> lastVisit = new ConcurrentHashMap<>();

    Patrol(ModuleContext ctx, Supplier<StaffConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    /** Every player this staff member may visit, in name order. Safe from any thread. */
    List<Player> candidates(Player staff) {
        StaffConfig cfg = config.get();
        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        AfkService afk = ctx.services().get(AfkService.class).orElse(null);
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getUniqueId().equals(staff.getUniqueId())) {
                continue;
            }
            if (player.hasPermission(PERM_BYPASS)) {
                continue;
            }
            if (cfg.patrolSkipVanished && vanish != null && !vanish.canSee(staff, player)) {
                continue;
            }
            if (cfg.patrolSkipAfk && afk != null && afk.isAfk(player.getUniqueId())) {
                continue;
            }
            candidates.add(player);
        }
        candidates.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return candidates;
    }

    /** Visits the next patrol target, or {@code target} when a staff member named one. */
    void patrol(Player staff, @Nullable Player target) {
        Player chosen = target;
        if (chosen == null) {
            List<Player> candidates = candidates(staff);
            if (candidates.isEmpty()) {
                ctx.messages().send(staff, "staff.patrol-none");
                return;
            }
            List<UUID> ids = candidates.stream().map(Player::getUniqueId).toList();
            UUID next = PatrolPicker.next(ids, lastVisit).orElse(null);
            chosen = next == null ? null : candidates.stream()
                    .filter(player -> player.getUniqueId().equals(next)).findFirst().orElse(null);
        }
        if (chosen == null || !chosen.isOnline()) {
            ctx.messages().send(staff, "staff.patrol-none");
            return;
        }
        visit(staff, chosen);
    }

    private void visit(Player staff, Player target) {
        TeleportService teleport = ctx.services().get(TeleportService.class).orElse(null);
        if (teleport == null) {
            ctx.messages().send(staff, "staff.patrol-unavailable");
            return;
        }
        String name = target.getName();
        ctx.scheduler().supplyOnEntity(target, target::getLocation)
                .thenCompose(location -> teleport.teleportNow(staff, location, TeleportCause.PLUGIN))
                .whenComplete((result, error) -> onStaffThread(staff, () -> {
                    if (error != null || result != TeleportResult.SUCCESS) {
                        if (error != null) {
                            ctx.logger().warn("Patrol teleport of {} to {} failed", staff.getName(), name, error);
                        }
                        ctx.messages().send(staff, "staff.patrol-failed", Msg.unparsed("player", name));
                        return;
                    }
                    lastVisit.put(target.getUniqueId(), System.currentTimeMillis());
                    ctx.messages().send(staff, "staff.patrol-visited", Msg.unparsed("player", name));
                }));
    }

    private void onStaffThread(Player staff, Runnable task) {
        if (!staff.isOnline()) {
            return;
        }
        try {
            ctx.scheduler().entity(staff, task, null);
        } catch (RuntimeException error) {
            ctx.logger().debug("Could not report the patrol result to {}", staff.getName(), error);
        }
    }
}
