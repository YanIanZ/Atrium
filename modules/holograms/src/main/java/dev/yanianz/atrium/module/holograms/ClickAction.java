package dev.yanianz.atrium.module.holograms;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A click action, written as {@code [left|right|any] [type] value}:
 * {@code right [console] give {player} diamond 1}, {@code [message] <green>Hi <player>}, {@code left [prev]},
 * {@code [page] 2}. Without a click word the action runs for both buttons.
 */
record ClickAction(Click click, Type type, String value) {
    /** Real player names and Bedrock (Floodgate) prefixed names; nothing that could break out of a command. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.]{1,32}");

    enum Click {
        LEFT, RIGHT, ANY;

        boolean matches(Click actual) {
            return this == ANY || this == actual;
        }
    }

    enum Type {
        CONSOLE(true), PLAYER(true), MESSAGE(true), NEXT(false), PREV(false), PAGE(true);

        final boolean needsValue;

        Type(boolean needsValue) {
            this.needsValue = needsValue;
        }

        String tag() {
            return "[" + name().toLowerCase(Locale.ROOT) + "]";
        }
    }

    ClickAction {
        value = value == null ? "" : value.strip();
        if (type.needsValue && value.isEmpty()) {
            throw new IllegalArgumentException(type.tag() + " needs a value");
        }
        if (!type.needsValue && !value.isEmpty()) {
            throw new IllegalArgumentException(type.tag() + " takes no value");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line breaks are not allowed");
        }
        if ((type == Type.CONSOLE || type == Type.PLAYER) && value.startsWith("/")) {
            value = value.substring(1);
        }
        if (type == Type.PAGE) {
            int page;
            try {
                page = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[page] needs a page number");
            }
            if (page < 1) {
                throw new IllegalArgumentException("[page] needs a page number");
            }
        }
    }

    static ClickAction parse(String text) {
        String rest = text.strip();
        Click click = Click.ANY;
        int space = rest.indexOf(' ');
        String first = (space < 0 ? rest : rest.substring(0, space)).toLowerCase(Locale.ROOT);
        for (Click c : Click.values()) {
            if (first.equals(c.name().toLowerCase(Locale.ROOT))) {
                click = c;
                rest = space < 0 ? "" : rest.substring(space + 1).strip();
                break;
            }
        }
        if (!rest.startsWith("[")) {
            throw new IllegalArgumentException("expected [console], [player], [message], [next], [prev] or [page]");
        }
        int close = rest.indexOf(']');
        if (close < 0) {
            throw new IllegalArgumentException("missing ]");
        }
        String typeName = rest.substring(1, close).strip().toUpperCase(Locale.ROOT);
        Type type;
        try {
            type = Type.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown action type [" + typeName.toLowerCase(Locale.ROOT) + "]");
        }
        return new ClickAction(click, type, rest.substring(close + 1));
    }

    String encode() {
        String head = click.name().toLowerCase(Locale.ROOT) + " " + type.tag();
        return value.isEmpty() ? head : head + " " + value;
    }

    /** 1-based page number of a {@code [page]} action. */
    int page() {
        return Integer.parseInt(value);
    }

    /**
     * Fills {@code {player}} and {@code {uuid}} in a command. Empty when the name is not a safe player name, so a
     * crafted name can never inject extra command text.
     */
    static Optional<String> fill(String command, String playerName, UUID uuid) {
        if (command.contains("{player}") && !SAFE_NAME.matcher(playerName).matches()) {
            return Optional.empty();
        }
        return Optional.of(command.replace("{player}", playerName).replace("{uuid}", uuid.toString()));
    }
}
