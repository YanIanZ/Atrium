package dev.yanianz.atrium.module.chatbubbles;

import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/** Colour parsing shared by both renderers. Player text is never parsed here. */
final class BubbleColors {
    static final int DEFAULT_BACKGROUND = 0x40000000;
    private static final Pattern HEX = Pattern.compile("#?[0-9a-fA-F]{8}");

    private BubbleColors() {}

    static TextColor text(String raw) {
        if (raw == null || raw.isBlank()) {
            return NamedTextColor.WHITE;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) {
            TextColor hex = TextColor.fromHexString(value);
            return hex == null ? NamedTextColor.WHITE : hex;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(value);
        return named == null ? NamedTextColor.WHITE : named;
    }

    static int backgroundArgb(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_BACKGROUND;
        }
        String value = raw.trim();
        if (!HEX.matcher(value).matches()) {
            return DEFAULT_BACKGROUND;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            return (int) Long.parseLong(value, 16);
        } catch (NumberFormatException e) {
            return DEFAULT_BACKGROUND;
        }
    }

    static boolean validBackground(String raw) {
        return raw == null || raw.isBlank() || HEX.matcher(raw.trim()).matches();
    }
}
