package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Text-only transformations of a line before it is rendered:
 * <ul>
 *   <li>{@code &u} at the start of a line turns it into a rainbow ({@code <rainbow>}); the marker itself is not
 *       shown. The hue is decided by the client when it renders the text, so every viewer sees the same thing
 *       regardless of how often the server refreshes - the opposite of a per-viewer counter.</li>
 *   <li>{@code %filler%} pads a line with the configured filler character up to the widest line of its page, so
 *       a left, right or centre aligned block of lines keeps its shape. Every {@code %filler%} of a line shares
 *       the padding, which lets an admin put one before and one after the text.</li>
 * </ul>
 * Pure: the character width is handed in, so the result is unit tested.
 */
final class LineText {
    static final String RAINBOW_MARKER = "&u";
    static final String FILLER = "%filler%";
    static final int MAX_FILLER = 999;

    private LineText() {}

    /**
     * Prepares one page: rainbow markers are converted and {@code %filler%} is expanded against the widest line
     * of the page. Non-text lines are returned unchanged.
     *
     * @param visibleChars visible characters of a template (tags removed)
     * @param fillerChar   character used to pad; must be one visible character
     */
    static List<HoloLine> preparePage(List<HoloLine> page, ToIntFunction<String> visibleChars, String fillerChar) {
        String filler = fillerChar == null || fillerChar.isEmpty() ? " " : fillerChar.substring(0, 1);
        int target = 0;
        for (HoloLine line : page) {
            if (line.content() instanceof LineContent.Text text) {
                target = Math.max(target, visibleChars.applyAsInt(stripMarker(text.template()).replace(FILLER, "")));
            }
        }
        List<HoloLine> out = new ArrayList<>(page.size());
        for (HoloLine line : page) {
            if (!(line.content() instanceof LineContent.Text(String template))) {
                out.add(line);
                continue;
            }
            String prepared = pad(stripMarker(template), target, visibleChars, filler);
            if (hasMarker(template) && !prepared.isBlank()) {
                prepared = "<rainbow>" + prepared + "</rainbow>";
            }
            out.add(prepared.equals(line.raw()) ? line : line.withRaw(prepared));
        }
        return List.copyOf(out);
    }

    /** True when the line starts with the rainbow marker. */
    static boolean hasMarker(String template) {
        return template != null
                && template.regionMatches(true, 0, RAINBOW_MARKER, 0, RAINBOW_MARKER.length());
    }

    /** Removes the rainbow marker (with an optional space after it). */
    static String stripMarker(String template) {
        String value = template == null ? "" : template;
        if (hasMarker(value)) {
            String rest = value.substring(RAINBOW_MARKER.length());
            return rest.startsWith(" ") ? rest.substring(1) : rest;
        }
        return value;
    }

    /**
     * Replaces every {@code %filler%} with filler characters so the line reaches {@code target} visible
     * characters. The padding is shared between the placeholders, the first one taking the extra character; with
     * one placeholder before and one after the text the line stays centred.
     */
    static String pad(String template, int target, ToIntFunction<String> visibleChars, String filler) {
        if (template == null || !template.contains(FILLER)) {
            return template == null ? "" : template;
        }
        int current = visibleChars.applyAsInt(template.replace(FILLER, ""));
        int needed = Math.max(0, Math.min(MAX_FILLER, target - current));
        int placeholders = 0;
        int at = 0;
        while ((at = template.indexOf(FILLER, at)) >= 0) {
            placeholders++;
            at += FILLER.length();
        }
        if (placeholders == 0) {
            return template;
        }
        int per = needed / placeholders;
        int extra = needed % placeholders;
        StringBuilder out = new StringBuilder(template.length() + needed);
        int index = 0;
        while (index < template.length()) {
            int next = template.indexOf(FILLER, index);
            if (next < 0) {
                out.append(template, index, template.length());
                break;
            }
            out.append(template, index, next);
            int count = per + (extra > 0 ? 1 : 0);
            if (extra > 0) {
                extra--;
            }
            out.append(filler.repeat(count));
            index = next + FILLER.length();
        }
        return out.toString();
    }
}
