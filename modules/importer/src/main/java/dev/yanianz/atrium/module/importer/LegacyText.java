package dev.yanianz.atrium.module.importer;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Converts legacy Bukkit colour codes ({@code &a}, {@code §a}, {@code &#RRGGBB}, {@code &x&r&r&g&g&b&b})
 * to MiniMessage. Literal text is escaped, so text that already looks like a MiniMessage tag can never
 * become one by accident. Only the codes CMI itself understood are converted; everything else stays text.
 */
final class LegacyText {
    private static final char ESCAPE = '\u00a7';
    private static final String HEX = "0123456789abcdef";

    private LegacyText() {}

    static String toMiniMessage(String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(legacy.length());
        StringBuilder literal = new StringBuilder(legacy.length());
        int i = 0;
        while (i < legacy.length()) {
            char current = legacy.charAt(i);
            if ((current == '&' || current == ESCAPE) && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(i + 1));
                if (code == '#' && i + 7 < legacy.length()) {
                    String hex = legacy.substring(i + 2, i + 8);
                    if (isHex(hex)) {
                        flush(out, literal);
                        out.append("<#").append(hex.toUpperCase(java.util.Locale.ROOT)).append('>');
                        i += 8;
                        continue;
                    }
                }
                if (code == 'x' && i + 13 < legacy.length()) {
                    String hex = readExpandedHex(legacy, i + 2);
                    if (hex != null) {
                        flush(out, literal);
                        out.append("<#").append(hex).append('>');
                        i += 14;
                        continue;
                    }
                }
                String tag = switch (code) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    flush(out, literal);
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            literal.append(current);
            i++;
        }
        flush(out, literal);
        return out.toString();
    }

    /** Visible text of a MiniMessage string, with every tag removed and escapes resolved. */
    static String plain(String miniMessage) {
        if (miniMessage == null) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
        } catch (RuntimeException e) {
            // A malformed template must never break an import report; fall back to a tag strip.
            return MiniMessage.miniMessage().stripTags(miniMessage);
        }
    }

    private static void flush(StringBuilder out, StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }
        out.append(MiniMessage.miniMessage().escapeTags(literal.toString()));
        literal.setLength(0);
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (HEX.indexOf(Character.toLowerCase(value.charAt(i))) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Reads {@code &r&r&g&g&b&b}-style expanded hex, returning upper-case RRGGBB or null. */
    private static String readExpandedHex(String text, int from) {
        StringBuilder hex = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int at = from + i * 2;
            char marker = text.charAt(at);
            if (marker != '&' && marker != ESCAPE) {
                return null;
            }
            char digit = Character.toLowerCase(text.charAt(at + 1));
            if (HEX.indexOf(digit) < 0) {
                return null;
            }
            hex.append(Character.toUpperCase(digit));
        }
        return hex.toString();
    }
}
