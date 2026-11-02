package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.BodyLine;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A preview must look like the dialog but never run anything. */
class DialogPreviewTest {
    static final DialogDefinition DIALOG = new DialogDefinition("example", "<gold>Hello", "external",
            "atrium.dialog.example", true, 2,
            List.of(new BodyLine("<gray>Body", 200)),
            List.of(),
            List.of(new ButtonSpec("<green>Give", "tooltip", 150, "atrium.dialog.example.give",
                    List.of("console: give {player} diamond", "msg: <green>Done"), "thanks")),
            new ButtonSpec("<red>Cancel", "", 150, "", List.of("msg: bye"), null),
            List.of("msg: opened {player}"),
            Duration.ofMinutes(5));

    @Test void buttonsBecomeInertAndShowTheirActions() {
        DialogDefinition preview = DialogPreview.apply(DIALOG);
        assertEquals(1, preview.buttons().size());
        ButtonSpec button = preview.buttons().getFirst();
        assertEquals(List.of(), button.actions(), "a preview never runs the actions");
        assertNull(button.openDialog(), "nor opens another dialog");
        assertTrue(button.label().contains("console: give {player} diamond"), button.label());
        assertTrue(button.label().contains("msg: \\<green>Done"), "action MiniMessage stays literal");
        assertTrue(button.label().contains("opens thanks"), button.label());
        assertEquals("atrium.dialog.example.give", button.permission(), "the preview shows who may click it");
        assertNotNull(preview.exit());
        assertEquals(List.of(), preview.exit().actions());
    }

    @Test void previewNeverRunsOnOpenNorAppliesTheCooldownOrPermission() {
        DialogDefinition preview = DialogPreview.apply(DIALOG);
        assertEquals(List.of(), preview.onOpen());
        assertNull(preview.cooldown());
        assertEquals("", preview.permission());
        assertEquals("example", preview.id(), "the id is kept so logs name the right dialog");
    }

    @Test void previewKeepsTheLayoutAndAddsAMetadataHeader() {
        DialogDefinition preview = DialogPreview.apply(DIALOG);
        assertEquals("<gold>Hello", preview.title().substring(preview.title().indexOf("<gold>")));
        assertTrue(preview.title().contains("[preview]"));
        assertTrue(preview.body().size() > DIALOG.body().size());
        assertTrue(preview.body().stream().anyMatch(line -> line.text().contains("5m")), preview.body().toString());
        assertTrue(preview.body().stream().anyMatch(line -> line.text().contains("atrium.dialog.example")));
        assertEquals(DIALOG.inputs(), preview.inputs());
        assertEquals(DIALOG.columns(), preview.columns());
    }
}
