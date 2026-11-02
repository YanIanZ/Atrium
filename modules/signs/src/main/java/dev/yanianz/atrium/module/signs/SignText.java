package dev.yanianz.atrium.module.signs;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Restricted MiniMessage for sign text: only colours and decorations are registered. Unknown tags
 * ({@code <click:...>}, {@code <hover:...>}, {@code <gradient:...>}, {@code <selector:...>}, ...) are not
 * resolved, so MiniMessage leaves them as literal text instead of creating interactive or expensive output.
 */
public final class SignText {
    private static final MiniMessage COLORS = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.reset()))
            .build();
    private static final MiniMessage DECORATIONS = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.decorations(), StandardTags.reset()))
            .build();
    private static final MiniMessage BOTH = MiniMessage.builder()
            .tags(TagResolver.resolver(StandardTags.color(), StandardTags.decorations(), StandardTags.reset()))
            .build();

    private SignText() {}

    /**
     * Applies the permitted tags to a line. Lines without a {@code '<'} are returned unchanged so the
     * formatting another plugin already applied is preserved; when the player did type tags, the line is
     * parsed with the restricted resolver.
     */
    public static Component format(Component current, boolean colors, boolean decorations) {
        if (!colors && !decorations) {
            return current;
        }
        String plain = plain(current);
        if (plain.indexOf('<') < 0) {
            return current;
        }
        MiniMessage parser = colors && decorations ? BOTH : colors ? COLORS : DECORATIONS;
        try {
            // Keep the style another plugin already applied where the player did not ask for a different one.
            return parser.deserialize(plain).applyFallbackStyle(current.style());
        } catch (RuntimeException malformed) {
            return current;
        }
    }

    /**
     * Removes click, hover and insertion data from a component tree. A sign click event runs its command
     * with elevated permission, so interactive data must never be copied onto a sign by a player.
     */
    public static Component stripInteractive(Component component) {
        Component stripped = component.clickEvent(null).hoverEvent(null).insertion(null);
        if (stripped.children().isEmpty()) {
            return stripped;
        }
        return stripped.children(stripped.children().stream().map(SignText::stripInteractive).toList());
    }

    public static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    public static int plainLength(Component component) {
        return plain(component).length();
    }

    /** Vanilla accepts at most 384 characters per sign line; longer text must be rejected, not truncated. */
    public static boolean exceeds(Component formatted, int maxLength) {
        return plainLength(formatted) > maxLength;
    }
}
