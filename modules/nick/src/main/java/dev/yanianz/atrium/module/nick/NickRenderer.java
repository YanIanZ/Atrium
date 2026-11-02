package dev.yanianz.atrium.module.nick;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Restricted MiniMessage parsing for nicknames. Only colour, decoration and gradient tags are ever
 * registered; click, hover, insertion, font, selector and every other tag stay outside the parser.
 */
final class NickRenderer {
    private static final int COLOR = 1;
    private static final int FORMAT = 2;
    private static final int GRADIENT = 4;
    private static final MiniMessage[] PARSERS = new MiniMessage[8];

    static {
        for (int mask = 1; mask < PARSERS.length; mask++) {
            List<TagResolver> resolvers = new ArrayList<>(4);
            if ((mask & COLOR) != 0) {
                resolvers.add(StandardTags.color());
            }
            if ((mask & FORMAT) != 0) {
                resolvers.add(StandardTags.decorations());
            }
            if ((mask & GRADIENT) != 0) {
                resolvers.add(StandardTags.gradient());
            }
            resolvers.add(StandardTags.reset());
            PARSERS[mask] = MiniMessage.builder()
                    .tags(TagResolver.resolver(resolvers.toArray(TagResolver[]::new)))
                    .build();
        }
    }

    /**
     * Parses what the player typed. Tags they may not use stay literal text, so validation rejects them
     * instead of silently dropping their content.
     */
    Component typed(String raw, boolean color, boolean format, boolean gradient) {
        int mask = mask(color, format, gradient);
        return mask == 0 ? Component.text(raw) : PARSERS[mask].deserialize(raw);
    }

    /** Replays a stored nickname, dropping tags whose permission was revoked since it was set. */
    Component stored(NickState state, boolean color, boolean format, boolean gradient) {
        int mask = mask(color, format, gradient);
        if (mask == 0) {
            return Component.text(state.plain());
        }
        try {
            return PARSERS[mask].deserialize(NickLogic.restrictTags(state.nick(), color, format, gradient));
        } catch (RuntimeException e) {
            // A stored value must never break a join; fall back to its validated visible text.
            return Component.text(state.plain());
        }
    }

    String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** Input used for a lookup: tags removed so both "~<red>Bob" and "~Bob" resolve to "bob". */
    String lookupText(String input) {
        return MiniMessage.miniMessage().stripTags(input);
    }

    static Component decorate(String prefix, Component nick) {
        return prefix == null || prefix.isEmpty() ? nick : Component.text(prefix).append(nick);
    }

    private static int mask(boolean color, boolean format, boolean gradient) {
        return (color ? COLOR : 0) | (format ? FORMAT : 0) | (gradient ? GRADIENT : 0);
    }
}
