package dev.yanianz.atrium.module.inspect;

import java.util.List;

/** A permission answer from Bukkit (online) or LuckPerms (offline/online). */
record PermissionResult(boolean value, boolean explicit, String source, List<String> details) {
    PermissionResult {
        source = source == null ? "" : source;
        details = List.copyOf(details);
    }
}
