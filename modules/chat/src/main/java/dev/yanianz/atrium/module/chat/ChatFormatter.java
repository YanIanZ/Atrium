package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Builds chat lines. Player text only becomes MiniMessage when the sender has the matching permissions;
 * the restricted instances cannot create click, hover or insertion tags.
 */
final class ChatFormatter {
    private static final Pattern URL = Pattern.compile(
            "(?i)\\bhttps?://[^\\s<>]*[\\p{L}\\p{N}/]|\\bwww\\.[^\\s<>]*[\\p{L}\\p{N}/]");

    /** Renders one parsed chat token from a server-side value. Empty keeps the literal token text. */
    interface TokenRenderer {
        java.util.Optional<Component> render(Player sender, ChatTokens.Token token);
    }

    private final Messages messages;
    private final ChatConfig config;
    private final TokenRenderer tokenRenderer;
    private final MiniMessage full;
    private final MiniMessage colorOnly;
    private final MiniMessage formatOnly;
    private final MiniMessage colorAndFormat;

    ChatFormatter(Messages messages, ChatConfig config, TokenRenderer tokenRenderer) {
        this.messages = messages;
        this.config = config;
        this.tokenRenderer = tokenRenderer;
        this.full = MiniMessage.miniMessage();
        this.colorOnly = restricted(StandardTags.color());
        this.formatOnly = restricted(StandardTags.decorations());
        TagResolver both = TagResolver.resolver(StandardTags.color(), StandardTags.decorations(),
                StandardTags.reset());
        this.colorAndFormat = MiniMessage.builder().tags(both).build();
    }

    private static MiniMessage restricted(TagResolver base) {
        TagResolver tags = TagResolver.resolver(base, StandardTags.reset());
        return MiniMessage.builder().tags(tags).build();
    }

    Component playerMessage(Player sender, Component original, String raw, boolean rewritten,
            ChatColorPalette.@Nullable Choice color) {
        ChatTokens.Parsed parsed = parseTokens(raw);
        String effective = parsed.text();
        boolean changed = rewritten || !parsed.tokens().isEmpty();
        boolean advanced = sender.hasPermission("atrium.chat.advanced");
        boolean colorPermission = sender.hasPermission("atrium.chat.color");
        boolean format = sender.hasPermission("atrium.chat.format");
        Component result;
        if (advanced) {
            result = full.deserialize(effective);
        } else if (colorPermission && format) {
            result = colorAndFormat.deserialize(legacy(effective, true, true));
        } else if (colorPermission) {
            result = colorOnly.deserialize(legacy(effective, true, false));
        } else if (format) {
            result = formatOnly.deserialize(legacy(effective, false, true));
        } else {
            result = changed ? Component.text(effective) : original;
        }
        if (color != null) {
            result = ChatColorPalette.apply(result, color);
        }
        if (sender.hasPermission("atrium.chat.link")) {
            result = linkify(result);
        }
        return renderTokens(result, sender, parsed);
    }

    private ChatTokens.Parsed parseTokens(String raw) {
        ChatConfig.Items items = config.items;
        if (tokenRenderer == null || items == null || !items.enabled) {
            return new ChatTokens.Parsed(raw == null ? "" : raw, List.of());
        }
        return ChatTokens.parse(raw, items.maxPerMessage);
    }

    private Component renderTokens(Component message, Player sender, ChatTokens.Parsed parsed) {
        if (parsed.tokens().isEmpty()) {
            return message;
        }
        Component result = message;
        for (ChatTokens.Token token : parsed.tokens()) {
            java.util.Optional<Component> rendered = tokenRenderer.render(sender, token);
            Component replacement = rendered.orElseGet(() -> Component.text(token.raw()));
            result = result.replaceText(builder -> builder.matchLiteral(token.sentinel()).replacement(replacement));
        }
        return result;
    }

    Component trustedMessage(String raw) {
        return full.deserialize(raw);
    }

    Component withMentions(Component message, Pattern mentions) {
        if (mentions == null) {
            return message;
        }
        return message.replaceText(builder -> builder.match(mentions)
                .replacement((match, text) -> text.color(NamedTextColor.YELLOW)));
    }

    Component format(Player sender, Component message, Pattern mentions, String world) {
        return format(sender, message, mentions, world, Component.empty(), Component.empty());
    }

