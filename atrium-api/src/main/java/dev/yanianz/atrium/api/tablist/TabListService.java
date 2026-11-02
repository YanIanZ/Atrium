package dev.yanianz.atrium.api.tablist;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * The tab list as managed by the {@code tablist} module. Published while that module is enabled. Safe to call from
 * any thread.
 *
 * <p>The tablist module owns every player's tab list name, whichever engine shows it. Other features never set a
 * list name themselves ({@code Player#playerListName}): with the {@code tab} engine the TAB plugin draws the list
 * and would either overwrite such a name or show it next to its own. Instead they register a <em>name tag</em>
 * here (for example {@code [AFK]}) and the tablist module adds it to the name it renders, on every engine.
 */
public interface TabListService {
    /** Which plugin draws the tab list. */
    enum Engine {
        /** Atrium sends the header, footer, list names and order itself. */
        NATIVE,
        /** The TAB plugin draws the tab list; Atrium hands it every text through TAB's API. */
        TAB
    }

    /** Where a name tag goes relative to the rendered list name. */
    enum Placement {
        BEFORE,
        AFTER
    }

    /** The engine that currently shows the tab list. */
    Engine engine();

    /** True when another plugin draws the tab list ({@link Engine#TAB}): never touch list names or teams then. */
    default boolean isExternal() {
        return engine() != Engine.NATIVE;
    }

    /**
     * Adds, replaces or (with a {@code null} or empty {@code tag}) removes the tag {@code source} contributes to a
     * player's list name. Tags of several sources are ordered by source id. The player is re-rendered shortly
     * after. Tags are dropped when the player leaves.
     *
     * @param source short id of the feature, for example {@code "afk"}
     * @param tag    the text to add, including any spacing; trusted, already rendered text
     */
    void setNameTag(UUID player, String source, Placement placement, @Nullable Component tag);

    /** Removes every tag of {@code source} (for example when that module is disabled). */
    void clearNameTags(String source);

    /**
     * Re-renders one player's tab list entry soon (within a tick or two) instead of waiting for the next update,
     * for example after a nickname, group or vanish change. Does nothing for an offline player.
     */
    void requestRender(UUID player);
}
