package dev.yanianz.atrium.module.messaging;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Leading {@code -s:<server1,server2>} flag of {@code /bbroadcast}. Unlike the other messaging commands the
 * flag names servers, not silence. Pure, so the parsing is unit tested.
 */
final class BroadcastFlags {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,64}");
    private static final int MAX_SERVERS = 64;

    enum Problem { NONE, BLANK, INVALID_SERVER, SERVERS_NOT_ALLOWED }

    /** @param servers empty means "every server the proxy reported" */
    record Result(String text, List<String> servers, Problem problem, String detail) {
        Result {
            servers = List.copyOf(servers);
        }

        boolean ok() {
            return problem == Problem.NONE;
        }
    }

    private BroadcastFlags() {}

    static Result parse(String raw, boolean serversAllowed) {
        String rest = raw == null ? "" : raw.strip();
        List<String> servers = new ArrayList<>();
        while (!rest.isEmpty()) {
            int split = firstWhitespace(rest);
            String token = split < 0 ? rest : rest.substring(0, split);
            String lower = token.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("-s:")) {
                break;
            }
            if (!serversAllowed) {
                return new Result("", List.of(), Problem.SERVERS_NOT_ALLOWED, token);
            }
            String list = token.substring(3);
            for (String name : list.split(",")) {
                String value = name.strip();
                if (value.isEmpty()) {
                    continue;
                }
                if (!NAME.matcher(value).matches()) {
                    return new Result("", List.of(), Problem.INVALID_SERVER, value);
                }
                if (servers.size() >= MAX_SERVERS) {
                    return new Result("", List.of(), Problem.INVALID_SERVER, value);
                }
                if (servers.stream().noneMatch(existing -> existing.equalsIgnoreCase(value))) {
                    servers.add(value);
                }
            }
            rest = split < 0 ? "" : rest.substring(split).strip();
        }
        if (rest.isBlank()) {
            return new Result("", servers, Problem.BLANK, "");
        }
        return new Result(rest, servers, Problem.NONE, "");
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
