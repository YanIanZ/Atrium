package dev.yanianz.atrium.module.staff;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Masks the arguments of sensitive commands before they are shown by /commandspy. The command label
 * itself stays visible; the whole line is later inserted with {@code Msg.unparsed}, so a player can
 * never inject formatting through a command they typed. Aliases and {@code namespace:} forms of a
 * sensitive command are masked too (see {@link CommandNames}).
 */
final class CommandSpyFilter {
    static final String HIDDEN = "•••";

    private CommandSpyFilter() {
    }

    static String mask(String rawMessage, Collection<String> sensitiveCommands) {
        return mask(rawMessage, sensitiveCommands, label -> List.of());
    }

    static String mask(String rawMessage, Collection<String> sensitiveCommands,
            Function<String, Collection<String>> aliases) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return "";
        }
        String body = rawMessage.strip();
        while (body.startsWith("/")) {
            body = body.substring(1).stripLeading();
        }
        if (body.isEmpty()) {
            return "/";
        }
        String label = CommandNames.label(body);
        boolean hasArguments = !body.substring(label.length()).isBlank();
        if (!CommandNames.matches(label, aliases, sensitiveCommands)) {
            return "/" + body;
        }
        String shown = label.substring(label.lastIndexOf(':') + 1);
        return hasArguments ? "/" + shown + " " + HIDDEN : "/" + shown;
    }
}
