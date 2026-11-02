package dev.yanianz.atrium.module.localeeditor;

import dev.yanianz.atrium.core.config.ConfigService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Reads and rewrites one language file as text. Editing goes through Configurate (the same loader the core uses),
 * so the result is always a document the core can parse; the rewritten text is parsed again and compared with the
 * expected flattened map before it is ever written to disk. Key order is preserved.
 *
 * <p>YAML comments are <em>not</em> preserved: the core {@code Messages} loader also re-saves these files with
 * Configurate on every reload, which drops them anyway. The editor therefore documents this instead of pretending
 * otherwise.
 */
final class LangDocument {
    /** One flattened entry, in document order. */
    record Entry(String key, String value) {}

    /** The new document, the stored value and whether the key did not exist before. */
    record Edit(String text, String value, boolean created) {}

    /** A file that cannot be edited safely. The command translates {@link Reason}. */
    static final class InvalidDocumentException extends Exception {
        private static final long serialVersionUID = 1L;

        enum Reason {
            /** The file is not valid YAML. */
            PARSE,
            /** The key currently holds a section; editing it would delete its children. */
            SECTION,
            /** The rewritten document does not contain the expected values. */
            VERIFY
        }

        private final Reason reason;

        InvalidDocumentException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        InvalidDocumentException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    private LangDocument() {}

    /** Every leaf of the document as {@code dotted.key -> text}; lists are joined with {@code <newline>}. */
    static List<Entry> entries(String yaml) throws InvalidDocumentException {
        ConfigurationNode root = parse(yaml);
        List<Entry> entries = new ArrayList<>();
        walk("", root, entries);
        return List.copyOf(entries);
    }

    static Map<String, String> flatten(String yaml) throws InvalidDocumentException {
        Map<String, String> flat = new LinkedHashMap<>();
        for (Entry entry : entries(yaml)) {
            flat.put(entry.key(), entry.value());
        }
        return flat;
    }

    static Optional<String> get(String yaml, String dottedKey) throws InvalidDocumentException {
        List<String> path = KeyPath.parse(dottedKey);
        ConfigurationNode node = node(parse(yaml), path);
        if (node.virtual() || node.isMap()) {
            return Optional.empty();
        }
        return Optional.of(valueOf(node));
    }

    /**
     * Sets one key and returns the document that should replace the file. A key that holds a section is refused:
     * overwriting it with a scalar would silently delete every child message.
     */
    static Edit set(String yaml, String dottedKey, String value) throws InvalidDocumentException {
        List<String> path = KeyPath.parse(dottedKey);
        ConfigurationNode root = parse(yaml);
        ConfigurationNode node = node(root, path);
        if (node.isMap()) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.SECTION,
                    "Key '" + dottedKey + "' holds a section");
        }
        boolean created = node.virtual();
        try {
            node.set(value);
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.PARSE,
                    "Cannot store the value for '" + dottedKey + "': " + e.getMessage(), e);
        }
        String text = render(root);
        verify(yaml, text, dottedKey, value);
        return new Edit(text, value, created);
    }

    private static void verify(String before, String after, String dottedKey, String value)
            throws InvalidDocumentException {
        Map<String, String> expected = flatten(before);
        expected.put(dottedKey, value);
        Map<String, String> actual = flatten(after);
        if (!expected.equals(actual)) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.VERIFY,
                    "The rewritten document does not match the requested edit for '" + dottedKey
                            + "': expected " + expected + " but read " + actual);
        }
    }

    private static ConfigurationNode parse(String yaml) throws InvalidDocumentException {
        String text = yaml == null ? "" : yaml;
        if (text.isBlank()) {
            return emptyRoot();
        }
        try {
            CommentedConfigurationNode root = ConfigService.read(
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            return root == null ? emptyRoot() : root;
        } catch (ConfigurateException | RuntimeException e) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.PARSE,
                    "The language file is not valid YAML: " + e.getMessage(), e);
        }
    }

    private static CommentedConfigurationNode emptyRoot() {
        return CommentedConfigurationNode.root();
    }

    private static ConfigurationNode node(ConfigurationNode root, List<String> path) {
        ConfigurationNode node = root;
        for (String segment : path) {
            node = node.node(segment);
        }
        return node;
    }

    private static String render(ConfigurationNode root) throws InvalidDocumentException {
        try {
            return YamlConfigurationLoader.builder()
                    .nodeStyle(NodeStyle.BLOCK)
                    .indent(2)
                    .defaultOptions(options -> options.shouldCopyDefaults(true))
                    .buildAndSaveString(root);
        } catch (ConfigurateException | RuntimeException e) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.PARSE,
                    "The language file cannot be written as YAML: " + e.getMessage(), e);
        }
    }

    private static void walk(String prefix, ConfigurationNode node, List<Entry> out) {
        if (node.isMap()) {
            node.childrenMap().forEach((key, child) ->
                    walk(prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, child, out));
        } else if (node.isList()) {
            List<String> lines = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                lines.add(child.getString(""));
            }
            out.add(new Entry(prefix, String.join("<newline>", lines)));
        } else if (!node.virtual() && !prefix.isEmpty()) {
            out.add(new Entry(prefix, node.getString("")));
        }
    }

    private static String valueOf(ConfigurationNode node) {
        if (!node.isList()) {
            return node.getString("");
        }
        List<String> lines = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            lines.add(child.getString(""));
        }
        return String.join("<newline>", lines);
    }
}
