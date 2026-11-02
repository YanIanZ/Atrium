package dev.yanianz.atrium.module.customtext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The editor must leave a file the loader can read and must not leave temp files behind. */
class TextYamlTest {
    @TempDir
    Path folder;

    @Test
    void writesMultiplePagesAndPreservesOtherKeys() throws Exception {
        Path file = folder.resolve("rules.yml");
        Files.writeString(file, """
                command: rules
                join: true
                pages:
                  - first
                  - second
                """);

        TextYaml.writePages(file, List.of(List.of("first", "second"), List.of("third")));

        TextLoader.Result result = TextLoader.load(folder);
        assertTrue(result.errors().isEmpty(), () -> result.errors().toString());
        TextDefinition text = result.texts().get("rules");
        assertEquals(2, text.pageCount());
        assertEquals(List.of("first", "second"), text.pages().getFirst());
        assertEquals(List.of("third"), text.pages().get(1));
        assertEquals("rules", text.command(), "other keys are preserved");
        assertTrue(text.join());
        assertFalse(Files.exists(folder.resolve(".rules.yml.tmp")), "the temp file is removed");
    }

    @Test
    void writesASinglePageAsAPlainLineList() throws Exception {
        Path file = folder.resolve("info.yml");
        Files.writeString(file, "pages:\n  - one\n  - two\n");

        TextYaml.writePages(file, List.of(List.of("hello", "world")));

        String yaml = Files.readString(file);
        assertTrue(yaml.contains("hello"));
        assertFalse(yaml.contains("- - hello"), "one page stays a simple list");
        TextDefinition text = TextLoader.load(folder).texts().get("info");
        assertEquals(List.of(List.of("hello", "world")), text.pages());
    }

    @Test
    void eachEditBuildsOnTheCurrentFileNotOnAStaleSnapshot() throws Exception {
        Path file = folder.resolve("rules.yml");
        Files.writeString(file, "pages:\n  - first\n");

        // Two quick edits, each reading the file inside the edit: the second must see the first one.
        TextYaml.editPages(file, pages -> TextEditModel.addLine(pages, 1, pages.getFirst().size() + 1, "second"));
        List<List<String>> pages = TextYaml.editPages(file,
                current -> TextEditModel.addLine(current, 1, current.getFirst().size() + 1, "third"));

        assertEquals(List.of(List.of("first", "second", "third")), pages);
        assertEquals(List.of(List.of("first", "second", "third")), TextYaml.readPages(file));
    }

    @Test
    void anEmptyLineIsAValidPageLine() throws Exception {
        Path file = folder.resolve("blank.yml");
        Files.writeString(file, "pages:\n  - old\n");

        TextYaml.writePages(file, List.of(List.of("")));

        TextDefinition text = TextLoader.load(folder).texts().get("blank");
        assertEquals(List.of(List.of("")), text.pages());
    }
}
