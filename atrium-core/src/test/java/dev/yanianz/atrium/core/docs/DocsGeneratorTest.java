package dev.yanianz.atrium.core.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class DocsGeneratorTest {
    @Test
    void usagesListEveryExecutablePath() {
        var node = LiteralArgumentBuilder.<Object>literal("home")
                .executes(c -> 1)
                .then(RequiredArgumentBuilder.<Object, String>argument("name", StringArgumentType.word()).executes(c -> 1))
                .then(LiteralArgumentBuilder.<Object>literal("list")
                        .then(RequiredArgumentBuilder.<Object, String>argument("page", StringArgumentType.word()).executes(c -> 1)))
                .build();
        List<String> usages = DocsGenerator.usages(node);
        assertTrue(usages.contains("/home"));
        assertTrue(usages.contains("/home <name>"));
        assertTrue(usages.contains("/home list <page>"));
        assertEquals(3, usages.size(), "a non-executable intermediate literal is not a usage");
    }

    @Test
    void markdownEscapesTableCellsAndMarksDisabledCommands() {
        var doc = new DocsGenerator.CommandDoc("homes", "home", List.of("h"), "Go | home\nnow",
                List.of("/home"), true);
        String md = DocsGenerator.commandsMarkdown("1.0", List.of(doc));
        assertTrue(md.contains("~~`/home`~~"));
        assertTrue(md.contains("Go \\| home now"));
        assertTrue(md.contains("## homes"));
    }

    @Test
    void permissionsAreGroupedAndSorted() {
        var map = new TreeMap<String, List<DocsGenerator.PermissionDoc>>();
        map.put("homes", List.of(new DocsGenerator.PermissionDoc("atrium.sethome", "TRUE", "b"),
                new DocsGenerator.PermissionDoc("atrium.home", "TRUE", "a")));
        map.put("empty", List.of());
        String md = DocsGenerator.permissionsMarkdown("1.0", map);
        assertTrue(md.indexOf("atrium.home`") < md.indexOf("atrium.sethome`"));
        assertTrue(!md.contains("## empty"));
        assertTrue(md.contains("2 permissions"));
    }

    @Test
    void disabledLookupIsCaseInsensitiveOnName() {
        assertTrue(DocsGenerator.isDisabled("Home", Set.of("home")));
    }
}
