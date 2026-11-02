package dev.yanianz.atrium.module.display;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits PlaceholderAPI tokens ({@code %expansion_param%}) out of an admin template. Each distinct token is
 * replaced by a MiniMessage tag {@code <papi_N>}; the resolved values are later inserted as components, so
 * a value can never be re-parsed as MiniMessage or as another placeholder (no second-order injection).
 */
public final class PapiTokens {
    private static final Pattern TOKEN = Pattern.compile("%([^%\\s<>]+)%");

    /** @param template the rewritten template; {@code tokens.get(N)} belongs to {@code <papi_N>} (without percent signs) */
    public record Tokenized(String template, List<String> tokens) {
        public Tokenized {
            tokens = List.copyOf(tokens);
        }
    }

    private PapiTokens() {}

    public static String tagName(int index) {
        return "papi_" + index;
    }

    public static Tokenized tokenize(String template) {
        if (template == null || template.indexOf('%') < 0) {
            return new Tokenized(template == null ? "" : template, List.of());
        }
        Map<String, Integer> indexes = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String token = matcher.group(1);
            int index = indexes.computeIfAbsent(token, t -> indexes.size());
            matcher.appendReplacement(out, Matcher.quoteReplacement("<" + tagName(index) + ">"));
        }
        matcher.appendTail(out);
        return new Tokenized(out.toString(), new ArrayList<>(indexes.keySet()));
    }
}
