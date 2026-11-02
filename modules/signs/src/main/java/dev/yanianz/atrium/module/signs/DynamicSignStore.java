package dev.yanianz.atrium.module.signs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.DyeColor;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Dynamic (scrolling, per-player) signs in {@code plugins/Atrium/modules/signs/dynamic-signs.yml}.
 *
 * <p>A dynamic sign never changes the world: every viewer gets the four lines through
 * {@code Player#sendSignChange}, so the block keeps its real text (a plugin can read it, and a player who is not
 * in range sees the ordinary sign). The stored colour/glow are captured when the sign is added, so updating a
 * sign never has to read a block that may belong to another region.
 */
final class DynamicSignStore {
    static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    /** One dynamic sign. {@code lines} are MiniMessage written by an admin; {@code <player>} is the viewer. */
    record DynamicSign(String name, String world, int x, int y, int z, List<String> lines, long intervalMillis,
            int range, boolean personal, DyeColor color, boolean glowing) {
        DynamicSign {
            lines = List.copyOf(lines);
        }

        String location() {
            return world + " " + x + " " + y + " " + z;
        }

        DynamicSign withLines(List<String> newLines) {
            return new DynamicSign(name, world, x, y, z, newLines, intervalMillis, range, personal, color, glowing);
        }

        DynamicSign withInterval(long millis) {
            return new DynamicSign(name, world, x, y, z, lines, millis, range, personal, color, glowing);
        }

        DynamicSign withRange(int newRange) {
            return new DynamicSign(name, world, x, y, z, lines, intervalMillis, newRange, personal, color, glowing);
        }

        DynamicSign withPersonal(boolean value) {
            return new DynamicSign(name, world, x, y, z, lines, intervalMillis, range, value, color, glowing);
        }
    }

    private final Path file;
    private final Logger logger;
    private final Map<String, DynamicSign> signs = new LinkedHashMap<>();

    DynamicSignStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    synchronized Collection<DynamicSign> all() {
        return List.copyOf(signs.values());
    }

    synchronized List<String> names() {
        return new ArrayList<>(signs.keySet());
    }

    synchronized Optional<DynamicSign> get(String name) {
        return Optional.ofNullable(signs.get(name == null ? "" : name.toLowerCase(Locale.ROOT)));
    }

    /** The sign at a block position, if any. */
    synchronized Optional<DynamicSign> at(String world, int x, int y, int z) {
        for (DynamicSign sign : signs.values()) {
            if (sign.x() == x && sign.y() == y && sign.z() == z && sign.world().equals(world)) {
                return Optional.of(sign);
            }
        }
        return Optional.empty();
    }

    synchronized void put(DynamicSign sign) throws IOException {
        signs.put(sign.name(), sign);
        save();
    }

    synchronized Optional<DynamicSign> remove(String name) throws IOException {
        DynamicSign removed = signs.remove(name == null ? "" : name.toLowerCase(Locale.ROOT));
        if (removed != null) {
            save();
        }
        return Optional.ofNullable(removed);
    }

    /** Reads the file; a missing file is not an error. Blocking. */
    synchronized void load() throws IOException {
        signs.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().load();
        } catch (ConfigurateException e) {
            throw new IOException("cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : root.node("signs").childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (!validName(name)) {
                logger.warn("Dynamic sign '{}' skipped: names use a-z, 0-9, _ and -", name);
                continue;
            }
            try {
                signs.put(name, read(name, entry.getValue()));
            } catch (RuntimeException e) {
                logger.warn("Dynamic sign '{}' skipped: {}", name, e.getMessage());
            }
        }
    }

    private DynamicSign read(String name, ConfigurationNode node) {
        String world = node.node("world").getString("");
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("'world' is required");
        }
        int x = node.node("x").getInt(0);
        int y = node.node("y").getInt(64);
        int z = node.node("z").getInt(0);
        List<String> rawLines;
        try {
            rawLines = node.node("lines").getList(String.class, List.of());
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            throw new IllegalArgumentException("'lines' must be a list of lines");
        }
        List<String> lines = SignScroll.sanitize(rawLines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("'lines' must not be empty");
        }
        long interval = node.node("interval-millis").getLong(1000L);
        if (interval < 100) {
            throw new IllegalArgumentException("'interval-millis' must be at least 100");
        }
        int range = Math.max(1, Math.min(64, node.node("range").getInt(16)));
        boolean personal = node.node("personal").getBoolean(false);
        DyeColor color = color(node.node("color").getString("black"));
        boolean glowing = node.node("glowing").getBoolean(false);
        return new DynamicSign(name, world, x, y, z, lines, interval, range, personal, color, glowing);
    }

    private static DyeColor color(String text) {
        if (text == null || text.isBlank()) {
            return DyeColor.BLACK;
        }
        try {
            return DyeColor.valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DyeColor.BLACK;
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().createNode();
            ConfigurationNode section = root.node("signs");
            for (DynamicSign sign : signs.values()) {
                ConfigurationNode node = section.node(sign.name());
                node.node("world").set(sign.world());
                node.node("x").set(sign.x());
                node.node("y").set(sign.y());
                node.node("z").set(sign.z());
                node.node("lines").setList(String.class, sign.lines());
                node.node("interval-millis").set(sign.intervalMillis());
                node.node("range").set(sign.range());
                node.node("personal").set(sign.personal());
                node.node("color").set(sign.color().name().toLowerCase(Locale.ROOT));
                node.node("glowing").set(sign.glowing());
            }
            YamlConfigurationLoader.builder().path(file).build().save(root);
        } catch (ConfigurateException e) {
            throw new IOException("cannot write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
