package dev.yanianz.atrium.module.tablist;

import org.jspecify.annotations.Nullable;

/**
 * The exact values Atrium hands to TAB's API for one player. Pure, so the empty-versus-null rules are tested.
 *
 * <p>TAB's setters (5.x and 6.x) read {@code null} as "remove the API override and show TAB's configured value"
 * and any string, including {@code ""}, as an override. So:
 * <ul>
 *   <li>header and footer are never {@code null} while Atrium owns them: an empty Atrium header is {@code ""},
 *       which hides TAB's header instead of falling back to it;</li>
 *   <li>when Atrium manages list names, the whole name is Atrium's: prefix and suffix are {@code ""} (TAB's group
 *       prefix and suffix would otherwise be drawn around Atrium's name, doubling the prefix) and the name is
 *       Atrium's rendered list name;</li>
 *   <li>when Atrium does not manage list names, all three are {@code null}: TAB's own formatting.</li>
 * </ul>
 */
record TabOverrides(String header, String footer, @Nullable String prefix, @Nullable String name,
        @Nullable String suffix) {
    /** Clears every list name override; header and footer are reset separately. */
    static final String NO_OVERRIDE = null;

    static TabOverrides of(TabBridge.Frame frame) {
        String header = frame.header() == null ? "" : frame.header();
        String footer = frame.footer() == null ? "" : frame.footer();
        if (frame.listName() == null) {
            return new TabOverrides(header, footer, NO_OVERRIDE, NO_OVERRIDE, NO_OVERRIDE);
        }
        return new TabOverrides(header, footer, "", frame.listName(), "");
    }

    boolean managesListName() {
        return name != null;
    }
}
