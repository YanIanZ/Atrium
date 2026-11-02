package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Resolves a chat range from the online count and checks block distance. Pure. */
final class RangeTable {
    private RangeTable() {}

    record Step(int minOnline, int range) {}

    record Position(String world, double x, double y, double z) {
        double distanceSquared(Position other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    static List<Step> compile(List<ChatConfig.RangeStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<Step> compiled = new ArrayList<>();
        for (ChatConfig.RangeStep step : steps) {
            if (step == null || step.range <= 0) {
                continue;
            }
            compiled.add(new Step(Math.max(0, step.minOnline), step.range));
        }
        compiled.sort(Comparator.comparingInt(Step::minOnline));
        return List.copyOf(compiled);
    }

    /**
     * The entry with the highest {@code minOnline} that is at most {@code online}; when every entry is above
     * the online count, the first (smallest) entry is used.
     */
    static int resolve(List<Step> steps, int online, int fallback) {
        if (steps == null || steps.isEmpty()) {
            return Math.max(1, fallback);
        }
        int selected = steps.getFirst().range();
        for (Step step : steps) {
            if (step.minOnline() <= online) {
                selected = step.range();
            } else {
                break;
            }
        }
        return Math.max(1, selected);
    }

    static boolean inRange(Position origin, Position viewer, int range) {
        if (origin == null || viewer == null) {
            return true;
        }
        if (!origin.world().equals(viewer.world())) {
            return false;
        }
        return origin.distanceSquared(viewer) <= (double) range * range;
    }
}
