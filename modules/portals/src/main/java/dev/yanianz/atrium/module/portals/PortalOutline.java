package dev.yanianz.atrium.module.portals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Draws box edges with particles that only the viewing admin receives. {@link #draw} must run on the
 * viewer's own thread; it only sends packets to that player and never touches the world.
 */
final class PortalOutline {
    private PortalOutline() {}

    /**
     * Points along the 12 edges of the box space {@code [min, max + 1)}, flattened as x,y,z triples. When
     * {@code spacing} would need more than {@code maxPoints} points, the spacing is widened to fit.
     */
    static double[] edgePoints(Cuboid box, double spacing, int maxPoints) {
        double x0 = box.minX();
        double y0 = box.minY();
        double z0 = box.minZ();
        double x1 = box.maxX() + 1.0;
        double y1 = box.maxY() + 1.0;
        double z1 = box.maxZ() + 1.0;
        double total = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(spacing, total / Math.max(1, maxPoints - 8));
        List<double[]> edges = List.of(
                new double[] {x0, y0, z0, x1, y0, z0}, new double[] {x0, y1, z0, x1, y1, z0},
                new double[] {x0, y0, z1, x1, y0, z1}, new double[] {x0, y1, z1, x1, y1, z1},
                new double[] {x0, y0, z0, x0, y1, z0}, new double[] {x1, y0, z0, x1, y1, z0},
                new double[] {x0, y0, z1, x0, y1, z1}, new double[] {x1, y0, z1, x1, y1, z1},
                new double[] {x0, y0, z0, x0, y0, z1}, new double[] {x1, y0, z0, x1, y0, z1},
                new double[] {x0, y1, z0, x0, y1, z1}, new double[] {x1, y1, z0, x1, y1, z1});
        List<Double> out = new ArrayList<>();
        for (double[] e : edges) {
            double length = Math.abs(e[3] - e[0]) + Math.abs(e[4] - e[1]) + Math.abs(e[5] - e[2]);
            int segments = Math.max(1, (int) Math.ceil(length / step));
            // Skip the last point of each edge: it is the first point of a neighbouring edge (corners once).
            for (int i = 0; i < segments; i++) {
                double t = (double) i / segments;
                out.add(e[0] + (e[3] - e[0]) * t);
                out.add(e[1] + (e[4] - e[1]) * t);
                out.add(e[2] + (e[5] - e[2]) * t);
            }
        }
        double[] result = new double[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /** Resolves a particle name; particles needing extra data are rejected. */
    static Particle particle(String name, Logger logger) {
        try {
            Particle particle = Particle.valueOf(name.strip().toUpperCase(Locale.ROOT));
            if (particle.getDataType() == Void.class) {
                return particle;
            }
            logger.warn("Portal outline particle {} needs extra data; using HAPPY_VILLAGER", name);
        } catch (IllegalArgumentException e) {
            logger.warn("Unknown portal outline particle '{}'; using HAPPY_VILLAGER", name);
        }
        return Particle.HAPPY_VILLAGER;
    }

    /** Sends the outline to the viewer only. Returns the number of particles sent. */
    static int draw(Player viewer, Cuboid box, Particle particle, double spacing, int budget, double maxDistance) {
        if (budget <= 0) {
            return 0;
        }
        Location eye = viewer.getLocation();
        double maxSq = maxDistance * maxDistance;
        double[] points = edgePoints(box, spacing, budget);
        int sent = 0;
        for (int i = 0; i + 2 < points.length && sent < budget; i += 3) {
            double dx = points[i] - eye.getX();
            double dy = points[i + 1] - eye.getY();
            double dz = points[i + 2] - eye.getZ();
            if (dx * dx + dy * dy + dz * dz > maxSq) {
                continue;
            }
            viewer.spawnParticle(particle, points[i], points[i + 1], points[i + 2], 1, 0, 0, 0, 0);
            sent++;
        }
        return sent;
    }
}
