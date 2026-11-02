package dev.yanianz.atrium.module.customtext;

import dev.yanianz.atrium.core.config.ConfigService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.UnaryOperator;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Blocking YAML write for the in-game editor. The replacement is atomic: the new document is written to
 * a temp file in the same directory and moved over the original, so a crash or a failed serialization
 * never leaves a half-written text file.
 */
final class TextYaml {
    private TextYaml() {}

    /**
     * Reads the pages, applies one edit and writes the result. The read happens here, on the I/O thread,
     * so the edit builds on the current file and not on a snapshot that another edit already replaced.
     */
    static List<List<String>> editPages(Path file, UnaryOperator<List<List<String>>> edit) throws IOException {
        List<List<String>> current = readPages(file);
        List<List<String>> next = edit.apply(current);
        if (next == null) {
            throw new IllegalArgumentException("page-out-of-range");
        }
        writePages(file, next);
        return next;
    }

    /** The pages currently stored in the file, read without consuming directive lines. */
    static List<List<String>> readPages(Path file) throws IOException {
        try {
            ConfigurationNode root = ConfigService.loader(file).load();
            return new java.util.ArrayList<>(TextParser.readPages(root.node("pages")).stream()
                    .map(java.util.ArrayList::new).toList());
        } catch (TextSyntaxException e) {
            throw new IOException("cannot read the pages of " + file + ": " + e.getMessage(), e);
        } catch (org.spongepowered.configurate.ConfigurateException e) {
            throw new IOException("cannot read " + file, e);
        } catch (RuntimeException e) {
            throw new IOException("cannot read " + file, e);
        }
    }

    static void writePages(Path file, List<List<String>> pages) throws IOException {
        Path name = file.getFileName();
        Path temp = file.resolveSibling("." + name + ".tmp");
        try {
            CommentedConfigurationNode root = ConfigService.loader(file).load();
            root.removeChild("pages");
            CommentedConfigurationNode pagesNode = root.node("pages");
            if (pages.size() == 1) {
                for (String line : pages.getFirst()) {
                    pagesNode.appendListNode().set(line);
                }
            } else {
                for (List<String> page : pages) {
                    CommentedConfigurationNode pageNode = pagesNode.appendListNode();
                    for (String line : page) {
                        pageNode.appendListNode().set(line);
                    }
                }
            }
            YamlConfigurationLoader.builder().path(temp).build().save(root);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot write " + file, e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
