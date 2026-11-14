package dev.yanianz.atrium.module.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatContentListenerTest {
    @Test
    void unchangedContentPreservesExistingStyling() {
        Component original = Component.text("allowed", NamedTextColor.GOLD);
        assertSame(original, ChatContentListener.rewrite(original, "allowed"));
    }

    @Test
    void replacementIsLiteralAndDoesNotRetainOriginalInteractions() {
        Component original = Component.text("badword")
                .clickEvent(ClickEvent.runCommand("/original"));
        Component result = ChatContentListener.rewrite(original, "<click:run_command:'/op name'>***</click>");
        assertEquals(Component.text("<click:run_command:'/op name'>***</click>"), result);
        assertNull(result.clickEvent());
    }

    @Test
    void absentContentCanBeRewrittenWithoutParsingIt() {
        assertEquals(Component.text("<red>literal"), ChatContentListener.rewrite(null, "<red>literal"));
    }
}
