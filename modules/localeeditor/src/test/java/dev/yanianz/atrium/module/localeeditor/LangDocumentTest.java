package dev.yanianz.atrium.module.localeeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.localeeditor.LangDocument.InvalidDocumentException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rewriting a language file must change exactly one value and never lose a sibling. */
class LangDocumentTest {
    private static final String YAML = """
            # Inventory messages
            no-homes: "<prefix><error>You have no homes."
            reason:
              death: "<error>death"
              quit: "<muted>quit"
            list-hover:
              - "<primary>Line one"
              - "<secondary>Line two"
            """;

    @Test
    void replacesOneTopLevelValueAndKeepsEverythingElse() throws Exception {
        LangDocument.Edit edit = LangDocument.set(YAML, "no-homes", "<prefix><success>Nothing here.");
        assertFalse(edit.created());
        Map<String, String> values = LangDocument.flatten(edit.text());
        assertEquals("<prefix><success>Nothing here.", values.get("no-homes"));
        assertEquals("<error>death", values.get("reason.death"));
        assertEquals("<muted>quit", values.get("reason.quit"));
        assertEquals("<primary>Line one<newline><secondary>Line two", values.get("list-hover"));
    }

    @Test
    void keepsTheDocumentOrder() throws Exception {
        String text = LangDocument.set(YAML, "reason.quit", "<muted>bye").text();
        assertTrue(text.indexOf("no-homes") < text.indexOf("reason:"));
        assertTrue(text.indexOf("reason:") < text.indexOf("list-hover"));
    }

    @Test
    void replacesNestedValues() throws Exception {
        Map<String, String> values = LangDocument.flatten(LangDocument.set(YAML, "reason.death", "<error>gone").text());
        assertEquals("<error>gone", values.get("reason.death"));
        assertEquals("<muted>quit", values.get("reason.quit"));
    }

    @Test
    void createsAMissingKey() throws Exception {
        LangDocument.Edit edit = LangDocument.set(YAML, "custom.hello", "<prefix>Hi");
        assertTrue(edit.created());
        Map<String, String> values = LangDocument.flatten(edit.text());
        assertEquals("<prefix>Hi", values.get("custom.hello"));
        assertEquals("<error>death", values.get("reason.death"));
    }

    @Test
    void refusesToOverwriteASection() {
        InvalidDocumentException error = assertThrows(InvalidDocumentException.class,
                () -> LangDocument.set(YAML, "reason", "oops"));
        assertEquals(InvalidDocumentException.Reason.SECTION, error.reason());
    }

    @Test
    void refusesBrokenYaml() {
        InvalidDocumentException error = assertThrows(InvalidDocumentException.class,
                () -> LangDocument.set("broken: [unclosed\n", "broken", "value"));
        assertEquals(InvalidDocumentException.Reason.PARSE, error.reason());
    }

    @Test
    void quotesAwkwardValuesSoTheyReadBackIdentically() throws Exception {
        String value = "<prefix><error>Colon: \"quoted\" \\ backslash & <player>";
        String text = LangDocument.set(YAML, "no-homes", value).text();
        assertEquals(value, LangDocument.get(text, "no-homes").orElseThrow());
    }

    @Test
    void createsTheFirstKeyOfAnEmptyFile() throws Exception {
        LangDocument.Edit edit = LangDocument.set("", "homes.no-homes", "<prefix><muted>None");
        assertTrue(edit.created());
        assertEquals("<prefix><muted>None", LangDocument.get(edit.text(), "homes.no-homes").orElseThrow());
    }

    @Test
    void readsCurrentValuesAndTreatsSectionsAsMissing() throws Exception {
        assertEquals("<error>death", LangDocument.get(YAML, "reason.death").orElseThrow());
        assertTrue(LangDocument.get(YAML, "reason").isEmpty());
        assertTrue(LangDocument.get(YAML, "nope").isEmpty());
    }
}
