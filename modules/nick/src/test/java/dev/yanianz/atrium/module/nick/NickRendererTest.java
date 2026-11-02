package dev.yanianz.atrium.module.nick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NickRendererTest {
    private final NickRenderer renderer = new NickRenderer();

    @Test void parsesOnlyTheTagGroupsThatAreEnabled() {
        assertEquals("Bob", renderer.plain(renderer.typed("<red><bold>Bob</bold></red>", true, true, false)));
        assertEquals("<bold>Bob</bold>",
                renderer.plain(renderer.typed("<red><bold>Bob</bold></red>", true, false, false)));
        // A tag the player may not use remains literal text, which validation then rejects.
        assertEquals("<red>Bob</red>", renderer.plain(renderer.typed("<red>Bob</red>", false, false, false)));
        assertEquals("<bold>Bob</bold>", renderer.plain(renderer.typed("<bold>Bob</bold>", false, false, false)));
    }

    @Test void appliesColourDecorationAndGradient() {
        Component colored = renderer.typed("<red><bold>Bob</bold></red>", true, true, false);
        assertEquals(NamedTextColor.RED, colored.color());
        assertEquals(TextDecoration.State.TRUE, colored.decoration(TextDecoration.BOLD));

        Component gradient = renderer.typed("<gradient:red:blue>Bob</gradient>", false, false, true);
        assertEquals("Bob", renderer.plain(gradient));
        assertNotEquals(Component.text("Bob"), gradient);
    }

    @Test void storedNamesAreDowngradedWithoutLeavingLiteralTags() {
        NickState state = new NickState("<red><bold>Bob</bold></red>", "Bob", 0L);
        Component colorOnly = renderer.stored(state, true, false, false);
        assertEquals("Bob", renderer.plain(colorOnly));
        assertEquals(NamedTextColor.RED, colorOnly.color());
        assertEquals(TextDecoration.State.NOT_SET, colorOnly.decoration(TextDecoration.BOLD));

        Component plain = renderer.stored(state, false, false, false);
        assertEquals("Bob", renderer.plain(plain));
        assertEquals(null, plain.color());
    }

    @Test void appliesTheOptionalDisplayPrefix() {
        assertEquals("~Bob", renderer.plain(NickRenderer.decorate("~", Component.text("Bob"))));
        assertEquals("Bob", renderer.plain(NickRenderer.decorate("", Component.text("Bob"))));
        assertEquals("Bob", renderer.plain(NickRenderer.decorate(null, Component.text("Bob"))));
    }

    @Test void lookupTextRemovesTagsSoTypedAndStoredFormsMatch() {
        assertEquals("Bob", renderer.lookupText("<red>Bob</red>"));
        assertEquals("Bob", renderer.lookupText("Bob"));
    }
}
