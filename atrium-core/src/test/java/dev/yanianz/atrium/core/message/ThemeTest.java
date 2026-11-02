package dev.yanianz.atrium.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class ThemeTest {
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @Test
    void parsesHexAndNamedColours() {
        assertEquals(TextColor.color(0x5FB3A1), Theme.parseColor("#5FB3A1"));
        assertEquals(TextColor.color(0x5FB3A1), Theme.parseColor("5fb3a1"));
        assertEquals(NamedTextColor.GRAY, Theme.parseColor("grey"));
        assertEquals(NamedTextColor.DARK_AQUA, Theme.parseColor("Dark-Aqua"));
        assertNull(Theme.parseColor("#12345"));
        assertNull(Theme.parseColor("not-a-colour"));
        assertNull(Theme.parseColor(""));
    }

    @Test
    void defaultsMatchThePrefixGradient() {
        Theme theme = Theme.defaults();
        assertEquals(TextColor.color(0x5FB3A1), theme.color("primary"));
        assertEquals(NamedTextColor.GRAY, theme.color("muted"));
        assertEquals(NamedTextColor.WHITE, theme.color("highlight"));
        assertEquals(Theme.TAGS, List.copyOf(theme.colors().keySet()));
    }

    @Test
    void everyTagResolvesToItsColour() {
        Theme theme = Theme.defaults();
        for (String tag : Theme.TAGS) {
            Component c = MINI.deserialize("<" + tag + ">x</" + tag + ">", theme.tags());
            assertEquals(theme.color(tag), firstColor(c), tag);
            assertEquals("x", plain(c));
        }
    }

    @Test
    void invalidConfigValuesFallBackWithAWarning() {
        CoreConfig.ThemeSettings settings = new CoreConfig.ThemeSettings();
        settings.primary = "#ABCDEF";
        settings.error = "bloodred";
        settings.gui.clickSound = "Not A Key!";
        settings.gui.openSound = "";
        settings.gui.soundVolume = 7;
        List<String> warnings = new ArrayList<>();
        Theme theme = Theme.from(settings, warnings::add);
        assertEquals(TextColor.color(0xABCDEF), theme.color("primary"));
        assertEquals(Theme.defaults().color("error"), theme.color("error"));
        assertNull(theme.gui().openSound(), "empty sound = silent");
        assertEquals(Theme.Gui.defaults().clickSound(), theme.gui().clickSound());
        assertEquals(1.0f, theme.gui().soundVolume());
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void messagesResolveThemeTagsButNotInsideUntrustedText(@TempDir Path dir) {
        Messages messages = messages(dir, "greet: \"<prefix><primary>Hi</primary> <highlight><name>\"\n"
                + "fail: \"<warning>Failed: <error>\"\n");
        Component rendered = messages.render("en_US", "test.greet", Msg.unparsed("name", "<error>evil</error>"));
        assertEquals("[A] Hi <error>evil</error>", plain(rendered), "unparsed text stays literal");
        assertTrue(hasColor(rendered, TextColor.color(0x5FB3A1)), "primary applied");
        // A per-call placeholder wins over the theme tag of the same name.
        Component fail = messages.render("en_US", "test.fail", Msg.unparsed("error", "disk full"));
        assertEquals("Failed: disk full", plain(fail));
    }

    @Test
    void applyThemeChangesColoursAndPrefix(@TempDir Path dir) {
        Messages messages = messages(dir, "x: \"<prefix><primary>t\"\n");
        CoreConfig.ThemeSettings settings = new CoreConfig.ThemeSettings();
        settings.primary = "#010203";
        settings.prefix = "<secondary>P> ";
        messages.applyTheme(Theme.from(settings, w -> { }));
        Component c = messages.render("en_US", "test.x");
        assertEquals("P> t", plain(c));
        assertTrue(hasColor(c, TextColor.color(0x010203)));
        assertTrue(hasColor(c, TextColor.color(0x3B7F9A)), "theme tags work inside the prefix");
        assertTrue(hasColor(messages.parseTrusted("<primary>q"), TextColor.color(0x010203)), "parseTrusted uses the theme");
    }

    private static Messages messages(Path dir, String testBundle) {
        Map<String, String> resources = Map.of(
                "lang/core/en_US.yml", "prefix: \"[A] \"\n",
                "lang/test/en_US.yml", testBundle);
        Messages messages = new Messages(dir, path -> stream(resources.get(path)), NOPLogger.NOP_LOGGER);
        messages.loadBundle("core");
        messages.loadBundle("test");
        messages.applyTheme(Theme.defaults());
        return messages;
    }

    private static InputStream stream(String text) {
        return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    private static TextColor firstColor(Component c) {
        if (c.color() != null) {
            return c.color();
        }
        for (Component child : c.children()) {
            TextColor color = firstColor(child);
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    private static boolean hasColor(Component c, TextColor color) {
        if (color.equals(c.color())) {
            return true;
        }
        return c.children().stream().anyMatch(child -> hasColor(child, color));
    }
}
