package dev.yanianz.atrium.module.discord;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Minimal JSON writer for the Discord webhook payload. Keeps escaping in one tested place. */
public final class DiscordJson {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final int MAX_AVATAR_LENGTH = 2048;

    private DiscordJson() {}

    /**
     * Builds a webhook execution payload. {@code allowed_mentions.parse} is always empty so neither a player
     * nor an admin template can ping @everyone, @here, a role or a user.
     */
    public static String payload(String content, @Nullable String username, @Nullable String avatarUrl) {
        StringBuilder out = new StringBuilder(content.length() + 160);
        out.append('{');
        out.append("\"content\":").append(quote(content));
        if (username != null && !username.isBlank()) {
            out.append(",\"username\":").append(quote(username));
        }
        if (avatarUrl != null && isHttps(avatarUrl)) {
            out.append(",\"avatar_url\":").append(quote(avatarUrl));
        }
        out.append(",\"allowed_mentions\":{\"parse\":[]}");
        out.append('}');
        return out.toString();
    }

    /** A quoted JSON string; control characters and line separators are escaped, everything else stays UTF-8. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else if (c == '\u2028' || c == '\u2029') {
                        out.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                                .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static boolean isHttps(@Nullable String url) {
        if (url == null || url.length() > MAX_AVATAR_LENGTH) {
            return false;
        }
        return url.toLowerCase(Locale.ROOT).startsWith("https://");
    }
}
