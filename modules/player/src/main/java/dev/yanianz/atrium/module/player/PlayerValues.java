package dev.yanianz.atrium.module.player;

import java.util.Locale;
import org.bukkit.GameMode;

/** Validated command values, independent of server state. */
final class PlayerValues {
    private PlayerValues() {}

    static float speed(int value) {
        if (value < 0 || value > 10) throw new IllegalArgumentException("Speed must be 0-10");
        return value / 10f;
    }

    static GameMode mode(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "0", "s", "gms", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "gmc", "creative" -> GameMode.CREATIVE;
            case "2", "a", "gma", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "gmsp", "spectator" -> GameMode.SPECTATOR;
            default -> throw new IllegalArgumentException("Unknown gamemode");
        };
    }

    record ExpAmount(int value, boolean levels) {}

    static ExpAmount exp(String input) {
        if (!input.matches("[0-9]+[Ll]?")) throw new IllegalArgumentException("Expected amount or amountL");
        boolean levels = input.endsWith("L") || input.endsWith("l");
        return new ExpAmount(Integer.parseInt(levels ? input.substring(0, input.length() - 1) : input), levels);
    }
}
