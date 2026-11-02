package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Plans and writes imported spawn points through {@link SpawnAdminService}. Everything server-specific (world
 * lookup, waiting for a write) comes in through {@link Env}, so tests run the whole path with fakes.
 */
final class SpawnImport {
    private SpawnImport() {}

    /**
     * One spawn point of the source. {@code problem} marks a point that cannot be imported (counted invalid);
     * {@code unsupported} a point Atrium has no slot for (per-group spawns), reported with its location.
     */
    record Spawn(String label, SpawnAdminService.Kind kind, @Nullable String world, @Nullable StoredLocation location,
            @Nullable String problem, @Nullable String unsupported, @Nullable String group) {
        Spawn(String label, SpawnAdminService.Kind kind, @Nullable String world, @Nullable StoredLocation location,
                @Nullable String problem, @Nullable String unsupported) {
            this(label, kind, world, location, problem, unsupported, null);
        }

        static Spawn ok(String label, SpawnAdminService.Kind kind, StoredLocation location) {
            return new Spawn(label, kind, null, location, null, null);
        }

        /** A group spawn ({@code /spawn} uses it for players with {@code atrium.spawn.group.<group>}). */
        static Spawn group(String label, String group, StoredLocation location) {
            return new Spawn(label, SpawnAdminService.Kind.DEFAULT, null, location, null, null,
                    group.trim().toLowerCase(Locale.ROOT));
        }

        static Spawn invalid(String label, SpawnAdminService.Kind kind, String problem) {
            return new Spawn(label, kind, null, null, problem, null);
        }

        static Spawn unsupported(String label, StoredLocation location, String reason) {
            return new Spawn(label, SpawnAdminService.Kind.DEFAULT, null, location, null, reason);
        }
    }

    /** The spawn points of one source plus notes for the report (things that are deliberately not imported). */
    record Plan(List<Spawn> spawns, List<String> notes) {
        Plan {
            spawns = List.copyOf(spawns);
            notes = List.copyOf(notes);
        }
    }

    /** What the job provides: world-name resolution, the world check and a blocking write. */
    interface Env {
        /** A loaded world's UUID becomes its name; a name is kept even when that world is not loaded. */
        StoredLocation byName(StoredLocation location);

        /** Why the world cannot be used, or {@code null}. */
        @Nullable String worldProblem(StoredLocation location);

        /** Waits for the write; throws with a readable message when it failed. */
        void awaitWrite(CompletableFuture<Void> write, String what);
    }

