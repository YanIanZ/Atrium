package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /scan containers}: finds loaded containers that hold a material.
 *
 * <p>A global-region timer only dispatches. Every chunk is handled by a task on the region owning
 * it, and a chunk that is not loaded is skipped without generating terrain. The number of chunks
 * dispatched per tick adapts between the configured bounds and never reaches zero.
 */
final class ContainerScan extends ScanJob {
    record Hit(String world, int x, int y, int z, Material container, int amount) {}

    private final ModuleContext ctx;
    private final Scans scans;
    private final Supplier<SearchConfig> config;
    private final World world;
    private final Material material;
    private final int radius;
    private final List<ChunkSpiral.Pos> chunks;
    private final int maxResults;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger dispatched = new AtomicInteger();
    private final AtomicInteger checked = new AtomicInteger();
    private final AtomicInteger skipped = new AtomicInteger();
    private final AtomicInteger found = new AtomicInteger();
    private final AtomicInteger hidden = new AtomicInteger();
    private final List<Hit> hits = new ArrayList<>();
    private volatile int speed;
    private volatile long lastReport = System.nanoTime();

    ContainerScan(ModuleContext ctx, Scans scans, Supplier<SearchConfig> config, UUID owner, String ownerName,
            World world, int centerX, int centerZ, int radius, Material material) {
        super(owner, ownerName);
        this.ctx = ctx;
        this.scans = scans;
        this.config = config;
        this.world = world;
        this.material = material;
        this.radius = radius;
        this.chunks = ChunkSpiral.around(centerX, centerZ, radius);
        this.maxResults = config.get().maxContainerResults();
    }

    int total() {
        return chunks.size();
    }

    @Override
    void begin() {
        speed = config.get().minChunksPerTick();
        driver = ctx.track(ctx.scheduler().globalTimer(this::tick, 1, 1));
    }

    private void tick() {
        if (finished) {
            driver.cancel();
            return;
        }
        SearchConfig cfg = config.get();
        if (!cancelled) {
            speed = ScanSpeed.next(speed, inFlight.get(), cfg.minChunksPerTick(), cfg.maxChunksPerTick());
            int budget = ScanSpeed.dispatch(speed, inFlight.get());
            for (int i = 0; i < budget; i++) {
                int index = dispatched.getAndIncrement();
                if (index >= chunks.size()) {
                    dispatched.set(chunks.size());
                    break;
                }
                dispatch(chunks.get(index));
            }
        }
        if (inFlight.get() == 0 && (cancelled || dispatched.get() >= chunks.size())) {
            finish();
            return;
        }
        long now = System.nanoTime();
        if (!cancelled && now - lastReport >= cfg.progressIntervalSeconds() * 1_000_000_000L) {
            lastReport = now;
            reportProgress();
        }
    }

    private void dispatch(ChunkSpiral.Pos pos) {
        inFlight.incrementAndGet();
        Location at = new Location(world, (pos.x() << 4) + 8, 64, (pos.z() << 4) + 8);
        try {
            ctx.scheduler().region(at, () -> scanChunk(pos));
        } catch (RuntimeException e) {
            inFlight.decrementAndGet();
            skipped.incrementAndGet();
            ctx.logger().warn("Cannot schedule the container scan of chunk {},{} in {}", pos.x(), pos.z(),
                    world.getName(), e);
        }
    }

