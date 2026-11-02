package dev.yanianz.atrium.module.particles.shape;

import java.util.ArrayList;
import java.util.List;

/**
 * A wings bitmap. Each {@code '#'} becomes one particle, {@code '.'} an empty cell. The pattern is read
 * from the module config, so admins can shape the wings without touching code.
 */
public record WingPattern(List<String> rows) {
    public static final int MAX_ROWS = 32;
    public static final int MAX_COLUMNS = 64;

    public WingPattern {
        List<String> copy = new ArrayList<>(rows.size());
        for (String row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("Wing pattern rows must not be null");
            }
            String cleaned = row.replace(' ', '.').strip();
            if (cleaned.length() > MAX_COLUMNS) {
                throw new IllegalArgumentException("Wing pattern rows may have at most " + MAX_COLUMNS + " columns");
            }
            copy.add(cleaned);
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("Wing pattern needs at least one row");
        }
        if (copy.size() > MAX_ROWS) {
            throw new IllegalArgumentException("Wing pattern may have at most " + MAX_ROWS + " rows");
        }
        rows = List.copyOf(copy);
    }

    /** The default bat-like wings. */
    public static WingPattern defaults() {
        return new WingPattern(List.of(
                "#.........#",
                "##.......##",
                "###.....###",
                "####...####",
                ".#########.",
                "..#######..",
                "...#####..."));
    }

    /** Parses and validates raw config lines. */
    public static WingPattern parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return defaults();
        }
        WingPattern pattern = new WingPattern(raw);
        if (!pattern.hasPoints()) {
            throw new IllegalArgumentException("Wing pattern needs at least one '#'");
        }
        return pattern;
    }

    public int columns() {
        int max = 0;
        for (String row : rows) {
            max = Math.max(max, row.length());
        }
        return max;
    }

    public boolean hasPoints() {
        for (String row : rows) {
            if (row.indexOf('#') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** Number of particle cells. */
    public int pointCount() {
        int count = 0;
        for (String row : rows) {
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) == '#') {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Particle offsets relative to the player, in local space (+Z forward). The bitmap is centred on the
     * spine and starts at shoulder height. {@code radius} controls the half width.
     */
    public List<Point> points(double radius) {
        double width = Math.max(1.0, (columns() - 1) / 2.0);
        double maxCell = rows.size() > 1 ? 1.8 / (rows.size() - 1) : 8.0;
        double cell = Math.max(0.02, Math.min(Math.max(0.02, radius) / width, maxCell));
        double centre = (columns() - 1) / 2.0;
        List<Point> points = new ArrayList<>(pointCount());
        for (int row = 0; row < rows.size(); row++) {
            String line = rows.get(row);
            double y = Math.max(0.05, 1.85 - row * cell);
            for (int column = 0; column < line.length(); column++) {
                if (line.charAt(column) == '#') {
                    points.add(new Point((column - centre) * cell, y, -0.25));
                }
            }
        }
        return points;
    }

    public record Point(double x, double y, double z) {}
}
