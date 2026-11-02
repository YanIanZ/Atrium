package dev.yanianz.atrium.module.worldtools;

import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Runs /replaceblock jobs. A global-region timer only dispatches: every chunk is fetched with
 * {@code getChunkAtAsync(x, z, false)} (never generated, never loaded synchronously; ungenerated chunks are
 * skipped) and edited on the thread owning that chunk. No chunk tickets are added, so nothing is leaked.
 */
final class ReplaceJobs {
    enum StartResult { STARTED, ALREADY_RUNNING, TOO_MANY }

    record Area(World world, int centerX, int centerZ, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {}

    static final class Job {
        final UUID owner;
        final String ownerName;
        final Area area;
        final Material from;
        final List<Replacement.Choice<Material>> to;
        final List<ChunkOrder.ChunkPos> chunks;
        final long seed;
        final long startedAt = System.nanoTime();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger processed = new AtomicInteger();
        final AtomicInteger skipped = new AtomicInteger();
        final AtomicLong replaced = new AtomicLong();
        final AtomicLong protectedBlocks = new AtomicLong();
        volatile boolean cancelled;
        volatile boolean finished;
        volatile TaskHandle driver = TaskHandle.NOOP;
        // Driver-only state (one global-region task at a time).
        int next;
        int speed;
        long lastReport = System.nanoTime();

        Job(UUID owner, String ownerName, Area area, Material from, List<Replacement.Choice<Material>> to, long seed) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.area = area;
            this.from = from;
            this.to = to;
            this.seed = seed;
            this.chunks = ChunkOrder.covering(area.centerX(), area.centerZ(), area.minX(), area.maxX(), area.minZ(), area.maxZ());
        }

        int total() {
            return chunks.size();
        }
    }

    private final ModuleContext ctx;
    private final java.util.function.Supplier<WorldToolsConfig> config;
    private final ProtectionService protection;
    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final SecureRandom seeds = new SecureRandom();

    ReplaceJobs(ModuleContext ctx, java.util.function.Supplier<WorldToolsConfig> config,
            ProtectionService protection) {
        this.ctx = ctx;
        this.config = config;
        this.protection = protection;
    }

    /** Job owner id for the console and other non-player senders (one shared slot). */
    static final UUID CONSOLE = new UUID(0L, 0L);

    synchronized StartResult start(UUID ownerId, String ownerName, Area area, Material from,
            List<Replacement.Choice<Material>> to) {
        if (jobs.containsKey(ownerId)) {
            return StartResult.ALREADY_RUNNING;
        }
        if (jobs.size() >= config.get().maxConcurrentJobs()) {
            return StartResult.TOO_MANY;
        }
        Job job = new Job(ownerId, ownerName, area, from, to, seeds.nextLong());
        job.speed = config.get().minChunksPerBatch();
        jobs.put(job.owner, job);
        int interval = config.get().batchIntervalTicks();
        job.driver = ctx.track(ctx.scheduler().globalTimer(() -> tick(job), 1, interval));
        ctx.logger().info("{} started /replaceblock {} -> {} in {} ({} chunks)", job.ownerName, from.getKey(),
                describe(to), area.world().getName(), job.total());
        return StartResult.STARTED;
    }

    /** Requests cancellation; the job reports once its in-flight chunks have finished. */
    boolean cancel(UUID owner) {
        Job job = jobs.get(owner);
        if (job == null) {
            return false;
        }
        job.cancelled = true;
        return true;
    }

    int cancelAll() {
        int n = 0;
        for (Job job : jobs.values()) {
            job.cancelled = true;
            n++;
        }
        return n;
    }

    Optional<Job> job(UUID owner) {
        return Optional.ofNullable(jobs.get(owner));
    }

    /** Module disable: stop dispatching; region tasks already queued see the flag and do nothing. */
    void shutdown() {
        for (Job job : jobs.values()) {
            job.cancelled = true;
            job.driver.cancel();
        }
        jobs.clear();
    }

    // ---- driver (global region) -----------------------------------------------------------------

    private void tick(Job job) {
        if (job.finished) {
            job.driver.cancel();
            return;
        }
        WorldToolsConfig cfg = config.get();
        if (!job.cancelled) {
            job.speed = SpeedControl.next(job.speed, job.inFlight.get(), cfg.minChunksPerBatch(), cfg.maxChunksPerBatch());
            int count = SpeedControl.dispatch(job.speed, job.inFlight.get());
            for (int i = 0; i < count && job.next < job.total(); i++) {
                dispatch(job, job.chunks.get(job.next++));
            }
        }
        boolean drained = job.inFlight.get() == 0;
        if (drained && (job.cancelled || job.next >= job.total())) {
            finish(job);
            return;
        }
        long now = System.nanoTime();
        if (now - job.lastReport >= cfg.progressIntervalSeconds() * 1_000_000_000L) {
            job.lastReport = now;
            tell(job, "worldtools.replace-progress", progress(job));
        }
    }

