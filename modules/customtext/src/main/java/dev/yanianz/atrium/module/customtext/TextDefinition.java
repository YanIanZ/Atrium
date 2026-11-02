package dev.yanianz.atrium.module.customtext;

import java.util.List;
import java.util.Objects;

/**
 * One admin text: the file name is the id, {@code pages} holds one list of MiniMessage lines per page.
 *
 * <p>Lines are trusted admin templates. {@code <player>}, {@code <online>} and {@code <world>} are inserted
 * as literal values, and PlaceholderAPI tokens are resolved before MiniMessage with their values escaped, so
 * a player-derived value can never inject tags.
 *
 * @param name           file name without {@code .yml}
 * @param command        optional command alias, empty when the text is only reachable through {@code /ctext}
 * @param permission     optional permission node needed to see the text, empty for everyone
 * @param join           show the first page on join
 * @param firstJoinOnly  with {@code join}: only show it when the player joins for the first time
 * @param cooldownSeconds seconds before the text can be opened again without an explicit page (0 disables)
 * @param pages          one list of MiniMessage lines per page; never empty
 */
record TextDefinition(
        String name,
        String command,
        String permission,
        boolean join,
        boolean firstJoinOnly,
        int cooldownSeconds,
        List<List<String>> pages) {

    TextDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(pages, "pages");
        pages = pages.stream().map(List::copyOf).toList();
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("a text needs at least one page");
        }
        if (cooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldownSeconds must not be negative");
        }
    }

    int pageCount() {
        return pages.size();
    }
}