    Component format(Player sender, Component message, Pattern mentions, String world,
            Component tagPrefix, Component tagSuffix) {
        Component highlighted = withMentions(message, mentions);
        ChatConfig.FormatEntry entry = pick(sender);
        return messages.parseTrusted(entry.format,
                Msg.unparsed("player", sender.getName()),
                Msg.component("display_name", sender.displayName()),
                Msg.unparsed("world", world == null || world.isBlank() ? "?" : world),
                Msg.component("tag_prefix", tagPrefix == null ? Component.empty() : tagPrefix),
                Msg.component("tag_suffix", tagSuffix == null ? Component.empty() : tagSuffix),
                Msg.component("message", highlighted));
    }

    private ChatConfig.FormatEntry pick(Player sender) {
        List<ChatConfig.FormatEntry> entries = config.formats;
        if (entries != null) {
            for (ChatConfig.FormatEntry entry : entries) {
                if (entry == null || entry.format == null || entry.format.isBlank()) {
                    continue;
                }
                String permission = entry.permission == null ? "" : entry.permission.trim();
                if (permission.isEmpty() || sender.hasPermission(permission)) {
                    return entry;
                }
            }
        }
        return new ChatConfig.FormatEntry("", "<gray><player><dark_gray> » <white><message>");
    }

    private static Component linkify(Component component) {
        return component.replaceText(builder -> builder.match(URL).replacement((match, text) -> {
            String url = match.group();
            String target = url.toLowerCase(Locale.ROOT).startsWith("www.") ? "https://" + url : url;
            return Component.text(url)
                    .color(NamedTextColor.BLUE)
                    .decoration(TextDecoration.UNDERLINED, true)
                    .clickEvent(ClickEvent.openUrl(target));
        }));
    }

    /** Converts legacy ampersand/section codes to MiniMessage tags, honouring the permissions. */
    static String legacy(String raw, boolean color, boolean format) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c != '&' && c != '\u00a7') || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = Character.toLowerCase(raw.charAt(i + 1));
            if (next == '#') {
                if (color && i + 7 < raw.length() && isHex(raw, i + 2, 6)) {
                    out.append('<').append('#').append(raw, i + 2, i + 8).append('>');
                    i += 7;
                } else {
                    i++;
                }
                continue;
            }
            if (next == 'x') {
                if (color && i + 13 < raw.length()) {
                    StringBuilder hex = new StringBuilder(7).append('#');
                    int cursor = i + 2;
                    boolean valid = true;
                    for (int part = 0; part < 6; part++) {
                        if (cursor + 1 >= raw.length() || (raw.charAt(cursor) != '&' && raw.charAt(cursor) != '\u00a7')
                                || !isHex(raw, cursor + 1, 1)) {
                            valid = false;
                            break;
                        }
                        hex.append(raw.charAt(cursor + 1));
                        cursor += 2;
                    }
                    if (valid) {
                        out.append('<').append(hex).append('>');
                        i = cursor - 1;
                        continue;
                    }
                }
                i++;
                continue;
            }
            String colorName = colorName(next);
            if (colorName != null) {
                if (color) {
                    out.append('<').append(colorName).append('>');
                }
                i++;
                continue;
            }
            String formatName = formatName(next);
            if (formatName != null) {
                if (format) {
                    out.append('<').append(formatName).append('>');
                }
                i++;
                continue;
            }
            if (next == 'r') {
                if (color || format) {
                    out.append("<reset>");
                }
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
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

    private static String colorName(char code) {
        return switch (code) {
            case '0' -> "black";
            case '1' -> "dark_blue";
            case '2' -> "dark_green";
            case '3' -> "dark_aqua";
            case '4' -> "dark_red";
            case '5' -> "dark_purple";
            case '6' -> "gold";
            case '7' -> "gray";
            case '8' -> "dark_gray";
            case '9' -> "blue";
            case 'a' -> "green";
            case 'b' -> "aqua";
            case 'c' -> "red";
            case 'd' -> "light_purple";
            case 'e' -> "yellow";
            case 'f' -> "white";
            default -> null;
        };
    }

    private static String formatName(char code) {
        return switch (code) {
            case 'k' -> "obfuscated";
            case 'l' -> "bold";
            case 'm' -> "strikethrough";
            case 'n' -> "underlined";
            case 'o' -> "italic";
            default -> null;
        };
    }
}
