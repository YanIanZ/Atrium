package dev.yanianz.atrium.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/** A missing translation must fall back to the key and never throw while a player is being answered. */
class MessagesTest {
    @TempDir
    Path directory;

    private static Function<String, InputStream> resources(Map<String, String> files) {
        return name -> {
            String text = files.get(name);
            return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        };
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void missingKeyRendersTheKeyInsteadOfThrowing() {
        Messages messages = new Messages(directory, resources(Map.of(
                "lang/test/en_US.yml", "present: 'Hello'\n")), NOPLogger.NOP_LOGGER);
        messages.configure("en_US", true);
        messages.loadBundle("test");

        assertEquals("Hello", plain(messages.render("en_US", "test.present")));
        assertEquals("test.absent", plain(messages.render("en_US", "test.absent")));
        assertFalse(messages.has("test.absent"));
    }

    @Test
    void unknownLocaleFallsBackToTheDefaultLocale() {
        Map<String, String> files = new HashMap<>();
        files.put("lang/test/en_US.yml", "present: 'Hello'\n");
        files.put("lang/test/id_ID.yml", "present: 'Halo'\n");
        Messages messages = new Messages(directory, resources(files), NOPLogger.NOP_LOGGER);
        messages.configure("id_ID", true);
        messages.loadBundle("test");

        // No zz_ZZ resources: the requested locale falls back to the configured default (id_ID).
        assertEquals("Halo", plain(messages.render("zz_ZZ", "test.present")));
    }
}
