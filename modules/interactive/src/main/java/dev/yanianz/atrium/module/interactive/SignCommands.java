package dev.yanianz.atrium.module.interactive;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Parses interactive-command signs: a sign whose line is {@code [ic:name]} runs the interactive command
 * {@code name} when a player right-clicks it. Pure, so the parsing rules are unit tested.
 *
 * <p>Only the sign itself carries the binding, so nothing is persisted and an admin can place one anywhere.
 * The remaining lines are exposed to the action lines as {@code {sign_line1}} .. {@code {sign_line4}} and
 * {@code {sign_args}} (all non-empty lines joined by a space). Sign text is untrusted, so the action service
 * inserts it literally and refuses it in command lines.
 */
final class SignCommands {
    /** {@code [ic:name]}, optionally padded. Case-insensitive; the name is lower-cased. */
    static final Pattern TAG = Pattern.compile("\\[\\s*ic\\s*:\\s*([A-Za-z0-9_\\-]{1,32})\\s*]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern STRAY_IC = Pattern.compile("\\[\\s*ic\\s*:", Pattern.CASE_INSENSITIVE);

    private SignCommands() {}

    /** The command name of a sign, or empty when no line carries a tag. */
    static Optional<String> name(List<String> lines) {
        if (lines == null) {
            return Optional.empty();
        }
        for (String line : lines) {
            if (line == null) {
                continue;
            }
            Matcher matcher = TAG.matcher(line.strip());
            if (matcher.matches()) {
                return Optional.of(matcher.group(1).toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    /**
     * Variables contributed by the sign: the four lines (the tag line included, as written) and the joined
     * text of the lines that are not the tag.
     */
    static Map<String, String> variables(List<String> lines) {
        Map<String, String> vars = new HashMap<>();
        if (lines == null) {
            return vars;
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < Math.min(4, lines.size()); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).strip();
            vars.put("sign_line" + (i + 1), line);
            if (!TAG.matcher(line).matches() && !line.isEmpty()) {
                if (!joined.isEmpty()) {
                    joined.append(' ');
                }
                joined.append(line);
            }
        }
        vars.put("sign_args", joined.toString());
        return vars;
    }

    /**
     * A line that looks like a tag but does not parse (for example an unknown character in the name). Used to
     * warn the placer instead of silently doing nothing.
     */
    static @Nullable String malformedTag(List<String> lines) {
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            if (line != null && STRAY_IC.matcher(line).find() && !TAG.matcher(line.strip()).matches()) {
                return line.strip();
            }
        }
        return null;
    }
}
