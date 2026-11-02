package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.module.dialogs.DialogDefinition.NumberInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.OptionInput;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import static org.junit.jupiter.api.Assertions.*;

class DialogParserTest {
    static final DialogParser.Defaults DEFAULTS =
            new DialogParser.Defaults(Pattern.compile("[A-Za-z0-9_ -]{0,32}"), 32);

    @TempDir Path folder;

    static ConfigurationNode yaml(String text) throws Exception {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    static final String VALID = """
            title: "<gold>Report"
            permission: atrium.dialog.report
            body:
              - "Tell us"
              - text: "More"
                width: 300
            inputs:
              - type: text
                key: target
                label: Player
                max-length: 16
                pattern: "[A-Za-z0-9_]{3,16}"
              - type: option
                key: reason
                options:
                  - id: cheat
                    initial: true
                  - spam
              - type: bool
                key: urgent
              - type: number
                key: amount
                start: 1
                end: 64
                step: 1
            buttons:
              - label: Send
                permission: atrium.dialog.report.send
                actions:
                  - "console: report {target} {reason} {amount}"
                open: thanks
            exit:
              label: Cancel
            """;

    @Test void parsesAllElementTypes() throws Exception {
        DialogDefinition d = DialogParser.parse("report", yaml(VALID), DEFAULTS);
        assertEquals("<gold>Report", d.title());
        assertEquals("atrium.dialog.report", d.permission());
        assertEquals(2, d.body().size());
        assertEquals(300, d.body().get(1).width());
        assertEquals(4, d.inputs().size());
        TextInput text = (TextInput) d.inputs().get(0);
        assertEquals(16, text.maxLength());
        assertEquals("[A-Za-z0-9_]{3,16}", text.pattern().pattern());
        OptionInput option = (OptionInput) d.inputs().get(1);
        assertTrue(option.hasOption("cheat"));
        assertTrue(option.hasOption("spam"));
        assertFalse(option.hasOption("Spam"));
        NumberInput number = (NumberInput) d.inputs().get(3);
        assertEquals(1f, number.start());
        assertEquals(64f, number.end());
        assertEquals(1, d.buttons().size());
        assertEquals("thanks", d.buttons().get(0).openDialog());
        assertEquals("atrium.dialog.report.send", d.buttons().get(0).permission());
        assertNotNull(d.exit());
    }

    @Test void textInputUsesServerDefaults() throws Exception {
        DialogDefinition d = DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - key: name
                """), DEFAULTS);
        TextInput text = (TextInput) d.inputs().get(0);
        assertEquals(32, text.maxLength());
        assertEquals(DEFAULTS.textPattern().pattern(), text.pattern().pattern());
    }

    @Test void rejectsWrongTypesInsteadOfCrashing() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                columns: 2.5
                can-close-with-escape: maybe
                buttons:
                  - label: A
                    width: wide
                """), DEFAULTS));
        assertTrue(e.problems().stream().anyMatch(p -> p.startsWith("columns")), e.getMessage());
        assertTrue(e.problems().stream().anyMatch(p -> p.startsWith("can-close-with-escape")), e.getMessage());
        assertTrue(e.problems().stream().anyMatch(p -> p.startsWith("buttons[0].width")), e.getMessage());
    }

    @Test void rejectsUnknownKeysSoTyposCannotDropPermissions() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                permision: secret.node
                """), DEFAULTS));
        assertTrue(e.getMessage().contains("permision"));
    }

    @Test void validatesOptionIds() {
        assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - type: option
                    key: c
                    options: [ "a b" ]
                """), DEFAULTS), "spaces are not allowed in option ids");
        DialogSyntaxException duplicate = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x",
                yaml("""
                        title: T
                        inputs:
                          - type: option
                            key: c
                            options: [ red, red ]
                        """), DEFAULTS));
        assertTrue(duplicate.getMessage().contains("duplicate option id"));
        DialogSyntaxException twoInitial = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x",
                yaml("""
                        title: T
                        inputs:
                          - type: option
                            key: c
                            options:
                              - {id: a, initial: true}
                              - {id: b, initial: true}
                        """), DEFAULTS));
        assertTrue(twoInitial.getMessage().contains("at most one option"));
    }

    @Test void rejectsBadKeysAndShadowingOfBuiltIns() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - key: player
                  - key: Name
                  - key: ok
                  - key: ok
                """), DEFAULTS));
        assertTrue(e.getMessage().contains("built-in"));
        assertTrue(e.getMessage().contains("inputs[1].key"));
        assertTrue(e.getMessage().contains("duplicate key"));
    }

    @Test void rejectsActionsReferencingUnknownVariables() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - key: name
                buttons:
                  - label: Go
                    actions: [ "console: give {player} {item}" ]
                """), DEFAULTS));
        assertTrue(e.getMessage().contains("{item}"));
        assertEquals(List.of("item"), DialogParser.unknownVariables(
                List.of("console: give {player} {item} {name}"), Set.of("name")));
    }

    @Test void validatesNumberRangeAndTextInitial() {
        assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - {type: number, key: n, start: 5, end: 1}
                """), DEFAULTS));
        assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - {type: number, key: n, start: 1, end: 5, initial: 9}
                """), DEFAULTS));
        assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - {key: t, max-length: 3, initial: "toolong"}
                """), DEFAULTS));
        assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x", yaml("""
                title: T
                inputs:
                  - {key: t, pattern: "[a-"}
                """), DEFAULTS));
    }

    @Test void requiresTitleAndValidFileName() {
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class,
                () -> DialogParser.parse("Bad Name", yaml("body: hi"), DEFAULTS));
        assertTrue(e.getMessage().contains("title: required"));
        assertTrue(e.getMessage().contains("file name"));
    }

    @Test void oneBrokenFileDoesNotStopTheOthers() throws Exception {
        Files.writeString(folder.resolve("alpha.yml"), "title: Alpha\n");
        Files.writeString(folder.resolve("broken.yml"), "title: [unclosed\n  - : :\n");
        Files.writeString(folder.resolve("typed.yml"), "title: T\ncolumns: lots\n");
        Files.writeString(folder.resolve("zulu.yml"), """
                title: Zulu
                buttons:
                  - label: Next
                    open: missing
                """);
        Files.writeString(folder.resolve("notes.txt"), "ignored");
        Files.createDirectory(folder.resolve("dir.yml"));

        DialogLoader.Result result = DialogLoader.load(folder, DEFAULTS, null);

        assertEquals(List.of("alpha", "zulu"), List.copyOf(result.dialogs().keySet()));
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("broken.yml:")), result.errors().toString());
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("typed.yml: columns")));
        assertTrue(result.errors().stream().anyMatch(e -> e.startsWith("dir.yml:")));
        assertEquals(3, result.failedFiles());
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("missing")));
    }

    @Test void actionSyntaxErrorsSkipOnlyThatFile() throws Exception {
        Files.writeString(folder.resolve("good.yml"), "title: G\nbuttons:\n  - label: A\n    actions: [ 'msg: hi' ]\n");
        Files.writeString(folder.resolve("bad.yml"), "title: B\nbuttons:\n  - label: A\n    actions: [ 'nope: x' ]\n");
        DialogLoader.Result result = DialogLoader.load(folder, DEFAULTS, lines -> lines.stream()
                .filter(line -> !line.startsWith("msg:"))
                .map(line -> "line 1: unknown executor")
                .toList());
        assertEquals(List.of("good"), List.copyOf(result.dialogs().keySet()));
        assertTrue(result.errors().get(0).startsWith("bad.yml: buttons[0].actions: line 1"));
    }

    @Test void missingFolderIsCreated() throws Exception {
        Path missing = folder.resolve("nested").resolve("dialogs");
        DialogLoader.Result result = DialogLoader.load(missing, DEFAULTS, null);
        assertTrue(result.dialogs().isEmpty());
        assertTrue(Files.isDirectory(missing));
    }

    @Test void parsesOnOpenAndCooldown() throws Exception {
        DialogDefinition d = DialogParser.parse("x", yaml("""
                title: T
                on-open:
                  - "console: say hi {player}"
                  - "msg: welcome"
                cooldown: 5m30s
                """), DEFAULTS);
        assertEquals(2, d.onOpen().size());
        assertEquals(java.time.Duration.ofMinutes(5).plusSeconds(30), d.cooldown());

        DialogDefinition seconds = DialogParser.parse("x", yaml("title: T\ncooldown: 90"), DEFAULTS);
        assertEquals(java.time.Duration.ofSeconds(90), seconds.cooldown());
        assertNull(DialogParser.parse("x", yaml("title: T\ncooldown: 0"), DEFAULTS).cooldown());
        // An input value does not exist yet when on-open runs.
        DialogSyntaxException e = assertThrows(DialogSyntaxException.class, () -> DialogParser.parse("x",
                yaml("title: T\ninputs: [{key: name}]\non-open: ['msg: {name}']"), DEFAULTS));
        assertTrue(e.getMessage().contains("on-open"), e.getMessage());
        assertThrows(DialogSyntaxException.class,
                () -> DialogParser.parse("x", yaml("title: T\ncooldown: soon"), DEFAULTS));
        assertThrows(DialogSyntaxException.class,
                () -> DialogParser.parse("x", yaml("title: T\ncooldown: 999d"), DEFAULTS));
    }

    @Test void extendsSharesHeaderAndButtonsBetweenFiles() throws Exception {
        Files.writeString(folder.resolve("base.yml"), """
                title: "<gold>Base"
                columns: 3
                body: [ "Shared header" ]
                inputs:
                  - {type: text, key: name, max-length: 8}
                buttons:
                  - label: Shared
                    actions: [ "msg: shared" ]
                """);
        Files.writeString(folder.resolve("child.yml"), """
                extends: base
                title: Child
                body: [ "Own line" ]
                inputs:
                  - {type: bool, key: loud}
                buttons:
                  - label: Own
                    actions: [ "msg: {name} {loud}" ]
                """);
        DialogLoader.Result result = DialogLoader.load(folder, DEFAULTS, null);
        assertEquals(List.of(), result.errors());
        DialogDefinition child = result.dialogs().get("child");
        assertEquals("Child", child.title());
        assertEquals(3, child.columns());
        assertEquals(List.of("Shared header", "Own line"),
                child.body().stream().map(DialogDefinition.BodyLine::text).toList());
        assertEquals(List.of("name", "loud"), child.inputs().stream().map(input -> input.key()).toList());
        assertEquals(2, child.buttons().size());
        assertEquals("base", result.dialogs().get("base").id());
    }

    @Test void extendsCycleSkipsOnlyTheFilesInvolved() throws Exception {
        Files.writeString(folder.resolve("a.yml"), "extends: b\ntitle: A\n");
        Files.writeString(folder.resolve("b.yml"), "extends: a\ntitle: B\n");
        Files.writeString(folder.resolve("ok.yml"), "title: Fine\n");
        DialogLoader.Result result = DialogLoader.load(folder, DEFAULTS, null);
        assertEquals(List.of("ok"), List.copyOf(result.dialogs().keySet()));
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("cycle")), result.errors().toString());
        assertEquals(2, result.failedFiles());
    }

    @Test void onOpenActionSyntaxIsCheckedAtLoadTime() throws Exception {
        Files.writeString(folder.resolve("bad.yml"), "title: T\non-open: [ 'nope: x' ]\n");
        DialogLoader.Result result = DialogLoader.load(folder, DEFAULTS, lines -> lines.stream()
                .filter(line -> !line.startsWith("msg:"))
                .map(line -> "line 1: unknown executor")
                .toList());
        assertEquals(List.of(), List.copyOf(result.dialogs().keySet()));
        assertTrue(result.errors().get(0).startsWith("bad.yml: on-open: line 1"), result.errors().toString());
    }

    @Test void bundledExampleIsValid() throws Exception {
        try (InputStream in = DialogParserTest.class.getResourceAsStream("/dialogs/example.yml")) {
            assertNotNull(in);
            Files.copy(in, folder.resolve("example.yml"));
        }
        DialogLoader.Result result = DialogLoader.load(folder, DEFAULTS, null);
        assertEquals(List.of(), result.errors());
        assertEquals(4, result.dialogs().get("example").inputs().size());
    }
}
