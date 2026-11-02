package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Night speed-up and the sleeping command block. World membership and the sleeping flag are recorded on
 * the player's own thread; the speed-up timer only reads those snapshots and touches the world time on
 * the global thread, so no entity state is read across regions.
 */
final class SleepListener implements Listener {
    static final String BYPASS_PERMISSION = "atrium.worldrules.sleeping.bypass";
    private static final long INFORM_INTERVAL_MS = 3000L;

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    /** Per-player world and whether the player counts for the sleep ratio. */
    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();
    private final Set<UUID> sleeping = ConcurrentHashMap.newKeySet();
    private final Map<String, TaskHandle> timers = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> lastInform = new ConcurrentHashMap<>();

    private record Sample(String world, boolean counts) {}

    SleepListener(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    // ---- command block while sleeping ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled() || !config.get().sleeping.blockCommands) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSleeping() || player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        String root = SleepMath.commandRoot(event.getMessage());
        if (root.isEmpty() || config.get().sleeping.allows(root)) {
            return;
        }
        event.setCancelled(true);
        ctx.messages().send(player, "worldrules.sleep-no-commands");
    }

    // ---- membership snapshots ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        remember(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        remember(player);
        sleeping.remove(player.getUniqueId());
        startIfNeeded(player.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        remember(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        samples.remove(id);
        sleeping.remove(id);
        lastInform.remove(id);
        stopIfIdle(event.getPlayer().getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        sleeping.remove(event.getPlayer().getUniqueId());
        stopIfIdle(event.getPlayer().getWorld().getName());
    }

    // ---- bed events ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.useBed() != Event.Result.ALLOW) {
            return;
        }
        Player player = event.getPlayer();
        remember(player);
        sleeping.add(player.getUniqueId());
        World world = player.getWorld();
        if (!enabledIn(world.getName())) {
            return;
        }
        informSleepers(world);
        startIfNeeded(world);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        Player player = event.getPlayer();
        sleeping.remove(player.getUniqueId());
        stopIfIdle(player.getWorld().getName());
    }

    private void remember(Player player) {
        boolean counts = player.getGameMode() != GameMode.SPECTATOR && !player.isSleepingIgnored() && player.isOnline();
        samples.put(player.getUniqueId(), new Sample(player.getWorld().getName(), counts));
    }

    private boolean enabledIn(String worldName) {
        WorldRulesConfig.SpeedUp speedUp = config.get().sleeping.speedUp;
        return speedUp.enabled && speedUp.inWorld(worldName);
    }

    // ---- timer ----

    private void startIfNeeded(World world) {
        if (!enabledIn(world.getName())) {
            return;
        }
        timers.computeIfAbsent(world.getName(), name -> ctx.track(
                ctx.scheduler().globalTimer(() -> tick(name), 20L, 20L)));
    }

    private void stopIfIdle(String worldName) {
        TaskHandle handle = timers.get(worldName);
        if (handle == null) {
            return;
        }
        if (count(worldName).sleeping() == 0) {
            timers.remove(worldName);
            handle.cancel();
        }
    }

    private Counts count(String worldName) {
        int eligible = 0;
        int asleep = 0;
        for (UUID id : sleeping) {
            Sample sample = samples.get(id);
            if (sample != null && sample.counts() && sample.world().equals(worldName)) {
                asleep++;
            }
        }
        for (Sample sample : samples.values()) {
            if (sample.counts() && sample.world().equals(worldName)) {
                eligible++;
            }
        }
        return new Counts(asleep, eligible);
    }

    private record Counts(int sleeping, int eligible) {}

    private void tick(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            cancel(worldName);
            return;
        }
        WorldRulesConfig.SpeedUp speedUp = config.get().sleeping.speedUp;
        Counts counts = count(worldName);
        if (counts.sleeping() == 0 || counts.eligible() == 0) {
            cancel(worldName);
            return;
        }
        if (!Boolean.TRUE.equals(world.getGameRuleValue(GameRules.ADVANCE_TIME))) {
            cancel(worldName);
            return;
        }
        long time = world.getTime();
        if (speedUp.onlyDuringNight && !SleepMath.isNight(time)) {
            cancel(worldName);
            return;
        }
        double percent = SleepMath.percent(counts.sleeping(), counts.eligible());
        double step = SleepMath.speed(percent, speedUp.ticksPerSecond(), speedUp.requiredPercent());
        if (step <= 0) {
            if (speedUp.inform) {
                informSleepers(world);
            }
            return;
        }
        world.setTime(SleepMath.advance(time, step));
    }

    private void cancel(String worldName) {
        TaskHandle handle = timers.remove(worldName);
        if (handle != null) {
            handle.cancel();
        }
    }

    private void informSleepers(World world) {
        ConfigHandle<WorldRulesConfig> handle = config;
        WorldRulesConfig.SpeedUp speedUp = handle.get().sleeping.speedUp;
        if (!speedUp.inform) {
            return;
        }
        Counts counts = count(world.getName());
        if (counts.eligible() <= 0) {
            return;
        }
        double percent = SleepMath.percent(counts.sleeping(), counts.eligible());
        int needed = (int) Math.ceil(speedUp.requiredPercent() * 100.0);
        for (UUID id : sleeping) {
            Sample sample = samples.get(id);
            if (sample == null || !sample.world().equals(world.getName())) {
                continue;
            }
            AtomicLong last = lastInform.computeIfAbsent(id, ignored -> new AtomicLong());
            long now = System.currentTimeMillis();
            long previous = last.get();
            if (previous != 0 && now - previous < INFORM_INTERVAL_MS) {
                continue;
            }
            if (!last.compareAndSet(previous, now)) {
                continue;
            }
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                continue;
            }
            int shown = (int) Math.floor(percent * 100.0);
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline()) {
                    ctx.messages().send(player, "worldrules.sleep-progress",
                            Msg.number("percent", shown), Msg.number("needed", needed));
                }
            }, null);
        }
    }

    /** Called on module reload: drop the timers, they restart on the next bed event. */
    void reload() {
        for (TaskHandle handle : timers.values()) {
            handle.cancel();
        }
        timers.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline() && enabledIn(player.getWorld().getName()) && player.isSleeping()) {
                    startIfNeeded(player.getWorld());
                }
            }, null);
        }
    }

    void shutdown() {
        for (TaskHandle handle : timers.values()) {
            handle.cancel();
        }
        timers.clear();
        samples.clear();
        sleeping.clear();
        lastInform.clear();
    }
}
