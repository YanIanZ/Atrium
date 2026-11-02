package dev.yanianz.atrium.module.customtext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class TextDirectivesTest {
    private static ConfigurationNode yaml(String source) throws ConfigurateException {
        return YamlConfigurationLoader.builder().buildAndLoadString(source);
    }

    @Test
    void autoPageConsumesTheLineAndSplitsThePage() throws Exception {
        TextDefinition text = TextParser.parse("long", yaml("""
                pages:
                  - - "<AutoPage>"
                    - "one"
                    - "two"
                    - "three"
                """), 2).definition();
        assertEquals(2, text.pageCount());
        assertEquals(List.of("one", "two"), text.pages().get(0));
        assertEquals(List.of("three"), text.pages().get(1));
    }

    @Test
    void hiddenMarksTheTextWithoutChangingTheContent() throws Exception {
        TextDefinition text = TextParser.parse("secret", yaml("""
                pages:
                  - - "<Hidden><AutoAlias>"
                    - "only for staff"
                """)).definition();
        assertTrue(text.hidden());
        assertEquals("secret", text.command());
        assertEquals(List.of(List.of("only for staff")), text.pages());
    }

    @Test
    void anExplicitCommandWinsOverAutoAlias() throws Exception {
        TextDefinition text = TextParser.parse("rules", yaml("""
                command: rules2
                pages:
                  - - "<AutoAlias>"
                    - "x"
                """)).definition();
        assertEquals("rules2", text.command());
    }

    @Test
    void requiredPermissionsAddUp() throws Exception {
        TextDefinition text = TextParser.parse("staff", yaml("""
                permission: atrium.ctext.staff
                pages:
                  - - "<ReqPermission>atrium.ctext.admin</ReqPermission><ReqPermission:atrium.staff>"
                    - "hello"
                """)).definition();
        assertEquals(List.of("atrium.ctext.staff", "atrium.ctext.admin", "atrium.staff"), text.permissions());
        assertTrue(text.maySee(node -> true));
        assertFalse(text.maySee(node -> node.startsWith("atrium.ctext.")));
    }

    @Test
    void aRepeatedPermissionIsStoredOnce() throws Exception {
        TextDefinition text = TextParser.parse("x", yaml("""
                permission: atrium.ctext.x
                pages:
                  - - "<ReqPermission:atrium.ctext.x>"
                    - "hello"
                """)).definition();
        assertEquals(List.of("atrium.ctext.x"), text.permissions());
    }

    @Test
    void aNormalFirstLineIsKept() throws Exception {
        TextDefinition text = TextParser.parse("plain", yaml("""
                pages:
                  - - "<gray>Hello"
                    - "<AutoPage>"
                """)).definition();
        List<String> page = text.pages().getFirst();
        assertEquals(List.of("<gray>Hello", "<AutoPage>"), page);
        assertFalse(text.hidden());
        assertEquals("", text.command());
    }

    @Test
    void aPageOfDirectivesOnlyIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("empty", yaml("""
                pages:
                  - - "<Hidden>"
                """)));
    }

    @Test
    void anEmptyRequirementIsRejected() {
        assertThrows(TextSyntaxException.class, () -> TextParser.parse("x", yaml("""
                pages:
                  - - "<ReqPermission></ReqPermission>"
                    - "hello"
                """)));
    }

    @Test
    void noAutoPageDisablesTheDirectiveExplicitly() throws Exception {
        TextDefinition text = TextParser.parse("x", yaml("""
                pages:
                  - - "<NoAutoPage>"
                    - "a"
                    - "b"
                    - "c"
                """), 1).definition();
        assertEquals(1, text.pageCount());
        assertEquals(3, text.pages().getFirst().size());
    }
}
