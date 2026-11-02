package dev.yanianz.atrium.module.performance;

import java.util.Collection;
import java.util.Optional;

/** Min/average/max of region TPS samples. Pure so the Folia summary is unit-tested. */
record TpsSummary(int regions, double min, double average, double max) {
    static Optional<TpsSummary> of(Collection<Double> values) {
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        int count = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                continue;
            }
            double tps = Math.max(0, Math.min(20, value));
            count++;
            min = Math.min(min, tps);
            max = Math.max(max, tps);
            sum += tps;
        }
        if (count == 0) {
            return Optional.empty();
        }
        return Optional.of(new TpsSummary(count, min, sum / count, max));
    }
}
