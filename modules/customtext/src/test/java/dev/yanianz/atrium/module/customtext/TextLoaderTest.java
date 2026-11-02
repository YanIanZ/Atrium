package dev.yanianz.atrium.module.customtext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLoaderTest {
    @TempDir Path folder;

    private void write(String name, String content) throws Exception {
        Files.writeString(folder.resolve(name), content);
    }

    @Test void oneBrokenFileDoesNotStopTheOthers() throws Exception {
        write("rules.yml", """
                command: rules
                pages:
                  - - "<gold>Rules"
                """);
        write("broken.yml", "pages: [\n");
        write("Bad Name.yml", """
                pages:
                  - "line"
                """);
        write("nopages.yml", "command: nopages\n");

        TextLoader.Result result = TextLoader.load(folder);

        assertEquals(List.of("rules"), List.copyOf(result.texts().keySet()));
        assertEquals("rules", result.aliases().get("rules"));
        assertEquals(3, result.failedFiles());
        assertTrue(result.errors().stream().anyMatch(line -> line.startsWith("broken.yml:")));
        assertTrue(result.errors().stream().anyMatch(line -> line.startsWith("Bad Name.yml:")));
        assertTrue(result.errors().stream().anyMatch(line -> line.startsWith("nopages.yml:")));
    }

    @Test void duplicateAliasKeepsTheFirstFileAndWarns() throws Exception {
        write("first.yml", """
                command: same
                pages:
                  - "one"
                """);
        write("second.yml", """
                command: same
                pages:
                  - "two"
                """);

        TextLoader.Result result = TextLoader.load(folder);

        assertEquals(2, result.texts().size());
        assertEquals("first", result.aliases().get("same"));
        assertEquals("", result.texts().get("second").command(), "an unregistered alias is not advertised");
        assertTrue(result.warnings().stream().anyMatch(line -> line.startsWith("second.yml:")
                && line.contains("already used")));
    }

    @Test void reservedAliasIsNotRegistered() throws Exception {
        write("main.yml", """
                command: ctext
                pages:
                  - "one"
                """);

        TextLoader.Result result = TextLoader.load(folder);

        assertFalse(result.aliases().containsKey("ctext"));
        assertEquals("", result.texts().get("main").command());
        assertTrue(result.warnings().stream().anyMatch(line -> line.contains("built-in")));
    }

    @Test void createsAMissingFolder() throws Exception {
        Path missing = folder.resolve("missing");

        TextLoader.Result result = TextLoader.load(missing);

        assertTrue(Files.isDirectory(missing));
        assertTrue(result.texts().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test void ignoresNonYmlFiles() throws Exception {
        write("notes.txt", "not a text");

        TextLoader.Result result = TextLoader.load(folder);

        assertTrue(result.texts().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test void sortedByFileName() throws Exception {
        write("zulu.yml", "pages:\n  - \"z\"\n");
        write("alpha.yml", "pages:\n  - \"a\"\n");

        TextLoader.Result result = TextLoader.load(folder);

        assertEquals(List.of("alpha", "zulu"), List.copyOf(result.texts().keySet()));
    }

    @Test void emptyResultHelper() {
        TextLoader.Result result = TextLoader.Result.empty();
        assertTrue(result.texts().isEmpty());
        assertEquals(0, result.failedFiles());
    }
}
