package dev.yanianz.atrium.module.customtext;

import dev.yanianz.atrium.core.message.Msg;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlaceholderSubstitutionTest {
    @Test void escapesEverySubstitutedValue() {
        String result = PlaceholderSubstitution.substituteOnce("<gray>Hi %who%", token -> "<red>Steve");
        assertEquals("<gray>Hi \\<red>Steve", result);
    }

    @Test void neverEvaluatesASubstitutedValueAgain() {
        AtomicInteger calls = new AtomicInteger();
        String result = PlaceholderSubstitution.substituteOnce("%x% and %x%", token -> {
            calls.incrementAndGet();
            return "<red>%y%";
        });
        assertEquals("\\<red>%y% and \\<red>%y%", result);
        assertEquals(2, calls.get(), "the matcher must not re-scan resolved values");
    }

    @Test void escapesBackslashes() {
        assertEquals("a\\\\b", PlaceholderSubstitution.escapeMiniMessage("a\\b"));
        assertEquals("a\\\\b", PlaceholderSubstitution.substituteOnce("%x%", token -> "a\\b"));
    }

    @Test void leavesTemplatesWithoutTokensAlone() {
        assertEquals("<gold>No placeholders", PlaceholderSubstitution.substituteOnce("<gold>No placeholders",
                token -> {
                    throw new AssertionError("resolver must not be called");
                }));
    }

    @Test void unresolvedTokenStaysLiteral() {
        assertEquals("<gray>%unknown%", PlaceholderSubstitution.substituteOnce("<gray>%unknown%", token -> "%" + token
                + "%"));
    }

    @Test void escapedValueIsLiteralAfterMiniMessage() {
        String prepared = PlaceholderSubstitution.substituteOnce("<gray>Hi %who%", token -> "<red>Steve");
        Component component = MiniMessage.miniMessage().deserialize(prepared);
        assertEquals("Hi <red>Steve", PlainTextComponentSerializer.plainText().serialize(component));
        assertFalse(PlainTextComponentSerializer.plainText().serialize(component).contains("Steve<"));
    }

    @Test void builtInPlaceholdersAreInsertedUnparsed() {
        Component component = MiniMessage.miniMessage().deserialize("<gray>Hi <player> in <world> (<online>)",
                Msg.unparsed("player", "<red>Steve"), Msg.unparsed("world", "<gold>world_nether"),
                Msg.unparsed("online", "4"));
        assertEquals("Hi <red>Steve in <gold>world_nether (4)",
                PlainTextComponentSerializer.plainText().serialize(component));
    }
}
