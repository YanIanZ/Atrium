package dev.yanianz.atrium.module.recipes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

/**
 * Restricted MiniMessage for anvil renames: only colours and decorations are registered. Unknown tags
 * ({@code <click:...>}, {@code <hover:...>}, {@code <gradient:...>}, {@code <lang:...>}, ...) are left as
 * literal text, so player-typed text can never create interactive or expensive output (the IT-1 class of
 * bugs). PlaceholderAPI is never run on a rename.
 */
public final class AnvilText {
    private static final MiniMessage COLORS = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.reset()))
            .build();
    private static final MiniMessage DECORATIONS = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.decorations(), StandardTags.reset()))
            .build();
    private static final MiniMessage BOTH = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.reset()))
            .build();

    private AnvilText() {}

    /**
     * Formats an anvil rename. Returns {@code null} when nothing should change: no permission, no tags, a
     * malformed tag, or plain text longer than {@code maxLength}. Returning {@code null} leaves the vanilla
     * plain-text name in place instead of truncating it.
     */
    @Nullable
    public static Component format(String input, boolean colors, boolean decorations, int maxLength) {
        if (input == null || input.isEmpty() || (!colors && !decorations)) {
            return null;
        }
        if (input.indexOf('<') < 0) {
            return null;
        }
        MiniMessage parser = colors && decorations ? BOTH : colors ? COLORS : DECORATIONS;
        Component formatted;
        try {
            formatted = parser.deserialize(input);
        } catch (RuntimeException malformed) {
            return null;
        }
        int plainLength = PlainTextComponentSerializer.plainText().serialize(formatted).length();
        int limit = Math.clamp(maxLength, 1, 256);
        return plainLength > limit ? null : formatted;
    }
}
