package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.junit.jupiter.api.Test;

class DeathComponentsTest {
    @Test
    void removesInteractiveEventsButKeepsTheText() {
        Component item = Component.text("Sword")
                .hoverEvent(HoverEvent.showText(Component.text("secret")))
                .clickEvent(ClickEvent.runCommand("/op"))
                .insertion("secret");
        Component clean = DeathComponents.withoutEvents(item);
        assertEquals("Sword", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(clean));
        assertNull(clean.hoverEvent());
        assertNull(clean.clickEvent());
        assertNull(clean.insertion());
    }

    @Test
    void stripsEventsFromChildrenToo() {
        Component child = Component.text("child").hoverEvent(HoverEvent.showText(Component.text("nested")));
        Component clean = DeathComponents.withoutEvents(Component.text("root").append(child));
        assertNull(clean.children().getFirst().hoverEvent());
    }
}
