package dev.yanianz.atrium.module.customtext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Turns one YAML document into a {@link TextDefinition}. Pure: no Bukkit, no file access, so the rules are
 * covered by unit tests.
 *
 * <p>{@code pages} accepts a list of pages (each a list of lines), a plain list of lines (one page) or a single
 * line. Mixing pages and lines is rejected instead of silently guessing.
 *
 * <p>Directives are read from the first line of the first page and that line is consumed, like the equivalent
 * command in other server suites:
 * <ul>
 *   <li>{@code <AutoPage>} / {@code <NoAutoPage>}: split every page into chunks of at most
 *       {@code autoPageLines} lines so a long text becomes clickable pages.</li>
 *   <li>{@code <Hidden>} / {@code <NoHidden>}: keep the text out of {@code /ctext list} and the join texts.</li>
 *   <li>{@code <AutoAlias>} / {@code <NoAutoAlias>}: register the file name as a command alias unless an
 *       explicit {@code command} key is set.</li>
 *   <li>{@code <ReqPermission>node&lt;/ReqPermission&gt;} or {@code <ReqPermission:node>}: an extra permission
 *       the viewer needs; several are allowed and all must be held.</li>
 * </ul>
 * An unknown directive is left in the line, so it is either a real MiniMessage tag or a visible typo.
 */
final class TextParser {
    private static final int MAX_COOLDOWN_SECONDS = 86_400;
    static final int DEFAULT_AUTO_PAGE_LINES = 10;

    private static final Pattern REQ_PERMISSION_TAG = Pattern.compile("(?i)<ReqPermission>(.*?)</ReqPermission>");
    private static final Pattern REQ_PERMISSION_INLINE = Pattern.compile("(?i)<ReqPermission:([^>]*)>");
    private static final Pattern DIRECTIVE = Pattern.compile("(?i)<(AutoPage|NoAutoPage|Hidden|NoHidden|"
            + "AutoAlias|NoAutoAlias)>");

    private TextParser() {}

    record Parsed(TextDefinition definition, List<String> warnings) {
        Parsed {
            warnings = List.copyOf(warnings);
        }
    }

    /** Directives found on the first line. Booleans are null when the line does not mention them. */
    record Directives(Boolean autoPage, Boolean hidden, Boolean autoAlias, List<String> permissions) {
        static final Directives NONE = new Directives(null, null, null, List.of());
    }

    static Parsed parse(String name, ConfigurationNode root) throws TextSyntaxException {
        return parse(name, root, DEFAULT_AUTO_PAGE_LINES);
    }

    static Parsed parse(String name, ConfigurationNode root, int autoPageLines) throws TextSyntaxException {
        if (root == null || root.virtual()) {
            throw new TextSyntaxException("the file is empty");
        }
        List<String> warnings = new ArrayList<>();

        List<List<String>> pages = new ArrayList<>();
        for (List<String> page : readPages(root.node("pages"))) {
            pages.add(new ArrayList<>(page));
        }
        Directives directives = readDirectives(pages);
        if (directives != Directives.NONE) {
            pages.getFirst().removeFirst();
            if (pages.getFirst().isEmpty()) {
                if (pages.size() == 1) {
                    throw new TextSyntaxException("page 1 only contains directives; add at least one line");
                }
                pages.removeFirst();
            }
        }

        String command = TextName.normalize(raw(root, "command"));
        if (command.isEmpty() && Boolean.TRUE.equals(directives.autoAlias())) {
            command = name;
        }
        if (!command.isEmpty() && !TextName.isValid(command)) {
            warnings.add("command '" + command + "' is not a valid command name (" + TextName.RULE
                    + "); no alias registered");
            command = "";
        }
        String permission = raw(root, "permission");
        if (permission.chars().anyMatch(Character::isWhitespace)) {
            throw new TextSyntaxException("permission must not contain spaces");
        }
        List<String> permissions = new ArrayList<>();
        if (!permission.isEmpty()) {
            permissions.add(permission);
        }
        for (String extra : directives.permissions()) {
            if (!extra.isEmpty() && !permissions.contains(extra)) {
                permissions.add(extra);
            }
        }

        boolean join = readBoolean(root.node("join"), false, "join");
        ConfigurationNode firstJoinNode = root.node("first-join-only");
        if (firstJoinNode.virtual()) {
            firstJoinNode = root.node("firstJoinOnly");
        }
        boolean firstJoinOnly = readBoolean(firstJoinNode, false, "first-join-only");
        boolean hidden = Boolean.TRUE.equals(directives.hidden());

        int cooldown = readCooldown(root.node("cooldown"));
        if (Boolean.TRUE.equals(directives.autoPage())) {
            pages = autoPage(pages, Math.max(1, Math.min(200, autoPageLines)));
        }
        return new Parsed(new TextDefinition(name, command, permissions, join, firstJoinOnly, cooldown, hidden,
                pages), warnings);
    }

    // ---- directives ----

    private static Directives readDirectives(List<List<String>> pages) throws TextSyntaxException {
        if (pages.isEmpty() || pages.getFirst().isEmpty()) {
            return Directives.NONE;
        }
        String first = pages.getFirst().getFirst();
        Matcher directives = DIRECTIVE.matcher(first);
        List<String> permissions = new ArrayList<>();
        Matcher tagged = REQ_PERMISSION_TAG.matcher(first);
        while (tagged.find()) {
            addPermission(permissions, tagged.group(1));
        }
        Matcher inline = REQ_PERMISSION_INLINE.matcher(first);
        while (inline.find()) {
            addPermission(permissions, inline.group(1));
        }
        Boolean autoPage = null;
        Boolean hidden = null;
        Boolean autoAlias = null;
        while (directives.find()) {
            String directive = directives.group(1).toLowerCase(Locale.ROOT);
            switch (directive) {
                case "autopage" -> autoPage = true;
                case "noautopage" -> autoPage = false;
                case "hidden" -> hidden = true;
                case "nohidden" -> hidden = false;
                case "autoalias" -> autoAlias = true;
                case "noautoalias" -> autoAlias = false;
                default -> {
                    // Unreachable: the pattern only lists the six known directives.
                }
            }
        }
        if (autoPage == null && hidden == null && autoAlias == null && permissions.isEmpty()) {
            return Directives.NONE;
        }
        return new Directives(autoPage, hidden, autoAlias, List.copyOf(permissions));
    }

    private static void addPermission(List<String> permissions, String raw) throws TextSyntaxException {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new TextSyntaxException("<ReqPermission> needs a permission node");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new TextSyntaxException("<ReqPermission> node must not contain spaces");
        }
        permissions.add(value);
    }

    /** Splits every page into chunks so a long text becomes pages the reader can click through. */
    static List<List<String>> autoPage(List<List<String>> pages, int linesPerPage) {
        List<List<String>> out = new ArrayList<>();
        for (List<String> page : pages) {
            for (int start = 0; start < page.size(); start += linesPerPage) {
                out.add(List.copyOf(page.subList(start, Math.min(page.size(), start + linesPerPage))));
            }
        }
        if (out.isEmpty()) {
            out.add(List.of());
        }
        return out;
    }

    // ---- keys ----

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

    /** Reads the pages exactly as stored, without consuming directives (used by the in-game editor). */
    static List<List<String>> readPages(ConfigurationNode node) throws TextSyntaxException {
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
