package dev.yanianz.atrium.module.customtext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Turns one YAML document into a {@link TextDefinition}. Pure: no Bukkit, no file access, so the rules are
 * covered by unit tests.
 *
 * <p>{@code pages} accepts a list of pages (each a list of lines), a plain list of lines (one page) or a single
 * line. Mixing pages and lines is rejected instead of silently guessing.
 */
final class TextParser {
    private static final int MAX_COOLDOWN_SECONDS = 86_400;

    private TextParser() {}

    record Parsed(TextDefinition definition, List<String> warnings) {
        Parsed {
            warnings = List.copyOf(warnings);
        }
    }

    static Parsed parse(String name, ConfigurationNode root) throws TextSyntaxException {
        if (root == null || root.virtual()) {
            throw new TextSyntaxException("the file is empty");
        }
        List<String> warnings = new ArrayList<>();

        String command = TextName.normalize(raw(root, "command"));
        if (!command.isEmpty() && !TextName.isValid(command)) {
            warnings.add("command '" + command + "' is not a valid command name (" + TextName.RULE
                    + "); no alias registered");
            command = "";
        }
        String permission = raw(root, "permission");
        if (permission.chars().anyMatch(Character::isWhitespace)) {
            throw new TextSyntaxException("permission must not contain spaces");
        }

        boolean join = readBoolean(root.node("join"), false, "join");
        boolean firstJoinOnly;
        ConfigurationNode firstJoinNode = root.node("first-join-only");
        if (firstJoinNode.virtual()) {
            firstJoinNode = root.node("firstJoinOnly");
        }
        firstJoinOnly = readBoolean(firstJoinNode, false, "first-join-only");

        int cooldown = readCooldown(root.node("cooldown"));
        List<List<String>> pages = readPages(root.node("pages"));
        return new Parsed(new TextDefinition(name, command, permission, join, firstJoinOnly, cooldown, pages),
                warnings);
    }

    private static String raw(ConfigurationNode root, String key) {
        ConfigurationNode node = root.node(key);
        if (node.virtual() || node.isList() || node.isMap()) {
            return "";
        }
        return node.getString("").trim();
    }

    private static boolean readBoolean(ConfigurationNode node, boolean fallback, String name)
            throws TextSyntaxException {
        if (node.virtual()) {
            return fallback;
        }
        if (node.isList() || node.isMap()) {
            throw new TextSyntaxException(name + " must be true or false");
        }
        String value = node.getString("").trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off", "" -> false;
            default -> throw new TextSyntaxException(name + " must be true or false, got '" + value + "'");
        };
    }

    private static int readCooldown(ConfigurationNode node) throws TextSyntaxException {
        if (node.virtual() || node.empty()) {
            return 0;
        }
        if (node.isList() || node.isMap()) {
            throw new TextSyntaxException("cooldown must be a whole number of seconds");
        }
        String value = node.getString("").trim();
        int seconds;
        try {
            seconds = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new TextSyntaxException("cooldown must be a whole number of seconds, got '" + value + "'");
        }
        if (seconds < 0 || seconds > MAX_COOLDOWN_SECONDS) {
            throw new TextSyntaxException("cooldown must be between 0 and " + MAX_COOLDOWN_SECONDS + " seconds");
        }
        return seconds;
    }

    private static List<List<String>> readPages(ConfigurationNode node) throws TextSyntaxException {
        if (node.virtual() || node.empty()) {
            throw new TextSyntaxException("pages is missing");
        }
        if (!node.isList()) {
            if (node.isMap()) {
                throw new TextSyntaxException("pages must be a list of lines or a list of pages");
            }
            return List.of(List.of(node.getString("")));
        }
        List<? extends ConfigurationNode> children = node.childrenList();
        if (children.isEmpty()) {
            throw new TextSyntaxException("pages is empty");
        }
        boolean anyList = children.stream().anyMatch(ConfigurationNode::isList);
        boolean anyScalar = children.stream().anyMatch(child -> !child.isList());
        if (anyList && anyScalar) {
            throw new TextSyntaxException("pages mixes single lines and page lists; use one list of lines for "
                    + "a single page, or a list of lists for several pages");
        }
        if (!anyList) {
            List<String> lines = new ArrayList<>(children.size());
            for (ConfigurationNode child : children) {
                if (child.isMap()) {
                    throw new TextSyntaxException("pages must contain text lines, not maps");
                }
                lines.add(child.getString(""));
            }
            return List.of(List.copyOf(lines));
        }
        List<List<String>> pages = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            pages.add(readPage(children.get(i), i + 1));
        }
        return List.copyOf(pages);
    }

    private static List<String> readPage(ConfigurationNode page, int number) throws TextSyntaxException {
        if (!page.isList()) {
            throw new TextSyntaxException("page " + number + " must be a list of lines");
        }
        List<String> lines = new ArrayList<>();
        for (ConfigurationNode line : page.childrenList()) {
            if (line.isList() || line.isMap()) {
                throw new TextSyntaxException("page " + number + " must contain text lines only");
            }
            lines.add(line.getString(""));
        }
        if (lines.isEmpty()) {
            throw new TextSyntaxException("page " + number + " has no lines");
        }
        return List.copyOf(lines);
    }
}
