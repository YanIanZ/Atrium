package dev.yanianz.atrium.module.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.tablist.TabListService.Placement;
import dev.yanianz.atrium.module.display.TabText;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/** Tags other modules add through TabListService (AFK), composed with the rendered (nicknamed) list name. */
class NameTagsTest {
    private static final UUID ALEX = new UUID(0, 1);
    private static final UUID STEVE = new UUID(0, 2);
    private static final Component AFK = Component.text(" [AFK]", NamedTextColor.GRAY);

    private final NameTags tags = new NameTags();

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /** What the tablist renders for "[Admin] <displayname>" when the nick module set the display name. */
    private static Component nicknamedListName() {
        return Component.text("[Admin] ", NamedTextColor.RED).append(Component.text("~Alexander", NamedTextColor.WHITE));
    }

    @Test
    void noTagsKeepsTheRenderedNameAsIs() {
        Component name = nicknamedListName();
        assertSame(name, tags.compose(ALEX, name), "unchanged instance: no extra update is sent");
    }

    @Test
    void afkTagIsAddedOnceAroundTheNicknamedName() {
        assertTrue(tags.set(ALEX, "afk", Placement.AFTER, AFK));
        assertFalse(tags.set(ALEX, "afk", Placement.AFTER, AFK), "same tag again: nothing to re-render");
        assertEquals("[Admin] ~Alexander [AFK]", plain(tags.compose(ALEX, nicknamedListName())));
        assertEquals("Steve", plain(tags.compose(STEVE, Component.text("Steve"))), "other players untouched");
    }

    @Test
    void tagsOfSeveralSourcesAreOrderedBySourceAndPlacement() {
        tags.set(ALEX, "vanish", Placement.BEFORE, Component.text("[V] "));
        tags.set(ALEX, "afk", Placement.AFTER, AFK);
        tags.set(ALEX, "away", Placement.BEFORE, Component.text("[Z] "));
        assertEquals("[Z] [V] Alex [AFK]", plain(tags.compose(ALEX, Component.text("Alex"))));
    }

    @Test
    void removingTheTagRestoresThePlainName() {
        tags.set(ALEX, "afk", Placement.AFTER, AFK);
        assertTrue(tags.set(ALEX, "afk", Placement.AFTER, null));
        assertFalse(tags.set(ALEX, "afk", Placement.AFTER, Component.empty()), "already gone");
        Component name = nicknamedListName();
        assertSame(name, tags.compose(ALEX, name));
    }

    @Test
    void clearingASourceReportsAffectedPlayers() {
        tags.set(ALEX, "afk", Placement.AFTER, AFK);
        tags.set(STEVE, "afk", Placement.AFTER, AFK);
        tags.set(STEVE, "other", Placement.BEFORE, Component.text("* "));
        assertEquals(Set.of(ALEX, STEVE), tags.clear("afk"));
        assertEquals("* Steve", plain(tags.compose(STEVE, Component.text("Steve"))));
        tags.forget(STEVE);
        assertEquals("Steve", plain(tags.compose(STEVE, Component.text("Steve"))));
    }

    @Test
    void composedNameReachesTabAsOneNameWithoutTabSyntax() {
        tags.set(ALEX, "afk", Placement.AFTER, AFK);
        String forTab = TabText.toTab(tags.compose(ALEX, nicknamedListName()));
        // One name string for TAB's setName (prefix/suffix are ""): the tag is part of it, not a second prefix.
        assertTrue(forTab.endsWith("[AFK]"), forTab);
        assertTrue(forTab.contains("~Alexander"), forTab);
        assertEquals(1, forTab.split("\\[AFK]", -1).length - 1, "tag exactly once");
    }
}
