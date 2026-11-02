package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses CMI's {@code Saves/Jails.yml}:
 * <pre>
 * town:
 *   Loc: world:-10.0:60.0:-10.0:10.0:80.0:10.0     # jail area, two corners world:x1:y1:z1:x2:y2:z2
 *   safeLoc: world;0.5;64.0;20.5;0.0;0.0          # where released players go
 *   cells:
 *     '1':
 *       location: world;0.5;61.0;0.5;90.0;0.0
 * </pre>
 * Atrium jails are a spawn point plus a radius: the first cell becomes the spawn point (the centre of the
 * area when there is no cell), and the radius covers the whole CMI area. Extra cells are reported.
 */
final class CmiJailParser {
    private CmiJailParser() {}

    /** @param radius 0 when the source has no area (the moderation module's default radius applies) */
    record ParsedJail(String name, StoredLocation spawn, double radius, StoredLocation release, int cells,
            List<String> notes, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    /** A cuboid jail area. */
    record Area(String world, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        StoredLocation centre() {
            return new StoredLocation(world, (minX + maxX) / 2, minY, (minZ + maxZ) / 2, 0f, 0f);
        }

        /** Smallest radius around {@code from} that contains every corner of the area. */
        double radiusFrom(StoredLocation from) {
            double dx = Math.max(Math.abs(from.x() - minX), Math.abs(from.x() - maxX));
            double dy = Math.max(Math.abs(from.y() - minY), Math.abs(from.y() - maxY));
            double dz = Math.max(Math.abs(from.z() - minZ), Math.abs(from.z() - maxZ));
            return Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
    }

    static List<ParsedJail> parse(ConfigurationNode root) {
        if (root == null || root.virtual()) {
            return List.of();
        }
        ConfigurationNode section = CmiNodeLocations.child(root, "jails", "Jails");
        ConfigurationNode source = section.virtual() ? root : section;
        List<ParsedJail> jails = new ArrayList<>();
        for (var entry : source.childrenMap().entrySet()) {
            if (entry.getValue().isMap()) {
                jails.add(parseOne(String.valueOf(entry.getKey()), entry.getValue()));
            }
        }
        return List.copyOf(jails);
    }

    private static ParsedJail parseOne(String rawName, ConfigurationNode node) {
        List<String> notes = new ArrayList<>();
        String name = rawName.strip();
        if (!ImportNames.valid(name)) {
            String fixed = ImportNames.sanitize(name);
            if (fixed == null) {
                return new ParsedJail(rawName, null, 0, null, 0, List.of(), "invalid jail name '" + rawName + "'");
            }
            notes.add("renamed '" + rawName + "' to '" + fixed + "'");
            name = fixed;
        }
        Optional<Area> area = area(CmiNodeLocations.text(node, "Loc", "loc", "Area", "area"));
        List<StoredLocation> cells = new ArrayList<>();
        ConfigurationNode cellNode = CmiNodeLocations.child(node, "cells", "Cells");
        if (cellNode.isMap()) {
            for (var cell : cellNode.childrenMap().values()) {
                CmiNodeLocations.read(CmiNodeLocations.child(cell, "location", "Location", "loc")).ifPresent(cells::add);
            }
        }
        StoredLocation release = CmiNodeLocations.read(CmiNodeLocations.child(node, "safeLoc", "SafeLoc", "safeLocation"))
                .orElse(null);
        StoredLocation spawn = !cells.isEmpty() ? cells.getFirst() : area.map(Area::centre).orElse(null);
        if (spawn == null) {
            return new ParsedJail(name, null, 0, release, 0, List.copyOf(notes), "jail '" + rawName + "' has no cell and no area");
        }
        double radius = area.map(a -> a.radiusFrom(spawn)).orElse(0.0);
        if (cells.size() > 1) {
            notes.add((cells.size() - 1) + " extra cell(s) not imported: an Atrium jail has one spawn point");
        }
        if (area.isPresent()) {
            notes.add("CMI's box area became a sphere of radius " + (long) radius + " around the spawn point");
        }
        return new ParsedJail(name, spawn, radius, release, cells.size(), List.copyOf(notes), null);
    }

    /** {@code world:x1:y1:z1:x2:y2:z2}; the world part may contain escaped separators. */
    static Optional<Area> area(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.trim().split(":", -1);
        if (parts.length < 7) {
            return Optional.empty();
        }
        int from = parts.length - 6;
        StringBuilder world = new StringBuilder();
        for (int i = 0; i < from; i++) {
            world.append(i == 0 ? "" : ":").append(CmiLocations.unescape(parts[i]));
        }
        double[] v = new double[6];
        for (int i = 0; i < 6; i++) {
            Double number = CmiNodeLocations.number(parts[from + i]);
            if (number == null) {
                return Optional.empty();
            }
            v[i] = number;
        }
        if (world.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Area(world.toString(), Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
                Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5])));
    }
}
