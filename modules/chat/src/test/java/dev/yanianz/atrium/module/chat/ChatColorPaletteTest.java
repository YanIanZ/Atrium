package dev.yanianz.atrium.module.chat;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatColorPaletteTest {
    private static final String GRADIENT = ChatColorPalette.GRADIENT_PERMISSION;

    @Test
    void colourResolutionRequiresTheMatchingPermission() {
        ChatColorPalette palette = palette();
        assertTrue(palette.resolve("red", permission("atrium.chatcolor.red")).isPresent());
        assertTrue(palette.resolve("red", permission()).isEmpty());
        assertTrue(palette.resolve("red", permission("atrium.chatcolor.blue")).isEmpty());
    }

    @Test
    void gradientsRequireBothTheEntryAndTheGradientPermission() {
        ChatColorPalette palette = palette();
        assertTrue(palette.resolve("rainbow", permission("atrium.chatcolor.rainbow")).isEmpty());
        ChatColorPalette.Choice choice = palette.resolve("rainbow",
                permission("atrium.chatcolor.rainbow", GRADIENT)).orElseThrow();
        assertTrue(choice.gradient());
        assertEquals(3, choice.colors().size());
    }

    @Test
    void resolutionIsCaseInsensitiveAndUnknownOrDisabledNamesAreEmpty() {
        ChatColorPalette palette = palette();
        assertTrue(palette.resolve("REd", permission("atrium.chatcolor.red")).isPresent());
        assertTrue(palette.resolve("does-not-exist", name -> true).isEmpty());
        assertTrue(palette.resolve(null, name -> true).isEmpty());

        ChatConfig.ChatColors disabled = new ChatConfig.ChatColors();
        disabled.enabled = false;
        assertTrue(ChatColorPalette.compile(disabled, message -> fail(message)).choices().isEmpty());
    }

    @Test
    void invalidAndDuplicateEntriesAreSkippedWithAWarning() {
        ChatConfig.ChatColors config = new ChatConfig.ChatColors();
        config.entries = List.of(
                new ChatConfig.ColorEntry("Bad Name!", "COLOR", "red", List.of()),
                new ChatConfig.ColorEntry("red", "COLOR", "red", List.of()),
                new ChatConfig.ColorEntry("red", "COLOR", "blue", List.of()),
                new ChatConfig.ColorEntry("broken", "COLOR", "not-a-colour", List.of()),
                new ChatConfig.ColorEntry("good", "COLOR", "#12ab34", List.of()));
        List<String> warnings = new java.util.ArrayList<>();
        ChatColorPalette palette = ChatColorPalette.compile(config, warnings::add);
        assertEquals(List.of("red", "good"),
                palette.choices().stream().map(ChatColorPalette.Choice::name).toList());
        assertEquals(3, warnings.size());
    }

    @Test
    void solidColoursAreAppliedAsAParentStyle() {
        ChatColorPalette.Choice red = palette().byName("red").orElseThrow();
        Component styled = ChatColorPalette.apply(Component.text("hello"), red);
        assertEquals(NamedTextColor.RED, styled.color());
    }

    @Test
    void gradientsAreSplitPerCodePointAndNeverSplitSurrogatePairs() {
        ChatColorPalette.Choice gradient = palette().byName("rainbow").orElseThrow();
        Component styled = ChatColorPalette.apply(Component.text("a\uD83D\uDE04b"), gradient);
        TextComponent text = (TextComponent) styled;
        assertEquals(3, text.children().size(), "an emoji counts as one coloured code point");
        assertEquals("a", ((TextComponent) text.children().get(0)).content());
        assertEquals("\uD83D\uDE04", ((TextComponent) text.children().get(1)).content());
        assertNotEquals(text.children().get(0).color(), text.children().get(2).color());
    }

    @Test
    void gradientInterpolationHitsBothEndsAndTheMiddle() {
        List<TextColor> stops = List.of(TextColor.color(0x000000), TextColor.color(0xFFFFFF));
        List<TextColor> colors = ChatColorPalette.interpolate(stops, 3);
        assertEquals(0x000000, colors.get(0).value());
        assertEquals(0x808080, colors.get(1).value());
        assertEquals(0xFFFFFF, colors.get(2).value());
    }

    @Test
    void namedAndHexColoursParse() {
        assertEquals(NamedTextColor.GOLD, ChatColorPalette.parse("gold").orElseThrow());
        assertEquals(TextColor.color(0x12AB34), ChatColorPalette.parse("#12ab34").orElseThrow());
        assertTrue(ChatColorPalette.parse("#zzzzzz").isEmpty());
        assertTrue(ChatColorPalette.parse("nope").isEmpty());
    }

    private static Predicate<String> permission(String... allowed) {
        Set<String> nodes = Set.of(allowed);
        return nodes::contains;
    }

    private static ChatColorPalette palette() {
        ChatConfig.ChatColors config = new ChatConfig.ChatColors();
        config.entries = List.of(
                new ChatConfig.ColorEntry("red", "COLOR", "red", List.of()),
                new ChatConfig.ColorEntry("blue", "COLOR", "blue", List.of()),
                new ChatConfig.ColorEntry("rainbow", "GRADIENT", "",
                        List.of("#ff0000", "#00ff00", "#0000ff")));
        return ChatColorPalette.compile(config, message -> fail(message));
    }
}
