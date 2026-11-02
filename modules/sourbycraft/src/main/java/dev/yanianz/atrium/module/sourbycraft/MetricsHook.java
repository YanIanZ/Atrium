package dev.yanianz.atrium.module.sourbycraft;

import dev.iyanz.sourbycraft.api.metrics.MetricWindow;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.api.metrics.WindowMetrics;
import dev.yanianz.atrium.api.performance.RegionMetricsService;
import java.util.Optional;
import org.bukkit.Bukkit;

/** SourbyCraft's metrics runtime as {@link RegionMetricsService} for /lag. Hook class: loaded only on SourbyCraft. */
final class MetricsHook implements RegionMetricsService {
    private final SourbyMetrics metrics;

    private MetricsHook(SourbyMetrics metrics) {
        this.metrics = metrics;
    }

    static boolean start(SourbyCraftModule module) {
        SourbyMetrics metrics = Bukkit.getServicesManager().load(SourbyMetrics.class);
        if (metrics == null) {
            return false;
        }
        module.context().service(RegionMetricsService.class, new MetricsHook(metrics));
        return true;
    }

    @Override
    public Optional<Summary> summary() {
        PerformanceSnapshot snapshot = metrics.snapshot();
        if (snapshot == null) {
            return Optional.empty();
        }
        WindowMetrics regions = snapshot.window(MetricWindow.ONE_MINUTE);
        WindowMetrics global = snapshot.globalWindow(MetricWindow.ONE_MINUTE);
        return Optional.of(new Summary("SourbyCraft", snapshot.activeRegionCount(), regions.worstTps(),
                regions.medianTps(), regions.aggregateAverageMspt(), regions.estimatedP95Mspt(), regions.maximumMspt(),
                global.aggregateTps(), snapshot.freshness().state().name(), snapshot.freshness().ageMillis()));
    }
}
