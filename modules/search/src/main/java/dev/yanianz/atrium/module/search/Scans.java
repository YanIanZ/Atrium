package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Registry of running scans: one per owner, all cancellable, all stopped on module disable. */
final class Scans {
    /** Owner id shared by the console and other non-player senders. */
    static final UUID CONSOLE = new UUID(0L, 0L);

    private final Map<UUID, ScanJob> jobs = new ConcurrentHashMap<>();

    static UUID owner(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }

    /** Registers and starts the job; false when the owner already has a scan running. */
    boolean start(ScanJob job) {
        if (jobs.putIfAbsent(job.owner(), job) != null) {
            return false;
        }
        try {
            job.begin();
        } catch (RuntimeException e) {
            jobs.remove(job.owner(), job);
            throw e;
        }
        return true;
    }

    void finished(ScanJob job) {
        jobs.remove(job.owner(), job);
    }

    boolean cancel(UUID owner) {
        ScanJob job = jobs.get(owner);
        if (job == null) {
            return false;
        }
        job.cancel();
        return true;
    }

    int cancelAll() {
        int count = 0;
        for (ScanJob job : jobs.values()) {
            job.cancel();
            count++;
        }
        return count;
    }

    void shutdown() {
        for (ScanJob job : jobs.values()) {
            job.stop();
        }
        jobs.clear();
    }

    /** Delivers a message-producing action on the owner's own thread (or the console sender). */
    static void deliver(ModuleContext ctx, UUID owner, Consumer<Audience> action) {
        if (CONSOLE.equals(owner)) {
            action.accept(Bukkit.getConsoleSender());
            return;
        }
        Player player = Bukkit.getPlayer(owner);
        if (player != null) {
            ctx.scheduler().entity(player, () -> action.accept(player), null);
        }
        // The owner left: there is nobody to show the report to, so it is dropped.
    }
}