    /** Region thread owning the chunk. */
    private void scanChunk(ChunkSpiral.Pos pos) {
        try {
            if (cancelled) {
                return;
            }
            // Never load chunks: a scan only reports what is already in memory.
            if (!world.isChunkLoaded(pos.x(), pos.z())) {
                skipped.incrementAndGet();
                return;
            }
            Chunk chunk = world.getChunkAt(pos.x(), pos.z());
            for (BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof Container container) || isRightChestHalf(state)) {
                    continue;
                }
                int amount = count(container.getSnapshotInventory());
                if (amount > 0) {
                    record(state.getLocation(), state.getType(), amount);
                }
            }
            checked.incrementAndGet();
        } catch (RuntimeException e) {
            ctx.logger().warn("The container scan of chunk {},{} in {} failed", pos.x(), pos.z(), world.getName(), e);
            skipped.incrementAndGet();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private int count(Inventory inventory) {
        int amount = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                amount += stack.getAmount();
            }
        }
        return amount;
    }

    /** A double chest is one inventory; counting both halves would report it twice. */
    private static boolean isRightChestHalf(BlockState state) {
        return state instanceof org.bukkit.block.Chest chest
                && chest.getBlockData() instanceof org.bukkit.block.data.type.Chest data
                && data.getType() == org.bukkit.block.data.type.Chest.Type.RIGHT;
    }

    private void record(Location location, Material containerType, int amount) {
        found.incrementAndGet();
        synchronized (hits) {
            if (hits.size() >= maxResults) {
                hidden.incrementAndGet();
                return;
            }
            hits.add(new Hit(world.getName(), location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                    containerType, amount));
        }
    }

    private void reportProgress() {
        int done = checked.get() + skipped.get();
        int total = chunks.size();
        int foundCount = found.get();
        int currentSpeed = speed;
        Scans.deliver(ctx, owner(), audience -> {
            if (finished) {
                return;
            }
            ctx.messages().send(audience, "search.containers-progress",
                    Msg.number("done", done),
                    Msg.number("total", total),
                    Msg.number("found", foundCount),
                    Msg.number("speed", currentSpeed));
        });
    }

    private void finish() {
        finished = true;
        driver.cancel();
        scans.finished(this);
        boolean wasCancelled = cancelled;
        int foundCount = found.get();
        int done = checked.get();
        int skippedCount = skipped.get();
        int total = chunks.size();
        int hiddenCount = hidden.get();
        long secs = seconds();
        List<Hit> snapshot;
        synchronized (hits) {
            snapshot = List.copyOf(hits);
        }
        int lineLimit = config.get().maxContainerLines();
        List<Hit> shown = snapshot.size() > lineLimit ? List.copyOf(snapshot.subList(0, lineLimit)) : snapshot;
        int notShown = hiddenCount + (snapshot.size() - shown.size());
        Scans.deliver(ctx, owner(), audience -> {
            String key = wasCancelled ? "search.containers-cancelled" : "search.containers-done";
            ctx.messages().send(audience, key,
                    Msg.number("found", foundCount),
                    Msg.number("done", done),
                    Msg.number("total", total),
                    Msg.number("skipped", skippedCount),
                    Msg.number("seconds", secs));
            if (foundCount == 0) {
                ctx.messages().send(audience, "search.containers-none", Msg.number("radius", radius));
                return;
            }
            for (Hit hit : shown) {
                sendHit(audience, hit);
            }
            if (notShown > 0) {
                ctx.messages().send(audience, "search.containers-more", Msg.number("count", notShown));
            }
        });
    }

    private void sendHit(Audience audience, Hit hit) {
        Component line = ctx.messages().render(audience, "search.containers-hit",
                Msg.unparsed("container", hit.container().getKey().getKey()),
                Msg.unparsed("item", material.getKey().getKey()),
                Msg.number("amount", hit.amount()),
                Msg.unparsed("world", hit.world()),
                Msg.number("x", hit.x()),
                Msg.number("y", hit.y()),
                Msg.number("z", hit.z()));
        if (audience instanceof Player player && canTeleport(player)) {
            // Coordinates are integers we produced, never player-supplied text.
            line = line.clickEvent(ClickEvent.runCommand("/tp " + hit.x() + " " + hit.y() + " " + hit.z()));
        }
        audience.sendMessage(line);
    }

    /** The clickable teleport needs the teleport module's permission or this module's own node. */
    static boolean canTeleport(Player player) {
        return player.hasPermission("atrium.tp") || player.hasPermission("atrium.search.teleport");
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "ContainerScan[%s %s r=%d]", ownerName(), material.getKey().getKey(), radius);
    }
}
