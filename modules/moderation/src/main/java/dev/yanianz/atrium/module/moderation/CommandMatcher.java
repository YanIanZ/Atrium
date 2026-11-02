package dev.yanianz.atrium.module.moderation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Matches a typed command line against configured command lists, word by word. An entry matches when its
 * words are the first words of the command, so {@code msg} matches {@code /msg Bob hi} and
 * {@code /minecraft:msg Bob hi}, but not {@code /msgtoggle}; {@code mail send} matches {@code /mail send Bob}
 * but not {@code /mail read}. {@code *} matches every command.
 */
final class CommandMatcher {
    private CommandMatcher() {}

    /** Lower-case words of a command line without the leading slash and without a {@code namespace:} prefix. */
    static List<String> words(String commandLine) {
        String text = commandLine.strip();
        while (text.startsWith("/")) {
            text = text.substring(1);
        }
        List<String> words = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        if (!words.isEmpty()) {
            String label = words.getFirst();
            int colon = label.indexOf(':');
            if (colon >= 0 && colon < label.length() - 1) {
                words.set(0, label.substring(colon + 1));
            }
        }
        return words;
    }

    static boolean matches(String commandLine, Collection<String> entries) {
        List<String> command = words(commandLine);
        if (command.isEmpty()) {
            return false;
        }
        for (String entry : entries) {
            if (entry.strip().equals("*")) {
                return true;
            }
            List<String> wanted = words(entry);
            if (!wanted.isEmpty() && wanted.size() <= command.size() && command.subList(0, wanted.size()).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Blocked when it matches {@code blocked} and does not match {@code allowed}. */
    static boolean blocked(String commandLine, Collection<String> blocked, Collection<String> allowed) {
        return matches(commandLine, blocked) && !matches(commandLine, allowed);
    }
}
