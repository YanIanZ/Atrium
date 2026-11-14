package dev.yanianz.atrium.module.chat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.Context;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * The {@code <papi:token>} tag used by admin-written chat formats and the name hover card.
 *
 * <p>PlaceholderAPI is optional: the tag renders empty when the plugin is not enabled, when the token is
 * missing or malformed, or when the expansion throws. Only the sender's own placeholders are resolved.
 * Player-typed message text never passes through this class, so chat input cannot inject a placeholder.
 *
 * <p>The value of a placeholder is third-party text and is converted as legacy text ({@code &} and
 * {@code §} colour codes, including hex) instead of being parsed as MiniMessage.
 */
final class ChatPapi {
    private static final String PAPI_PLUGIN = "PlaceholderAPI";
    private static final int MAX_TOKEN_CHARS = 128;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final Logger logger;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    ChatPapi(Logger logger) {
        this.logger = logger;
    }

    /** Checked on every render: PlaceholderAPI can be disabled or reloaded at runtime. */
    static boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled(PAPI_PLUGIN);
    }

    /** One resolver for one render; a missing PlaceholderAPI still makes the tag consume its argument. */
    TagResolver resolver(Player sender) {
        boolean enabled = available();
        return new TagResolver() {
            @Override
            public Tag resolve(String name, ArgumentQueue arguments, Context context) {
                if (!"papi".equals(name)) {
                    return null;
                }
                if (!enabled || sender == null || !arguments.hasNext()) {
                    return Tag.inserting(Component.empty());
                }
                return Tag.inserting(value(sender, arguments.pop().value()));
            }

            @Override
            public boolean has(String name) {
                return "papi".equals(name);
            }
        };
    }

    Component value(Player sender, String token) {
        if (sender == null || token == null) {
            return Component.empty();
        }
        String cleaned = token.strip();
        if (cleaned.isEmpty() || cleaned.length() > MAX_TOKEN_CHARS || cleaned.indexOf('%') >= 0
                || cleaned.indexOf('\u00a7') >= 0 || cleaned.chars().anyMatch(Character::isISOControl)) {
            return Component.empty();
        }
        try {
            String raw = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(sender, "%" + cleaned + "%");
            return raw == null || raw.isEmpty() ? Component.empty() : legacy(raw);
        } catch (RuntimeException | LinkageError e) {
            if (reported.add(cleaned)) {
                logger.warn("PlaceholderAPI could not resolve %{}% for chat; the tag renders empty", cleaned, e);
            }
            return Component.empty();
        }
    }

    /** Converts only real legacy colour/format sequences, so an ampersand in normal text stays literal. */
    static Component legacy(String raw) {
        return LEGACY.deserialize(normaliseLegacy(raw));
    }

    static String normaliseLegacy(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '&' && c != '\u00a7') {
                out.append(c);
                continue;
            }
            if (i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = Character.toLowerCase(raw.charAt(i + 1));
            if (next == '#') {
                if (i + 7 < raw.length() && isHex(raw, i + 2, 6)) {
                    out.append('\u00a7').append('#').append(raw, i + 2, i + 8);
                    i += 7;
                } else {
                    out.append(c);
                }
                continue;
            }
            if (next == 'x') {
                int cursor = i + 2;
                boolean valid = true;
                for (int part = 0; part < 6; part++) {
                    if (cursor + 1 >= raw.length()
                            || (raw.charAt(cursor) != '&' && raw.charAt(cursor) != '\u00a7')
                            || !isHex(raw, cursor + 1, 1)) {
                        valid = false;
                        break;
                    }
                    cursor += 2;
                }
                if (valid) {
                    out.append('\u00a7').append('x');
                    int hex = i + 2;
                    for (int part = 0; part < 6; part++) {
                        out.append('\u00a7').append(raw.charAt(hex + 1));
                        hex += 2;
                    }
                    i = cursor - 1;
                } else {
                    out.append(c);
                }
                continue;
            }
            if (isCode(next)) {
                out.append('\u00a7').append(next);
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isCode(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                || (code >= 'k' && code <= 'o') || code == 'r';
    }

    private static boolean isHex(String input, int start, int length) {
        if (start + length > input.length()) {
            return false;
        }
        for (int i = start; i < start + length; i++) {
            if (Character.digit(input.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }
}
