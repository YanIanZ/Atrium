package dev.yanianz.atrium.module.chat;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Pure anti-caps check. Formatting is stripped before counting, player names are exempt, and a message
 * below the minimum letter count is always allowed.
 */
final class CapsFilter {
    private CapsFilter() {}

    record Settings(boolean enabled, int percentage, int minLetters, boolean lowerCase, boolean cancel,
            List<String> whitelist) {
        Settings {
            percentage = Math.max(1, Math.min(100, percentage));
            minLetters = Math.max(0, minLetters);
            whitelist = whitelist == null ? List.of() : List.copyOf(whitelist);
        }

        static Settings of(ChatConfig.Caps config) {
            if (config == null) {
                return new Settings(false, 60, 6, true, false, List.of());
            }
            return new Settings(config.enabled, config.percentage, config.minLetters, config.lowerCase,
                    config.cancel, config.whitelist);
        }
    }

    record Result(boolean exceeded, int percentage, String text) {}

    static Result check(String raw, Settings settings, Collection<String> playerNames) {
        String text = raw == null ? "" : raw;
        if (settings == null || !settings.enabled()) {
            return new Result(false, 0, text);
        }
        String visible = ChatFilter.stripFormatting(text);
        String stripped = visible.trim();
        if (stripped.isEmpty()) {
            return new Result(false, 0, text);
        }
        for (String allowed : settings.whitelist()) {
            if (allowed != null && allowed.equalsIgnoreCase(stripped)) {
                return new Result(false, 0, text);
            }
        }
        String withoutNames = visible;
        if (playerNames != null) {
            for (String name : playerNames) {
                if (name != null && !name.isBlank()) {
                    withoutNames = withoutNames.replaceAll("(?i)" + java.util.regex.Pattern.quote(name), "");
                }
            }
        }
        int letters = 0;
        int upper = 0;
        for (int i = 0; i < withoutNames.length(); i++) {
            char c = withoutNames.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            letters++;
            if (Character.isUpperCase(c)) {
                upper++;
            }
        }
        if (letters <= settings.minLetters()) {
            return new Result(false, 0, text);
        }
        int percent = (int) Math.round(upper * 100.0 / letters);
        if (percent <= settings.percentage()) {
            return new Result(false, percent, text);
        }
        return new Result(true, percent, settings.lowerCase() ? lowerCasePreserving(text) : text);
    }

    /** Lower-cases letters while leaving colour codes and MiniMessage tags untouched. */
    static String lowerCasePreserving(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean start = true;
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < raw.length()) {
                out.append(c).append(raw.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '<') {
                int close = raw.indexOf('>', i + 1);
                if (close > i + 1 && close - i <= 80 && raw.substring(i + 1, close).indexOf(' ') < 0) {
                    out.append(raw, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }
            if (Character.isLetter(c)) {
                out.append(start ? Character.toUpperCase(c) : Character.toLowerCase(c));
                start = false;
            } else {
                out.append(c);
                if (Character.isWhitespace(c)) {
                    start = true;
                }
            }
            i++;
        }
        return out.toString();
    }

    /** Lower-cases every letter, then capitalises the first letter of each word. */
    static String lowerCase(String visible) {
        String lower = visible.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean start = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (start && Character.isLetter(c)) {
                out.append(Character.toUpperCase(c));
                start = false;
            } else {
                out.append(c);
                if (Character.isWhitespace(c)) {
                    start = true;
                }
            }
        }
        return out.toString();
    }
}
