package dev.yanianz.atrium.module.chat;

import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The message published to later listeners (the Discord bridge, chat bubbles, cross-server relays) must be
 * the filtered text, and never parsed as MiniMessage. DiscordListener reads {@code event.message()}.
 */
class ChatRelayMessageTest {
    @Test
    void relayedMessageCarriesTheFilteredText() {
        List<ChatFilter.Rule> rules = List.of(new ChatFilter.Rule("profanity",
                Pattern.compile(Pattern.quote("fuck")), ChatFilter.Action.CENSOR, false));
        ChatFilter.Verdict verdict = ChatFilter.apply("fuck this", rules, "***");
        assertEquals("*** this", verdict.text());
        assertEquals("*** this", plain(ChatModule.relayedMessage(verdict.text())));
    }

    @Test
    void relayedMessageNeverParsesMiniMessage() {
        Component relayed = ChatModule.relayedMessage("<red>hi <click:run_command:/op me>");
        assertEquals("<red>hi <click:run_command:/op me>", plain(relayed));
        assertTrue(relayed.children().isEmpty());
        assertEquals(Component.text("<red>hi <click:run_command:/op me>"), relayed);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
