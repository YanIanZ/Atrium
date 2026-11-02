package dev.yanianz.atrium.core.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HelpIndexTest {
    private static HelpIndex.Command cmd(String module, String name) {
        return new HelpIndex.Command(module, name, "d", List.of("/" + name));
    }

    @Test
    void sortsCoreFirstAndInsertsHeadingsPerPage() {
        List<HelpIndex.Command> commands = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            commands.add(cmd("homes", "h" + (char) ('a' + i)));
        }
        commands.add(cmd("core", "atrium"));
        commands.add(cmd("afk", "afk"));
        HelpIndex index = new HelpIndex(commands);
        assertEquals("core", index.commands().getFirst().module());
        assertEquals(List.of("core", "afk", "homes"), List.copyOf(index.byModule().keySet()));

        HelpIndex.Page first = index.page(0, 10);
        assertEquals(2, first.pages());
        assertEquals(new HelpIndex.Heading("core"), first.lines().getFirst());
        HelpIndex.Page second = index.page(1, 10);
        assertEquals(new HelpIndex.Heading("homes"), second.lines().getFirst(), "continued module gets a heading");
        assertEquals(5, second.lines().size());
        assertInstanceOf(HelpIndex.Entry.class, second.lines().get(1));
        assertEquals(1, index.page(9, 10).page(), "page is clamped");
        assertEquals(12, index.module("HOMES").size());
        assertTrue(index.module("nope").isEmpty());
    }

    @Test
    void parsesPageOrModule() {
        assertEquals(0, HelpIndex.parsePage("1"));
        assertEquals(4, HelpIndex.parsePage("5"));
        assertEquals(0, HelpIndex.parsePage("0"));
        assertEquals(-1, HelpIndex.parsePage("homes"));
        assertEquals(-1, HelpIndex.parsePage("9999999"));
    }

    @Test
    void pasteTextStopsAtFirstArgument() {
        assertEquals("/home set ", HelpIndex.pasteText("/home set <name>"));
        assertEquals("/spawn ", HelpIndex.pasteText("/spawn"));
        assertEquals("/tp ", HelpIndex.pasteText("/tp ..."));
    }

    @Test
    void usagesHideNodesTheSenderCannotUse() {
        var node = LiteralArgumentBuilder.<String>literal("eco")
                .then(LiteralArgumentBuilder.<String>literal("balance").executes(c -> 1))
                .then(LiteralArgumentBuilder.<String>literal("give").requires("admin"::equals)
                        .then(RequiredArgumentBuilder.<String, String>argument("amount", StringArgumentType.word())
                                .executes(c -> 1)))
                .build();
        assertEquals(List.of("/eco balance"), DocsGenerator.usages(node, n -> n.canUse("player")));
        assertEquals(2, DocsGenerator.usages(node, n -> n.canUse("admin")).size());
        assertEquals(2, DocsGenerator.usages(node).size(), "unfiltered overload unchanged");
    }
}
