package dev.yanianz.atrium.module.customtext;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One admin text: the file name is the id, {@code pages} holds one list of MiniMessage lines per page.
 *
 * <p>Lines are trusted admin templates. {@code <player>}, {@code <online>} and {@code <world>} are inserted
 * as literal values, and PlaceholderAPI tokens are resolved before MiniMessage with their values escaped, so
 * a player-derived value can never inject tags.
 *
 * @param name           file name without {@code .yml}
 * @param command        optional command alias, empty when the text is only reachable through {@code /ctext}
 * @param permissions    every node the viewer needs (empty = everyone); {@code <ReqPermission>} adds to the
 *                       {@code permission} key instead of replacing it
 * @param join           show the first page on join
 * @param firstJoinOnly  with {@code join}: only show it when the player joins for the first time
 * @param cooldownSeconds seconds before the text can be opened again without an explicit page (0 disables)
 * @param hidden         with the {@code <Hidden>} directive: not listed and not sent on join
 * @param pages          one list of MiniMessage lines per page; never empty
 */
record TextDefinition(
        String name,
        String command,
        List<String> permissions,
        boolean join,
        boolean firstJoinOnly,
        int cooldownSeconds,
        boolean hidden,
        List<List<String>> pages) {

    TextDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(pages, "pages");
        permissions = List.copyOf(permissions);
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

    /** The first required permission, for messages; empty when the text is open to everyone. */
    String permission() {
        return permissions.isEmpty() ? "" : permissions.getFirst();
    }

    /** Every required permission, comma separated, for messages. */
    String permissionLabel() {
        return String.join(", ", permissions);
    }

    boolean maySee(Predicate<String> hasPermission) {
        return permissions.stream().allMatch(hasPermission);
    }
}
