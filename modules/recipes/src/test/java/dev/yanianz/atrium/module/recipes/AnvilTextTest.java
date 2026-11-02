package dev.yanianz.atrium.module.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class AnvilTextTest {
    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void appliesColoursOnlyWithColourPermission() {
        Component result = AnvilText.format("<red>Blade", true, false, 64);
        assertNotNull(result);
        assertEquals("Blade", plain(result));
        assertEquals(NamedTextColor.RED, result.color());
    }

    @Test
    void appliesDecorationsOnlyWithDecorationPermission() {
        Component result = AnvilText.format("<bold>Blade", false, true, 64);
        assertNotNull(result);
        assertEquals(TextDecoration.State.TRUE, result.decoration(TextDecoration.BOLD));
        assertNull(result.color());
    }

    @Test
    void keepsUnauthorisedColourTagsLiteral() {
        Component result = AnvilText.format("<red>Blade", false, true, 64);
        assertNotNull(result);
        assertEquals("<red>Blade", plain(result));
        assertNull(result.color());
    }

    @Test
    void neverCreatesClickOrHoverEvents() {
        Component click = AnvilText.format("<click:run_command:/op>Blade", true, true, 64);
        assertNotNull(click);
        assertEquals("<click:run_command:/op>Blade", plain(click));
        assertNull(click.clickEvent());

        Component hover = AnvilText.format("<hover:show_text:secret>Blade", true, true, 64);
        assertNotNull(hover);
        assertEquals("<hover:show_text:secret>Blade", plain(hover));
        assertNull(hover.hoverEvent());
    }

    @Test
    void gradientAndLangTagsStayLiteral() {
        Component gradient = AnvilText.format("<gradient:red:blue>Blade", true, true, 64);
        assertNotNull(gradient);
        assertInstanceOf(Component.class, gradient);
        assertEquals("<gradient:red:blue>Blade", plain(gradient));
    }

    @Test
    void returnsNullWhenNothingShouldChange() {
        assertNull(AnvilText.format("Blade", true, true, 64));
        assertNull(AnvilText.format("<red>Blade", false, false, 64));
        assertNull(AnvilText.format(null, true, true, 64));
        assertNull(AnvilText.format("", true, true, 64));
    }

    @Test
    void rejectsPlainTextLongerThanTheLimit() {
        assertNull(AnvilText.format("<red>" + "x".repeat(65), true, true, 64));
        assertNotNull(AnvilText.format("<red>" + "x".repeat(64), true, true, 64));
    }
}
