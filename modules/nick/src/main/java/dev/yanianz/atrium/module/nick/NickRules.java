package dev.yanianz.atrium.module.nick;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.function.Consumer;

/** Immutable validation settings; changing the config rebuilds this snapshot. */
record NickRules(
        int minLength,
        int maxLength,
        String allowedPattern,
        Pattern allowed,
        List<String> blockedWords,
        boolean normalizeBlocked,
        int maxRawLength) {

    static final String DEFAULT_PATTERN = "[A-Za-z0-9_-]+";

    static NickRules from(NickConfig config, Consumer<String> warning) {
        int min = Math.clamp(config.minLength, 1, 32);
        int max = Math.clamp(config.maxLength, min, 32);
        String pattern = config.allowedCharacters == null || config.allowedCharacters.isBlank()
                ? DEFAULT_PATTERN
                : config.allowedCharacters;
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            warning.accept("Invalid nickname allowedCharacters regex, using " + DEFAULT_PATTERN + ": " + e.getMessage());
            pattern = DEFAULT_PATTERN;
            compiled = Pattern.compile(pattern);
        }
        List<String> blocked = new ArrayList<>();
        if (config.blockedWords != null) {
            for (String word : config.blockedWords) {
                if (word == null || word.isBlank()) {
                    continue;
                }
                String normalized = NickLogic.normalizeWord(config.normalizeBlockedWords, word);
                if (!normalized.isEmpty()) {
                    blocked.add(normalized);
                }
            }
        }
        return new NickRules(min, max, pattern, compiled, List.copyOf(blocked),
                config.normalizeBlockedWords, Math.clamp(config.maxRawLength, 1, 4096));
    }
}
