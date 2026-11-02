package dev.yanianz.atrium.module.scoreboard;

import java.util.Locale;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/**
 * What the TAB sidebar backend does for one player on a flush. Pure, so the exclusivity rules are tested without
 * TAB.
 *
 * <p>When Atrium shows a sidebar through TAB, TAB's own default scoreboards must never appear instead of (or
 * after) it: Atrium's per-player scoreboard is TAB's custom scoreboard of the player, and TAB's scoreboard
 * visibility is switched on if something (TAB's join handling, its toggle command) turned it off. When Atrium
 * shows no sidebar (no matching board, or {@code /sidebar} off), {@link DefaultMode} decides:
 * <ul>
 *   <li>{@link DefaultMode#HIDE}: TAB's scoreboards are hidden for that player (TAB's visibility off); TAB's
 *       defaults do not take the slot;</li>
 *   <li>{@link DefaultMode#SHOW}: Atrium's custom scoreboard is reset, so TAB's own scoreboards take the slot.</li>
 * </ul>
 */
final class TabSidebarSync {
    /** {@code tab-default-scoreboard} in scoreboard.yml. */
    enum DefaultMode {
        HIDE,
        SHOW;

        static DefaultMode parse(@Nullable String raw, BiConsumer<String, String> problems) {
            if (raw == null || raw.isBlank()) {
                return HIDE;
            }
            return switch (raw.strip().toLowerCase(Locale.ROOT)) {
                case "hide", "hidden", "false", "off" -> HIDE;
                case "show", "shown", "true", "on" -> SHOW;
                default -> {
                    problems.accept("tab-default-scoreboard", "'" + raw + "' is not hide or show; using hide");
                    yield HIDE;
                }
            };
        }
    }

    /**
     * TAB calls for one player; all false/null means nothing to do.
     *
     * @param show    show Atrium's scoreboard as the player's custom scoreboard
     * @param visible change TAB's scoreboard visibility to this value, or leave it ({@code null})
     * @param reset   reset the custom scoreboard, so TAB's own scoreboards take the slot back
     */
    record Step(boolean show, @Nullable Boolean visible, boolean reset) {
        static final Step NONE = new Step(false, null, false);

        boolean none() {
            return !show && visible == null && !reset;
        }
    }

    /** Who decides about TAB's own default scoreboards, for {@code /sidebar engine}. */
    enum Defaults {
        /** TAB's scoreboard feature is off or TAB is missing: there are none. */
        NOT_RUNNING("not-running"),
        /** Atrium shows its sidebar through TAB and hides TAB's own while it shows none. */
        HIDDEN_BY_ATRIUM("hidden-by-atrium"),
        /** Atrium shows its sidebar through TAB; TAB's own take the slot while Atrium shows none. */
        SHOWN_WHEN_ATRIUM_HIDES("shown-when-atrium-hides"),
        /** TAB draws its scoreboards while Atrium draws its own sidebar outside TAB: two sources fight. */
        CONFLICT("conflict");

        final String key;

        Defaults(String key) {
            this.key = key;
        }
    }

    private TabSidebarSync() {}

    /**
     * @param backend       the active sidebar backend
     * @param tabScoreboard TAB runs its scoreboard feature
     */
    static Defaults defaults(SidebarBackend.Kind backend, boolean tabScoreboard, DefaultMode mode) {
        if (!tabScoreboard) {
            return Defaults.NOT_RUNNING;
        }
        if (backend == SidebarBackend.Kind.TAB) {
            return mode == DefaultMode.HIDE ? Defaults.HIDDEN_BY_ATRIUM : Defaults.SHOWN_WHEN_ATRIUM_HIDES;
        }
        // NONE draws nothing itself, so only TAB's scoreboards show: no fight, but not Atrium's either.
        return backend == SidebarBackend.Kind.NONE ? Defaults.SHOWN_WHEN_ATRIUM_HIDES : Defaults.CONFLICT;
    }

    /**
     * @param wanted         Atrium shows a sidebar to this player
     * @param mode           what happens to TAB's own scoreboards while Atrium shows none
     * @param forced         Atrium's scoreboard is (or is about to be) the player's custom scoreboard in TAB
     * @param visible        TAB currently shows scoreboards to this player
     * @param hiddenByAtrium Atrium itself turned TAB's scoreboards off for this player
     */
    static Step plan(boolean wanted, DefaultMode mode, boolean forced, boolean visible, boolean hiddenByAtrium) {
        if (wanted) {
            // Atrium's board is the custom one, and visible: TAB's defaults never show instead of it.
            return new Step(!forced, visible ? null : Boolean.TRUE, false);
        }
        if (mode == DefaultMode.HIDE) {
            // Keep Atrium's (stale) board as the custom one, so a re-enabled visibility never reveals TAB's own.
            return visible ? new Step(false, Boolean.FALSE, false) : Step.NONE;
        }
        // SHOW: hand the slot back to TAB's own scoreboards, undoing a visibility Atrium switched off.
        return new Step(false, hiddenByAtrium && !visible ? Boolean.TRUE : null, forced);
    }
}
