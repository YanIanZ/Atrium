package dev.yanianz.atrium.module.inspect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Formats the {@code /util} diagnostic dumps. Pure: the command only gathers the rows and writes the text
 * on the I/O executor, which keeps a region thread free of file access and makes the format unit testable.
 */
final class UtilDumps {
    record CommandRow(String name, List<String> aliases, String owner, String permission, String usage,
            String description) {}

    record PermissionRow(String node, String defaultLevel, String description) {}

    record MaterialRow(String name) {}

    private UtilDumps() {}

    static String commands(String generatedAt, List<CommandRow> input) {
        List<CommandRow> rows = new ArrayList<>(input);
        rows.sort(Comparator.comparing(CommandRow::name, String.CASE_INSENSITIVE_ORDER));
        StringBuilder out = new StringBuilder(header(generatedAt, "commands", rows.size()));
        for (CommandRow row : rows) {
            out.append("command: ").append(oneLine(row.name())).append('\n')
                    .append("  aliases: ").append(oneLine(String.join(", ", row.aliases()))).append('\n')
                    .append("  owner: ").append(oneLine(row.owner())).append('\n')
                    .append("  permission: ").append(oneLine(row.permission())).append('\n')
                    .append("  usage: ").append(oneLine(row.usage())).append('\n')
                    .append("  description: ").append(oneLine(row.description())).append('\n');
        }
        return out.toString();
    }

    static String permissions(String generatedAt, List<PermissionRow> input) {
        List<PermissionRow> rows = new ArrayList<>(input);
        rows.sort(Comparator.comparing(PermissionRow::node, String.CASE_INSENSITIVE_ORDER));
        StringBuilder out = new StringBuilder(header(generatedAt, "permissions", rows.size()));
        for (PermissionRow row : rows) {
            out.append("permission: ").append(oneLine(row.node())).append('\n')
                    .append("  default: ").append(oneLine(row.defaultLevel())).append('\n')
                    .append("  description: ").append(oneLine(row.description())).append('\n');
        }
        return out.toString();
    }

    static String materials(String generatedAt, List<MaterialRow> input) {
        List<MaterialRow> rows = new ArrayList<>(input);
        rows.sort(Comparator.comparing(MaterialRow::name, String.CASE_INSENSITIVE_ORDER));
        StringBuilder out = new StringBuilder(header(generatedAt, "materials", rows.size()));
        out.append("name\n");
        for (MaterialRow row : rows) {
            out.append(oneLine(row.name())).append('\n');
        }
        return out.toString();
    }

    static String info(String generatedAt, Map<String, String> values) {
        StringBuilder out = new StringBuilder(header(generatedAt, "server info", values.size()));
        values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> out.append(oneLine(entry.getKey())).append(": ")
                        .append(oneLine(entry.getValue())).append('\n'));
        return out.toString();
    }

    private static String header(String generatedAt, String kind, int count) {
        return "# Atrium " + kind + " dump\n"
                + "# generated: " + oneLine(generatedAt) + "\n"
                + "# rows: " + count + "\n";
    }

    /** Keeps one record per line, so a chat/console value cannot break the dump format. */
    private static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
    }
}
