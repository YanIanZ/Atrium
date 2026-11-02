package dev.yanianz.atrium.core.docs;

import dev.yanianz.atrium.core.menu.Paginator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The model behind {@code /atrium help}: the commands one sender may use, grouped by module and split into
 * chat pages. Pure (the caller filters by permission), so it is unit-tested.
 */
public final class HelpIndex {
    /** One usable command. {@code usages} only contains paths the sender may run. */
    public record Command(String module, String name, String description, List<String> usages) {
        public Command {
            usages = List.copyOf(usages);
        }
    }

    /** A chat line: either a module heading or a command. */
    public sealed interface Line permits Heading, Entry {}

    public record Heading(String module) implements Line {}

    public record Entry(Command command) implements Line {}

    /** A rendered page: lines plus 0-based page number and page count. */
    public record Page(List<Line> lines, int page, int pages) {}

    private final List<Command> commands;

    public HelpIndex(List<Command> commands) {
        List<Command> sorted = new ArrayList<>(commands);
        sorted.sort(Comparator.comparing((Command c) -> c.module().equals("core") ? "" : c.module())
                .thenComparing(Command::name));
        this.commands = List.copyOf(sorted);
    }

    /** Commands sorted by module ("core" first), then name. */
    public List<Command> commands() {
        return commands;
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    /** Commands grouped by module, in display order. */
    public Map<String, List<Command>> byModule() {
        Map<String, List<Command>> map = new LinkedHashMap<>();
        for (Command command : commands) {
            map.computeIfAbsent(command.module(), k -> new ArrayList<>()).add(command);
        }
        return map;
    }

    /** Commands of one module (case-insensitive), empty if unknown or nothing usable. */
    public List<Command> module(String module) {
        String wanted = module.toLowerCase(Locale.ROOT);
        return commands.stream().filter(c -> c.module().toLowerCase(Locale.ROOT).equals(wanted)).toList();
    }

    /**
     * One page of {@code perPage} commands. A module heading is inserted before the first command of every
     * module on the page (also when a module continues from the previous page).
     */
    public Page page(int page, int perPage) {
        Paginator paginator = new Paginator(commands.size(), perPage);
        int current = paginator.clamp(page);
        List<Line> lines = new ArrayList<>();
        String module = null;
        for (Command command : paginator.slice(commands, current)) {
            if (!command.module().equals(module)) {
                module = command.module();
                lines.add(new Heading(module));
            }
            lines.add(new Entry(command));
        }
        return new Page(lines, current, paginator.pageCount());
    }

    /**
     * Parses the {@code [page|module]} argument: a positive number is a 1-based page (returned 0-based),
     * anything else a module name. Returns -1 for a module name.
     */
    public static int parsePage(String argument) {
        if (argument.isEmpty() || argument.length() > 6) {
            return -1;
        }
        for (int i = 0; i < argument.length(); i++) {
            if (!Character.isDigit(argument.charAt(i))) {
                return -1;
            }
        }
        return Math.max(0, Integer.parseInt(argument) - 1);
    }

    /**
     * What to paste into the chat box for a usage: the literal part up to the first argument plus a space,
     * e.g. {@code /home set <name>} becomes {@code "/home set "}.
     */
    public static String pasteText(String usage) {
        int argument = usage.indexOf(" <");
        int more = usage.indexOf(" ...");
        int cut = argument < 0 ? more : more < 0 ? argument : Math.min(argument, more);
        return (cut < 0 ? usage : usage.substring(0, cut)) + " ";
    }
}
