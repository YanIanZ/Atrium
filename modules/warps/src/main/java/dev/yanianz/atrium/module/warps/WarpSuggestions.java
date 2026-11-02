package dev.yanianz.atrium.module.warps;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Tab-completion values of the warp commands. Pure logic over a warp snapshot and a {@link Viewer}, so it is
 * safe on the thread that answers a tab completion and never touches the world, the database or the player.
 */
final class WarpSuggestions {
    private WarpSuggestions() {}

    /**
     * Who asks. {@code id} is {@code null} for the console and other non-player senders; {@code permissions}
     * answers {@code hasPermission}.
     */
    record Viewer(@Nullable UUID id, Predicate<String> permissions) {
        /** Sees everything (staff-only commands whose own permission already gates the argument). */
        static final Viewer ALL = new Viewer(null, permission -> true);

        boolean has(String permission) {
            return permissions.test(permission);
        }

        boolean owns(Warp warp) {
            return id != null && id.equals(warp.creator());
        }
    }

    /** Same rule as {@code /warp}: owners always, otherwise the per-warp permission when it is required. */
    static boolean canUse(Viewer viewer, Warp warp, boolean requirePerWarpPermission) {
        if (viewer.owns(warp)) {
            return true;
        }
        return !requirePerWarpPermission || viewer.has(WarpsModule.PERM_ALL) || viewer.has(warp.permission());
    }

    /** {@code /warp <name>}: the warps the viewer can use. */
    static List<String> usable(Collection<Warp> warps, Viewer viewer, boolean requirePerWarpPermission, String typed) {
        return names(warps.stream().filter(w -> canUse(viewer, w, requirePerWarpPermission)).map(Warp::name), typed);
    }

    /**
     * {@code /setwarp}, {@code /delwarp}, {@code /renamewarp}: the warps the viewer may change with that
     * command. {@code adminPermission} is the command's own staff node ({@code atrium.setwarp} or
     * {@code atrium.delwarp}), the same one {@link WarpsModule#mayManage} checks when the command runs.
     */
    static List<String> manageable(Collection<Warp> warps, Viewer viewer, String adminPermission, String typed) {
        boolean staff = viewer.has(adminPermission);
        boolean creator = viewer.has(WarpsModule.PERM_CREATE);
        return names(warps.stream()
                .filter(w -> WarpEditPolicy.mayManage(staff, creator, viewer.owns(w)))
                .map(Warp::name), typed);
    }

    /**
     * {@code /warps <category>} and {@code /warpgroups <group>}: groups with at least one warp the viewer can
     * use (the same groups {@code /warpgroups} lists), once each even when warps spell them differently.
     */
    static List<String> groups(Collection<Warp> warps, Viewer viewer, boolean requirePerWarpPermission, String typed) {
        return names(warps.stream().filter(w -> canUse(viewer, w, requirePerWarpPermission)).map(Warp::category),
                typed);
    }

    /** Category argument of {@code /setwarp} and {@code /editwarp <warp> category}: every category plus the default. */
    static List<String> categories(Collection<Warp> warps, String defaultCategory, String typed) {
        Stream<String> existing = warps.stream().map(Warp::category);
        return names(defaultCategory == null || defaultCategory.isBlank() ? existing
                : Stream.concat(existing, Stream.of(defaultCategory.strip())), typed);
    }

    /** Distinct (case-insensitive, first spelling wins), prefix-filtered (case-insensitive), sorted. */
    private static List<String> names(Stream<String> names, @Nullable String typed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        Map<String, String> unique = new TreeMap<>();
        names.forEach(name -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (key.startsWith(prefix)) {
                unique.putIfAbsent(key, name);
            }
        });
        return List.copyOf(unique.values());
    }
}