    static void apply(Spawn spawn, @Nullable SpawnAdminService admin, ImportReport report, boolean dryRun,
            boolean overwrite, Env env) {
        if (spawn.problem() != null) {
            report.invalid(ImportCategory.SPAWN, spawn.label(), spawn.problem());
            return;
        }
        StoredLocation location = env.byName(spawn.location());
        if (spawn.unsupported() != null) {
            report.unsupported(ImportCategory.SPAWN, 1, spawn.unsupported());
            report.issue(new ImportIssue(ImportCategory.SPAWN, spawn.label(), "not imported (" + spawn.unsupported()
                    + "); it is at " + describe(location)));
            return;
        }
        String problem = env.worldProblem(location);
        if (problem != null) {
            report.invalid(ImportCategory.SPAWN, spawn.label(), problem);
            return;
        }
        if (admin == null) {
            report.unsupported(ImportCategory.SPAWN, 1, "teleport module (SpawnAdminService) not available");
            return;
        }
        if (spawn.group() != null) {
            applyGroup(spawn, location, admin, report, dryRun, overwrite, env);
            return;
        }
        StoredLocation existing = admin.spawn(spawn.kind(), spawn.world()).orElse(null);
        // With per-world spawns on, /spawn in a world with its own spawn ignores the default spawn. The imported
        // default spawn must therefore also replace the per-world spawn of its own world, or /spawn would keep
        // sending players to the old point while the report says the spawn was imported.
        StoredLocation shadow = spawn.kind() == SpawnAdminService.Kind.DEFAULT && admin.perWorldSpawns()
                ? admin.spawn(SpawnAdminService.Kind.WORLD, location.world()).orElse(null) : null;
        boolean existingDiffers = existing != null && !existing.equals(location);
        boolean shadowDiffers = shadow != null && !shadow.equals(location);
        if (existing != null && !existingDiffers && !shadowDiffers) {
            report.skipped(ImportCategory.SPAWN, 1, "already imported (same location)");
            return;
        }
        if (!overwrite && existingDiffers) {
            report.skipped(ImportCategory.SPAWN, 1, "Atrium already has this spawn set (use --overwrite)");
            return;
        }
        if (!overwrite && shadowDiffers) {
            report.skipped(ImportCategory.SPAWN, 1, "the per-world spawn of the spawn's world would still be used by "
                    + "/spawn there (use --overwrite to replace it as well)");
            report.issue(new ImportIssue(ImportCategory.SPAWN, spawn.label(), "not imported: world '"
                    + location.world() + "' has its own spawn at " + describe(shadow) + "; the imported spawn is at "
                    + describe(location)));
            return;
        }
        if (dryRun) {
            report.imported(ImportCategory.SPAWN, 1);
            return;
        }
        try {
            if (existing == null || existingDiffers) {
                env.awaitWrite(admin.setSpawn(spawn.kind(), spawn.world(), location), "spawn " + spawn.label());
            }
            if (shadowDiffers) {
                env.awaitWrite(admin.setSpawn(SpawnAdminService.Kind.WORLD, location.world(), location),
                        "spawn " + spawn.label() + " (world " + location.world() + ")");
            }
            report.imported(ImportCategory.SPAWN, 1);
            report.note("Spawn '" + spawn.label() + "' set to " + describe(location)
                    + (shadowDiffers ? " (also replaced the per-world spawn of '" + location.world() + "')" : ""));
        } catch (RuntimeException failure) {
            report.failed(ImportCategory.SPAWN, spawn.label(), failure.getMessage());
        }
    }

    private static void applyGroup(Spawn spawn, StoredLocation location, SpawnAdminService admin, ImportReport report,
            boolean dryRun, boolean overwrite, Env env) {
        String group = spawn.group();
        StoredLocation existing = admin.groupSpawns().get(group);
        if (location.equals(existing)) {
            report.skipped(ImportCategory.SPAWN, 1, "already imported (same location)");
            return;
        }
        if (existing != null && !overwrite) {
            report.skipped(ImportCategory.SPAWN, 1, "Atrium already has this spawn set (use --overwrite)");
            return;
        }
        if (dryRun) {
            report.imported(ImportCategory.SPAWN, 1);
            return;
        }
        try {
            env.awaitWrite(admin.setGroupSpawn(group, location), "spawn " + spawn.label());
            report.imported(ImportCategory.SPAWN, 1);
            report.note("Group spawn '" + group + "' stored at " + describe(location) + " (used only with "
                    + "spawn.group-spawns: true, for players with atrium.spawn.group." + group + ")");
        } catch (RuntimeException failure) {
            if (rootCause(failure) instanceof UnsupportedOperationException) {
                report.unsupported(ImportCategory.SPAWN, 1, "group spawns need a newer teleport module");
                report.issue(new ImportIssue(ImportCategory.SPAWN, spawn.label(), "not imported (group spawns are not "
                        + "supported by the teleport module); it is at " + describe(location)));
                return;
            }
            report.failed(ImportCategory.SPAWN, spawn.label(), failure.getMessage());
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    static String describe(@Nullable StoredLocation location) {
        if (location == null) {
            return "an unknown location";
        }
        return location.world() + " " + location.blockX() + " " + location.blockY() + " " + location.blockZ();
    }
}
