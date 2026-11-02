package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits an admin template into MiniMessage plus placeholder slots. Each {@code %identifier_params%}
 * PlaceholderAPI token becomes a {@code <atrium_ph_N>} tag whose value is inserted as a component after
 * MiniMessage parsing, so text returned by a placeholder (a nickname, a chat value) can never inject tags.
 * {@code <viewer>} is the built-in viewer-name placeholder.
 */
final class PlaceholderText {
    static final Pattern TOKEN = Pattern.compile("%([A-Za-z0-9]+_[^%\\s]+)%");
    static final String TAG_PREFIX = "atrium_ph_";
    static final String VIEWER_TAG = "<viewer>";

    private PlaceholderText() {}

    record Prepared(String miniMessage, List<String> tokens, boolean viewerTag) {
        Prepared {
            tokens = List.copyOf(tokens);
        }

        boolean dynamic() {
            return !tokens.isEmpty() || viewerTag;
        }
    }

    static Prepared prepare(String template) {
        Matcher matcher = TOKEN.matcher(template);
        List<String> tokens = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement("<" + TAG_PREFIX + tokens.size() + ">"));
            tokens.add(matcher.group(1));
        }
        matcher.appendTail(out);
        return new Prepared(out.toString(), tokens, hasViewerTag(template));
    }

    static boolean hasTokens(String template) {
        return TOKEN.matcher(template).find();
    }

    static boolean hasViewerTag(String template) {
        return template.toLowerCase(Locale.ROOT).contains(VIEWER_TAG);
    }
}
