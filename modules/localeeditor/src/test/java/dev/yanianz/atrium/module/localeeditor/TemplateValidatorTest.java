package dev.yanianz.atrium.module.localeeditor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Templates are admin MiniMessage with placeholders; only real syntax errors may be refused. */
class TemplateValidatorTest {
    private final MiniMessage mini = MiniMessage.miniMessage();
    private final TagResolver known = TagResolver.resolver(
            TagResolver.standard(),
            TagResolver.resolver("primary", Tag.styling(NamedTextColor.AQUA)),
            TagResolver.resolver("highlight", Tag.styling(NamedTextColor.YELLOW)),
            TagResolver.resolver("error", Tag.styling(NamedTextColor.RED)),
            TagResolver.resolver("prefix", Tag.inserting(net.kyori.adventure.text.Component.empty())));

    @Test
    void acceptsThemeTagsAndPlaceholders() {
        assertNull(TemplateValidator.error(
                "<prefix><success>Home <highlight><home></highlight> set for <player>.", mini, known));
        assertNull(TemplateValidator.error("<gradient:#5fb3a1:#3b7f9a>Atrium</gradient>", mini, known));
        assertNull(TemplateValidator.error("<click:run_command:'/spawn'>Click</click>", mini, known));
        assertNull(TemplateValidator.error("<primary><bold>Header</bold></primary>", mini, known));
    }

    @Test
    void acceptsUnknownPlaceholdersAsNames() {
        assertNull(TemplateValidator.error("<player> has <count> homes", mini, known));
        assertNull(TemplateValidator.error("<player></player>", mini, known));
    }

    @Test
    void acceptsEscapedAngleBracketsAndPlainText() {
        assertNull(TemplateValidator.error("1 \\< 2 and 3 > 2", mini, known));
        assertNull(TemplateValidator.error("", mini, known));
    }

    @Test
    void reportsABrokenColourWithTheParserFinding() {
        String message = TemplateValidator.error("<#gggggg>broken", mini, known);
        assertNotNull(message);
        assertTrue(message.toLowerCase(java.util.Locale.ROOT).contains("#gggggg"), message);
    }

    @Test
    void reportsABrokenGradient() {
        assertNotNull(TemplateValidator.error("<gradient:red>too few colours", mini, known));
    }

    @Test
    void reportsAnUnknownClickAction() {
        assertNotNull(TemplateValidator.error("<click:not_an_action>x</click>", mini, known));
    }

    @Test
    void renderKeepsPlaceholdersVisible() {
        String plain = PlainTextComponentSerializer.plainText()
                .serialize(TemplateValidator.render("<primary>Hi <player>", mini, known));
        assertTrue(plain.contains("<player>"), plain);
    }
}
