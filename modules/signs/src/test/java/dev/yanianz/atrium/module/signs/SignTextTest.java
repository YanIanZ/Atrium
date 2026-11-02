package dev.yanianz.atrium.module.signs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class SignTextTest {
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void appliesColoursWhenPermitted() {
        Component result = SignText.format(Component.text("<red>hello"), true, false);
        assertEquals("hello", plain(result));
        assertEquals(NamedTextColor.RED, result.color());
    }

    @Test
    void appliesDecorationsWhenPermitted() {
        Component result = SignText.format(Component.text("<bold>hello"), false, true);
        assertEquals("hello", plain(result));
        assertEquals(TextDecoration.State.TRUE, result.decoration(TextDecoration.BOLD));
        assertNull(result.color());
    }

    @Test
    void keepsUnauthorisedColourTagsLiteral() {
        Component result = SignText.format(Component.text("<red>hello"), false, true);
        assertEquals("<red>hello", plain(result));
        assertNull(result.color());
    }

    @Test
    void neverParsesClickOrHoverTags() {
        Component click = SignText.format(Component.text("<click:run_command:/op>hello"), true, true);
        assertEquals("<click:run_command:/op>hello", plain(click));
        assertNull(click.clickEvent());

        Component hover = SignText.format(Component.text("<hover:show_text:secret>hello"), true, true);
        assertEquals("<hover:show_text:secret>hello", plain(hover));
        assertNull(hover.hoverEvent());
    }

    @Test
    void leavesLinesWithoutTagsUntouched() {
        Component line = Component.text("hello").color(NamedTextColor.GOLD);
        assertSame(line, SignText.format(line, true, true));
    }

    @Test
    void doesNothingWithoutPermissionOrTags() {
        Component line = Component.text("<red>hello");
        assertSame(line, SignText.format(line, false, false));
    }

    @Test
    void enforcesSignLineLengthOnPlainText() {
        Component thirtyEight = Component.text("x".repeat(38));
        assertFalse(SignText.exceeds(thirtyEight, 38));
        assertTrue(SignText.exceeds(Component.text("x".repeat(39)), 38));
        assertEquals(5, SignText.plainLength(SignText.format(Component.text("<red>hello"), true, false)));
    }

    @Test
    void stripInteractiveRemovesClickHoverAndInsertionRecursively() {
        Component nested = Component.text("inner")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/op attacker"))
                .insertion("x");
        Component line = Component.text("outer", NamedTextColor.RED)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("h")))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/stop"))
                .append(nested);
        Component stripped = SignText.stripInteractive(line);
        assertNull(stripped.clickEvent());
        assertNull(stripped.hoverEvent());
        assertNull(stripped.children().getFirst().clickEvent());
        assertNull(stripped.children().getFirst().insertion());
        assertEquals(NamedTextColor.RED, stripped.color());
        assertEquals("outerinner", SignText.plain(stripped));
    }
}
