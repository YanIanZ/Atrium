package dev.yanianz.atrium.module.display;

import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Converts text that Atrium already rendered into the string format the TAB plugin expects.
 *
 * <p>TAB's API takes strings, not components, and parses them again: {@code &} colour codes, several hex
 * formats ({@code #RRGGBB}, {@code &#RRGGBB}, {@code {#RRGGBB}}, {@code #<RRGGBB>}), gradients, MiniMessage tags
 * when MiniMessage support is on, {@code %placeholders%} (any two {@code %} signs form one) and, in scoreboard
 * lines, the {@code Long|} prefix and the {@code ||} separator. The rendered component can contain
 * player-controlled text (nicknames, PlaceholderAPI values), so every character of the displayed text that TAB
 * would read as syntax is neutralised before serialising:
 * <ul>
 *   <li>a zero-width non-joiner (U+200C, drawn with zero width by the vanilla font) is inserted after
 *       {@code & § # { < \}, so no colour code, hex code, gradient or MiniMessage tag can start there;</li>
 *   <li>the same character is inserted before {@code |}, which breaks both {@code ||} and a leading
 *       {@code Long|};</li>
 *   <li>when the text holds two or more {@code %}, every {@code %} becomes a full-width percent sign (U+FF05), so
 *       no pair can form a placeholder. A single {@code %} cannot, and is kept.</li>
 * </ul>
 * Styles are then written as {@code &} legacy codes with {@code &#rrggbb} hex colours, which TAB understands with
 * and without MiniMessage support. Fonts, shadows and click/hover events do not survive; the tab list and the
 * sidebar never use the latter.
 */
public final class TabText {
    /** Zero-width non-joiner. */
    static final String BREAK = "‌";
    /** Full-width percent sign. */
    static final String PERCENT = "％";
    private static final Pattern SYNTAX = Pattern.compile("[&§#{<\\\\|]");
    private static final Pattern SYNTAX_AND_PERCENT = Pattern.compile("[&§#{<\\\\|%]");
    private static final TextReplacementConfig ESCAPE = config(SYNTAX);
    private static final TextReplacementConfig ESCAPE_WITH_PERCENT = config(SYNTAX_AND_PERCENT);
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private TabText() {}

    /** The string to hand to TAB for this rendered component. */
    public static String toTab(Component component) {
        if (component == null) {
            return "";
        }
        return LEGACY.serialize(escape(component));
    }

    /** The component with every character TAB would parse neutralised; styles are unchanged. */
    static Component escape(Component component) {
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        return component.replaceText(countPercent(plain) >= 2 ? ESCAPE_WITH_PERCENT : ESCAPE);
    }

    private static TextReplacementConfig config(Pattern pattern) {
        return TextReplacementConfig.builder()
                .match(pattern)
                .replacement((match, builder) -> builder.content(replacement(match.group())))
                .build();
    }

    private static String replacement(String character) {
        return switch (character) {
            case "|" -> BREAK + "|";
            case "%" -> PERCENT;
            default -> character + BREAK;
        };
    }

    private static int countPercent(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '%') {
                count++;
            }
        }
        return count;
    }
}
