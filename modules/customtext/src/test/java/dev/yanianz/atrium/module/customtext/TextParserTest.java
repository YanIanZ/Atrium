package dev.yanianz.atrium.module.customtext;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextParserTest {
    static ConfigurationNode yaml(String text) throws Exception {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    @Test void parsesEveryField() throws Exception {
        TextParser.Parsed parsed = TextParser.parse("rules", yaml("""
                command: Rules
                permission: atrium.rules
                cooldown: 30
                join: true
                first-join-only: true
                pages:
                  - - "<gold>Page one"
                    - "<gray>line two"
                  - - "<gold>Page two"
                """));
        TextDefinition text = parsed.definition();
        assertEquals("rules", text.name());
        assertEquals("rules", text.command());
        assertEquals("atrium.rules", text.permission());
        assertEquals(30, text.cooldownSeconds());
        assertTrue(text.join());
        assertTrue(text.firstJoinOnly());
        assertEquals(2, text.pageCount());
        assertEquals(List.of("<gold>Page one", "<gray>line two"), text.pages().get(0));
        assertEquals(List.of("<gold>Page two"), text.pages().get(1));
        assertTrue(parsed.warnings().isEmpty());
    }

    @Test void plainLineListIsOnePage() throws Exception {
        TextDefinition text = TextParser.parse("hello", yaml("""
                pages:
                  - "first"
                  - "second"
                """)).definition();
        assertEquals(1, text.pageCount());
        assertEquals(List.of("first", "second"), text.pages().get(0));
        assertEquals("", text.command());
        assertEquals(0, text.cooldownSeconds());
    }

    @Test void singleLineIsOnePage() throws Exception {
        TextDefinition text = TextParser.parse("hello", yaml("pages: \"<gray>Only one line\"")).definition();
        assertEquals(List.of(List.of("<gray>Only one line")), text.pages());
    }

    @Test void missingPagesIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("command: rules\n")));
    }

    @Test void emptyPageIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                pages:
                  - []
                """)));
    }

    @Test void emptyPagesListIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("pages: []\n")));
    }

    @Test void mixingLinesAndPagesIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                pages:
                  - "line"
                  - - "page line"
                """)));
    }

    @Test void nestedListInsideAPageIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                pages:
                  - - - "too deep"
                """)));
    }

    @Test void invalidCooldownIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                cooldown: 10s
                pages:
                  - "line"
                """)));
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                cooldown: -5
                pages:
                  - "line"
                """)));
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                cooldown: 999999
                pages:
                  - "line"
                """)));
    }

    @Test void invalidJoinValueIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                join: sometimes
                pages:
                  - "line"
                """)));
    }

    @Test void permissionWithSpacesIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("rules", yaml("""
                permission: "atrium rules"
                pages:
                  - "line"
                """)));
    }

    @Test void invalidAliasBecomesAWarning() throws Exception {
        TextParser.Parsed parsed = TextParser.parse("rules", yaml("""
                command: "Bad Command"
                pages:
                  - "line"
                """));
        assertEquals("", parsed.definition().command());
        assertEquals(1, parsed.warnings().size());
        assertTrue(parsed.warnings().get(0).contains("command"));
    }

    @Test void acceptsCamelCaseFirstJoinOnly() throws Exception {
        assertTrue(TextParser.parse("rules", yaml("""
                firstJoinOnly: true
                pages:
                  - "line"
                """)).definition().firstJoinOnly());
    }
}
