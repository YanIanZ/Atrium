package dev.yanianz.atrium.module.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class TabTextTest {
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final String ZWNJ = "‌";

    /** Renders a trusted template with one untrusted value, as TemplateRenderer does. */
    private static String render(String template, String untrusted) {
        return TabText.toTab(MINI.deserialize(template, Placeholder.unparsed("name", untrusted)));
    }

    @Test
    void trustedStylesBecomeTabLegacyCodes() {
        assertEquals("&cAdmin&r &fBob", render("<red>Admin</red> <white><name>", "Bob"));
        assertEquals("&#5fb3a1Hi", TabText.toTab(MINI.deserialize("<#5fb3a1>Hi")));
        assertEquals("&c&lBold", TabText.toTab(MINI.deserialize("<red><bold>Bold")));
        assertEquals("", TabText.toTab(Component.empty()));
        assertEquals("", TabText.toTab(null));
    }

    @Test
    void newlinesAreKeptForMultiLineHeaders() {
        assertEquals("a\nb", TabText.toTab(MINI.deserialize("a<newline>b")));
    }

    @Test
    void untrustedColourAndHexCodesCannotStyleText() {
        String out = render("<name>", "&cRed §lBold &#ff0000 #00ff00 {#0000ff} #<123456> &x&f&f&0&0&0&0");
        assertEquals("&" + ZWNJ + "cRed §" + ZWNJ + "lBold &" + ZWNJ + "#" + ZWNJ + "ff0000 #" + ZWNJ + "00ff00 {"
                + ZWNJ + "#" + ZWNJ + "0000ff} #" + ZWNJ + "<" + ZWNJ + "123456> &" + ZWNJ + "x&" + ZWNJ + "f&" + ZWNJ
                + "f&" + ZWNJ + "0&" + ZWNJ + "0&" + ZWNJ + "0&" + ZWNJ + "0", out);
        // No legacy code or hex colour of any TAB format survives.
        assertFalse(Pattern.compile("[&§][0-9a-fk-orx#]").matcher(out).find(), out);
        assertFalse(Pattern.compile("#[0-9a-fA-F]{6}").matcher(out).find(), out);
    }

    @Test
    void untrustedTagsAndGradientsCannotBeParsedByTabOrMiniMessage() {
        String untrusted = "<click:run_command:/op me><font:alt>x</font><#ff0000>g</#00ff00><$#ff0000>g<$#00ff00>"
                + " <head:Notch> \\<b>";
        String out = render("<gray><name>", untrusted);
        assertTrue(out.startsWith("&7"), out);
        // Every '<' is followed by the zero-width character, so no tag name can start; '\' cannot escape.
        assertFalse(Pattern.compile("<(?!" + ZWNJ + ")").matcher(out).find(), out);
        assertFalse(Pattern.compile("\\\\(?!" + ZWNJ + ")").matcher(out).find(), out);
        // MiniMessage (which TAB uses when available) shows exactly the untrusted text, nothing is parsed.
        String shown = PlainTextComponentSerializer.plainText().serialize(MINI.deserialize(out.substring(2)));
        assertEquals(untrusted, shown.replace(ZWNJ, ""));
    }

    @Test
    void placeholderPairsAreBrokenButASinglePercentIsKept() {
        assertEquals("&f50%", render("<white>50%", ""));
        String out = render("<name>", "%player_ip% and %vault_eco_balance%");
        assertFalse(out.contains("%"), out);
        assertEquals("％player_ip％ and ％vault_eco_balance％", out);
        // A pair formed by trusted and untrusted text together is broken too.
        assertFalse(render("Load: 50% <name>", "100%").contains("%"));
    }

    @Test
    void scoreboardLineSyntaxIsNeutralised() {
        String out = render("<name>", "Long|text||5||9");
        assertFalse(out.startsWith("Long|"), out);
        assertFalse(out.contains("||"), out);
        assertEquals("Long" + ZWNJ + "|text" + ZWNJ + "|" + ZWNJ + "|5" + ZWNJ + "|" + ZWNJ + "|9", out);
    }

    @Test
    void escapingKeepsStylesAndVisibleText() {
        Component rendered = Component.text("a&b", NamedTextColor.RED).append(Component.text("<c>", NamedTextColor.BLUE));
        Component escaped = TabText.escape(rendered);
        assertEquals("a&" + ZWNJ + "b<" + ZWNJ + "c>", PlainTextComponentSerializer.plainText().serialize(escaped));
        assertEquals("&ca&" + ZWNJ + "b&9<" + ZWNJ + "c>", TabText.toTab(rendered));
    }
}
