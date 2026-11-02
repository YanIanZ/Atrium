package dev.yanianz.atrium.module.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecipeLoaderTest {
    @TempDir Path folder;

    @Test
    void loadsGoodFilesAndSkipsBrokenOnes() throws Exception {
        Files.writeString(folder.resolve("good.yml"), """
                type: shapeless
                ingredients: [STONE]
                result: {material: STONE_BUTTON}
                """);
        Files.writeString(folder.resolve("broken.yml"), """
                type: shaped
                pattern: ["DD", "D"]
                key: {D: DIAMOND}
                result: {material: DIAMOND}
                """);
        Files.writeString(folder.resolve("notes.txt"), "ignored");

        RecipeLoader.Result result = RecipeLoader.load(folder);
        assertEquals(1, result.recipes().size());
        assertEquals(RecipeSpec.Kind.SHAPELESS, result.recipes().get("good").kind());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().startsWith("broken.yml:"));
    }

    @Test
    void emptyFolderLoadsNothingWithoutErrors() throws Exception {
        RecipeLoader.Result result = RecipeLoader.load(folder);
        assertTrue(result.recipes().isEmpty());
        assertTrue(result.errors().isEmpty());
        assertEquals(List.of(), result.errors());
    }

    @Test
    void invalidFileNameIsReportedAndSkipped() throws Exception {
        Files.writeString(folder.resolve("Bad Name.yml"), """
                type: shapeless
                ingredients: [STONE]
                result: {material: STONE}
                """);
        RecipeLoader.Result result = RecipeLoader.load(folder);
        assertTrue(result.recipes().isEmpty());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().getFirst().contains("file name"));
    }
}