    private void dispatch(Job job, ChunkOrder.ChunkPos pos) {
        World world = job.area.world();
        job.inFlight.incrementAndGet();
        CompletableFuture<Chunk> future;
        try {
            future = world.getChunkAtAsync(pos.x(), pos.z(), false);
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot request chunk {},{} in {} for /replaceblock", pos.x(), pos.z(), world.getName(), e);
            job.skipped.incrementAndGet();
            job.inFlight.decrementAndGet();
            return;
        }
        future.whenComplete((chunk, error) -> {
            if (error != null) {
                ctx.logger().warn("Loading chunk {},{} in {} failed for /replaceblock", pos.x(), pos.z(), world.getName(), error);
                job.skipped.incrementAndGet();
                job.inFlight.decrementAndGet();
            } else if (chunk == null) {
                // Not generated: skip, never generate terrain for a replace job.
                job.skipped.incrementAndGet();
                job.inFlight.decrementAndGet();
            } else if (Bukkit.isOwnedByCurrentRegion(world, pos.x(), pos.z())) {
                process(job, pos); // still loaded: we are inside the load callback on the owning thread
            } else {
                Location at = new Location(world, (pos.x() << 4) + 8, job.area.minY(), (pos.z() << 4) + 8);
                try {
                    ctx.scheduler().region(at, () -> process(job, pos));
                } catch (RuntimeException e) {
                    ctx.logger().warn("Cannot schedule /replaceblock for chunk {},{} in {}", pos.x(), pos.z(), world.getName(), e);
                    job.skipped.incrementAndGet();
                    job.inFlight.decrementAndGet();
                }
            }
        });
    }

    /** Region thread owning the chunk. */
    private void process(Job job, ChunkOrder.ChunkPos pos) {
        World world = job.area.world();
        try {
            if (job.cancelled || !world.isChunkLoaded(pos.x(), pos.z())) {
                job.skipped.incrementAndGet();
                return;
            }
            Chunk chunk = world.getChunkAt(pos.x(), pos.z());
            Player actor = CONSOLE.equals(job.owner) ? null : Bukkit.getPlayer(job.owner);
            job.replaced.addAndGet(replaceIn(job, chunk, pos, actor));
            job.processed.incrementAndGet();
        } catch (RuntimeException e) {
            ctx.logger().warn("/replaceblock failed in chunk {},{} of {}", pos.x(), pos.z(), world.getName(), e);
            job.skipped.incrementAndGet();
        } finally {
            job.inFlight.decrementAndGet();
        }
    }

    private long replaceIn(Job job, Chunk chunk, ChunkOrder.ChunkPos pos, Player actor) {
        Area a = job.area;
        int baseX = pos.x() << 4;
        int baseZ = pos.z() << 4;
        int fromX = Math.max(a.minX(), baseX) - baseX;
        int toX = Math.min(a.maxX(), baseX + 15) - baseX;
        int fromZ = Math.max(a.minZ(), baseZ) - baseZ;
        int toZ = Math.min(a.maxZ(), baseZ + 15) - baseZ;
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        SplittableRandom random = new SplittableRandom(Replacement.chunkSeed(job.seed, pos.x(), pos.z()));
        long count = 0;
        for (int x = fromX; x <= toX; x++) {
            for (int z = fromZ; z <= toZ; z++) {
                for (int y = a.minY(); y <= a.maxY(); y++) {
                    if (snapshot.getBlockType(x, y, z) != job.from) {
                        continue;
                    }
                    Block block = chunk.getBlock(x, y, z);
                    if (protection != null && !CONSOLE.equals(job.owner)) {
                        if (actor == null || !protection.canBuild(actor, block.getLocation())
                                || !protection.canBreak(actor, block)) {
                            // Keep running when the owner logged out: never replace protected land with
                            // no player to ask. The count is reported at the end.
                            job.protectedBlocks.incrementAndGet();
                            continue;
                        }
                    }
                    Optional<Material> replacement = Replacement.pick(job.to, random);
                    if (replacement.isPresent() && replacement.get() != job.from) {
                        block.setType(replacement.get(), false);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private void finish(Job job) {
        job.finished = true;
        job.driver.cancel();
        jobs.remove(job.owner, job);
        String key = job.cancelled ? "worldtools.replace-cancelled" : "worldtools.replace-done";
        ctx.logger().info("/replaceblock of {} {}: {} blocks replaced, {} chunks processed, {} skipped", job.ownerName,
                job.cancelled ? "cancelled" : "finished", job.replaced.get(), job.processed.get(), job.skipped.get());
        tell(job, key, progress(job));
    }

    private static TagResolver progress(Job job) {
        int done = job.processed.get() + job.skipped.get();
        long seconds = (System.nanoTime() - job.startedAt) / 1_000_000_000L;
        return TagResolver.resolver(Msg.number("done", done), Msg.number("total", job.total()),
                Msg.number("percent", job.total() == 0 ? 100 : done * 100L / job.total()),
                Msg.number("replaced", job.replaced.get()), Msg.number("skipped", job.skipped.get()),
                Msg.number("protected", job.protectedBlocks.get()),
                Msg.number("speed", job.speed), Msg.number("seconds", seconds));
    }

    private void tell(Job job, String key, TagResolver tags) {
        if (CONSOLE.equals(job.owner)) {
            ctx.messages().send(Bukkit.getConsoleSender(), key, tags);
            return;
        }
        Player player = Bukkit.getPlayer(job.owner);
        if (player != null) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        }
    }

    static String describe(List<Replacement.Choice<Material>> to) {
        StringBuilder out = new StringBuilder();
        for (Replacement.Choice<Material> choice : to) {
            if (!out.isEmpty()) {
                out.append('+');
            }
            out.append(choice.value().getKey().getKey());
            if (choice.chance() < 100) {
                out.append('%').append(java.math.BigDecimal.valueOf(choice.chance()).stripTrailingZeros().toPlainString());
            }
        }
        return out.toString();
    }
}
