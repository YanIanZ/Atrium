package dev.yanianz.atrium.module.worlds;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * World name rules. A name typed in a command is checked before any provider sees it, so a file-based loader
 * can never be asked for {@code ../something}: only letters, digits and {@code _ - . +} (the characters of a
 * Brigadier word), at most 64, no leading dot and no {@code ..}.
 */
final class WorldNames {
    static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_\\-.+]+");

    private WorldNames() {}

    static boolean valid(String name) {
        return name != null
                && !name.isEmpty()
                && name.length() <= MAX_LENGTH
                && ALLOWED.matcher(name).matches()
                && name.charAt(0) != '.'
                && !name.contains("..");
    }

    /**
     * The file {@code <directory>/<name><suffix>}, or null when the name is invalid or the path would leave the
     * directory (checked on the normalised path as well, not only by the name rules).
     */
    static Path fileIn(Path directory, String name, String suffix) {
        if (!valid(name)) {
            return null;
        }
        Path base = directory.toAbsolutePath().normalize();
        Path file = base.resolve(name + suffix).normalize();
        if (!base.equals(file.getParent())) {
            return null;
        }
        return file;
    }
}
