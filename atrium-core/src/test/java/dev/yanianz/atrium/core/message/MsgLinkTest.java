package dev.yanianz.atrium.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class MsgLinkTest {
    private static Component render(String template, String url) {
        return MiniMessage.miniMessage().deserialize(template, Msg.link("release_link", url));
    }

    private static ClickEvent firstClick(Component component) {
        if (component.clickEvent() != null) {
            return component.clickEvent();
        }
        for (Component child : component.children()) {
            ClickEvent found = firstClick(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void httpsLinkOpensTheUrlAndShowsNoRawTags() {
        String url = "https://github.com/YanIanZ/Atrium/releases/tag/v1.0.1";
        Component c = render("Update: <release_link>Open the release page</release_link>", url);
        assertEquals("Update: Open the release page", PlainTextComponentSerializer.plainText().serialize(c));
        ClickEvent click = firstClick(c);
        assertEquals(ClickEvent.Action.OPEN_URL, click.action());
        assertEquals(url, ((ClickEvent.Payload.Text) click.payload()).value());
    }

    @Test
    void nonHttpsUrlsGetNoClickAction() {
        Component c = render("<release_link>x</release_link>", "javascript:alert(1)");
        assertEquals("x", PlainTextComponentSerializer.plainText().serialize(c));
        assertNull(firstClick(c));
    }
}
