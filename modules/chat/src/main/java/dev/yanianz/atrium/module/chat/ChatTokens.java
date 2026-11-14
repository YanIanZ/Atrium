package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds {@code [item]}, {@code [item:slot]}, {@code [sprite:name]}, {@code [inv]} and {@code [ender]} tokens
 * in player text and replaces them with a private-use sentinel that survives MiniMessage parsing. The caller
 * renders the sentinel from a server-side value, so no item, sprite or snapshot data is ever taken from the
 * typed text.
 */
final class ChatTokens {
    private ChatTokens() {}

    private static final Pattern TOKEN = Pattern.compile(
            "\\[item(?::([A-Za-z0-9_]+))?\\]|\\[sprite:(?:([A-Za-z0-9_.-]+):)?([A-Za-z0-9_./-]+)\\]"
                    + "|\\[(inv|ender)\\]");

    sealed interface Token permits Item, Sprite, Showcase {
        String raw();

        String sentinel();
    }

    record Item(String slot, String raw, String sentinel) implements Token {}

    record Sprite(String atlas, String name, String raw, String sentinel) implements Token {}

    /** {@code kind} is {@code inv} or {@code ender}; the id is resolved when the token is rendered. */
    record Showcase(String kind, String raw, String sentinel) implements Token {}

    record Parsed(String text, List<Token> tokens) {
        Parsed {
            tokens = List.copyOf(tokens);
        }
    }

    static Parsed parse(String raw, int maxTokens) {
        String input = raw == null ? "" : raw;
        StringBuilder clean = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c < '\uE000' || c > '\uE0FF') {
                clean.append(c);
            }
        }
        if (maxTokens <= 0) {
            return new Parsed(clean.toString(), List.of());
        }
        String text = clean.toString();
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        while (matcher.find() && tokens.size() < maxTokens) {
            out.append(text, cursor, matcher.start());
            String sentinel = "\uE000" + tokens.size() + "\uE001";
            String matched = matcher.group();
            if (matched.startsWith("[item")) {
                String slot = matcher.group(1);
                tokens.add(new Item(slot == null ? "" : slot, matched, sentinel));
            } else if (matcher.group(4) != null) {
                tokens.add(new Showcase(matcher.group(4), matched, sentinel));
            } else {
                String atlas = matcher.group(2);
                String name = matcher.group(3);
                tokens.add(new Sprite(atlas == null ? "" : atlas, name, matched, sentinel));
            }
            out.append(sentinel);
            cursor = matcher.end();
        }
        out.append(text, cursor, text.length());
        return new Parsed(out.toString(), tokens);
    }
}
