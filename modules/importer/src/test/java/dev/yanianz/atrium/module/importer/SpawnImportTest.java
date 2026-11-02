package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.SpawnAdminService.Kind;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Spawn import end to end: real-format source files → parser → plan → SpawnAdminService (fake). */
class SpawnImportTest {
    private static final UUID OVERWORLD = UUID.fromString("4d3c1b2a-9f8e-4a7b-8c6d-5e4f3a2b1c0d");
    private static final UUID NETHER = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final EssentialsLocations.WorldIndex WORLDS = new EssentialsLocations.WorldIndex(
            Map.of(OVERWORLD, "world", NETHER, "world_nether"), Set.of("world", "world_nether"));

    @TempDir
    Path dir;

    // ---- fakes ----

    /** Stores spawns like the teleport module: by kind (and world), with /spawn's per-world-first lookup. */
    static final class FakeSpawnAdmin implements SpawnAdminService {
        final Map<String, StoredLocation> stored = new LinkedHashMap<>();
        final List<String> writes = new ArrayList<>();
        boolean perWorld = true;

        private static String id(Kind kind, String world) {
            return kind == Kind.WORLD ? "world:" + world : kind.name();
        }

        @Override
        public Optional<StoredLocation> spawn(Kind kind, String world) {
            return Optional.ofNullable(stored.get(id(kind, world)));
        }

        @Override
        public CompletableFuture<Void> setSpawn(Kind kind, String world, StoredLocation location) {
            writes.add(id(kind, world));
            stored.put(id(kind, world), location);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean perWorldSpawns() {
            return perWorld;
        }

        /** Where /spawn sends a player standing in {@code world}. */
        StoredLocation effective(String world) {
            if (perWorld && stored.containsKey("world:" + world)) {
                return stored.get("world:" + world);
            }
            return stored.get("DEFAULT");
        }
    }

    /** Resolves world UUIDs with {@link #WORLDS}; a world given by name is always accepted. */
    static final class FakeEnv implements SpawnImport.Env {
        @Override
        public StoredLocation byName(StoredLocation location) {
            try {
                String name = WORLDS.byId().get(UUID.fromString(location.world()));
                return name == null ? location : new StoredLocation(name, location.x(), location.y(), location.z(),
                        location.yaw(), location.pitch());
            } catch (IllegalArgumentException notAUuid) {
                return location;
            }
        }

        @Override
        public String worldProblem(StoredLocation location) {
            try {
                UUID.fromString(location.world());
                return "world " + location.world() + " is not loaded and has no known name";
            } catch (IllegalArgumentException notAUuid) {
                return null;
            }
        }

        @Override
        public void awaitWrite(CompletableFuture<Void> write, String what) {
            write.join();
        }
    }

    private static ConfigurationNode yaml(String name) throws Exception {
        Path path = Path.of(SpawnImportTest.class.getResource("/fixtures/spawn/" + name).toURI());
        return YamlConfigurationLoader.builder().path(path).build().load();
    }

    private static ImportReport run(SpawnImport.Plan plan, FakeSpawnAdmin admin, boolean dryRun, boolean overwrite) {
        ImportReport report = new ImportReport();
        plan.notes().forEach(report::note);
        for (SpawnImport.Spawn spawn : plan.spawns()) {
            report.found(ImportCategory.SPAWN, 1);
            SpawnImport.apply(spawn, admin, report, dryRun, overwrite, new FakeEnv());
        }
        return report;
    }

    private static SpawnImport.Plan essentialsPlan(String spawnFile) throws Exception {
        String newbies = CmiNodeLocations.text(CmiNodeLocations.child(yaml("essentials-config.yml"), "newbies"),
                "spawnpoint");
        return EssentialsSpawnParser.plan(EssentialsSpawnParser.parse(yaml(spawnFile)), newbies, WORLDS);
    }

    private static void assertLocation(StoredLocation expected, StoredLocation actual) {
        assertEquals(expected.world(), actual.world());
        assertEquals(expected.x(), actual.x(), 1e-9);
        assertEquals(expected.y(), actual.y(), 1e-9);
        assertEquals(expected.z(), actual.z(), 1e-9);
        assertEquals(expected.yaw(), actual.yaw(), 1e-4, "yaw");
        assertEquals(expected.pitch(), actual.pitch(), 1e-4, "pitch");
    }

    // ---- EssentialsX ----

    @Test
    void essentialsSpawnYmlIsFoundInTheEssentialsFolder() throws Exception {
        // EssentialsSpawn writes spawn.yml into Essentials' own data folder (plugins/Essentials/spawn.yml).
        Path essentials = Files.createDirectories(dir.resolve("plugins/Essentials"));
        Files.writeString(essentials.resolve("spawn.yml"), "spawns: {}\n");
        assertEquals(essentials.toAbsolutePath(), EssentialsSpawnParser.spawnFolder(essentials, null));

        // An explicitly configured folder still wins, and a legacy plugins/EssentialsSpawn copy is still found.
        Path legacy = Files.createDirectories(dir.resolve("plugins/EssentialsSpawn"));
        Files.writeString(legacy.resolve("spawn.yml"), "spawns: {}\n");
        assertEquals(legacy.toAbsolutePath(), EssentialsSpawnParser.spawnFolder(essentials, legacy));
        Files.delete(essentials.resolve("spawn.yml"));
        assertEquals(legacy.toAbsolutePath(), EssentialsSpawnParser.spawnFolder(essentials, null));
        Files.delete(legacy.resolve("spawn.yml"));
        assertNull(EssentialsSpawnParser.spawnFolder(essentials, null));
    }

    @Test
    void essentialsImportsDefaultAndNewbieSpawnAndReportsGroups() throws Exception {
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        ImportReport report = run(essentialsPlan("essentials-spawn.yml"), admin, false, false);

        assertLocation(new StoredLocation("world", 12.5, 72, -340.5, -90f, 12.5f), admin.stored.get("DEFAULT"));
        assertLocation(new StoredLocation("world", 200.5, 80, 200.5, 45f, -5f), admin.stored.get("FIRST_JOIN"));
        var counters = report.counters(ImportCategory.SPAWN);
        assertEquals(3, counters.found, "default, newbies (as the first-join spawn), vip");
        assertEquals(2, counters.imported);
        assertEquals(1, counters.unsupported, "the other group is reported with its location, never dropped silently");
        assertTrue(report.issues(ImportCategory.SPAWN).stream()
                .anyMatch(i -> i.subject().equals("group vip") && i.message().contains("world_nether 10 65 10")));
    }

    @Test
    void essentialsWorldUuidWithoutNameIsReportedNotDropped() throws Exception {
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        ImportReport report = run(essentialsPlan("essentials-spawn-uuid-only.yml"), admin, false, false);
        assertLocation(new StoredLocation("world", -3.5, 64, 7.5, 90f, 30f), admin.stored.get("DEFAULT"));
        var counters = report.counters(ImportCategory.SPAWN);
        assertEquals(2, counters.found);
        assertEquals(1, counters.imported);
        assertEquals(1, counters.invalid);
        assertTrue(report.issues(ImportCategory.SPAWN).stream()
                .anyMatch(i -> i.message().contains("99999999-9999-4999-8999-999999999999")));
    }

    @Test
    void dryRunCountsLikeTheRealRunAndWritesNothing() throws Exception {
        FakeSpawnAdmin dry = new FakeSpawnAdmin();
        ImportReport dryReport = run(essentialsPlan("essentials-spawn.yml"), dry, true, false);
        FakeSpawnAdmin real = new FakeSpawnAdmin();
        ImportReport realReport = run(essentialsPlan("essentials-spawn.yml"), real, false, false);
        assertTrue(dry.writes.isEmpty());
        var a = dryReport.counters(ImportCategory.SPAWN);
        var b = realReport.counters(ImportCategory.SPAWN);
        assertEquals(List.of(b.found, b.imported, b.skipped, b.invalid, b.unsupported, b.failed),
                List.of(a.found, a.imported, a.skipped, a.invalid, a.unsupported, a.failed));
    }

    @Test
    void secondRunIsIdempotent() throws Exception {
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        run(essentialsPlan("essentials-spawn.yml"), admin, false, false);
        admin.writes.clear();
        ImportReport again = run(essentialsPlan("essentials-spawn.yml"), admin, false, false);
        assertTrue(admin.writes.isEmpty(), "a re-run writes nothing");
        assertEquals(2, again.counters(ImportCategory.SPAWN).skipped);
        assertTrue(again.reasons(ImportCategory.SPAWN).keySet().stream().anyMatch(r -> r.contains("already imported")),
                "an identical spawn is reported as already imported: " + again.reasons(ImportCategory.SPAWN));
    }

    @Test
    void importedSpawnIsWhatSpawnUsesEvenWithAnOlderPerWorldSpawn() throws Exception {
        // The admin ran /setspawn in "world" before importing (per-world spawns are on by default).
        StoredLocation old = new StoredLocation("world", 0.5, 64, 0.5, 0f, 0f);
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        admin.stored.put("world:world", old);

        ImportReport plain = run(essentialsPlan("essentials-spawn.yml"), admin, false, false);
        assertEquals(old, admin.effective("world"), "without --overwrite the existing spawn of 'world' is kept");
        assertTrue(plain.reasons(ImportCategory.SPAWN).keySet().stream().anyMatch(r -> r.contains("--overwrite")),
                "and the report says why the imported spawn is not used: " + plain.reasons(ImportCategory.SPAWN));
        assertEquals(1, plain.counters(ImportCategory.SPAWN).imported,
                "only the first-join spawn is new; the default spawn is shadowed and therefore skipped");

        run(essentialsPlan("essentials-spawn.yml"), admin, false, true);
        assertLocation(new StoredLocation("world", 12.5, 72, -340.5, -90f, 12.5f), admin.effective("world"));
        assertLocation(new StoredLocation("world", 12.5, 72, -340.5, -90f, 12.5f), admin.effective("world_the_end"));
    }

    // ---- CMI ----

    @Test
    void cmiImportsMainAndFirstSpawnAndReportsGroups() throws Exception {
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        ImportReport report = run(CmiSpawnParser.plan(CmiSpawnParser.parse(yaml("cmi-config.yml"))), admin, false, false);
        assertLocation(new StoredLocation("world", -15.5, 71, 24.5, -89.4f, 12.9f), admin.stored.get("DEFAULT"));
        assertLocation(new StoredLocation("world", 100.5, 70, -50.5, 180f, -10f), admin.stored.get("FIRST_JOIN"));
        var counters = report.counters(ImportCategory.SPAWN);
        assertEquals(3, counters.found, "main, first spawn and the vip group spawn");
        assertEquals(2, counters.imported);
        assertEquals(1, counters.unsupported);
        assertTrue(report.issues(ImportCategory.SPAWN).stream()
                .anyMatch(i -> i.subject().contains("vip") && i.message().contains("world_nether 10 65 10")));
    }

    @Test
    void cmiFirstSpawnThatWasNeverSetIsNotAnError() throws Exception {
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        ImportReport report = run(CmiSpawnParser.plan(CmiSpawnParser.parse(yaml("cmi-config-unset.yml"))), admin,
                true, false);
        var counters = report.counters(ImportCategory.SPAWN);
        assertEquals(1, counters.found);
        assertEquals(1, counters.imported);
        assertEquals(0, counters.invalid, "an empty FirstSpawn location means 'use the main spawn'");
    }

    @Test
    void cmiLegacyKeysKeepYawAndPitchApart() throws Exception {
        FakeSpawnAdmin admin = new FakeSpawnAdmin();
        ImportReport report = run(CmiSpawnParser.plan(CmiSpawnParser.parse(yaml("cmi-config-legacy.yml"))), admin,
                false, false);
        assertLocation(new StoredLocation("Theia", -1306.836024430177, 77, -692.5003481315823, -89.920135f,
                -4.8099575f), admin.stored.get("DEFAULT"));
        assertEquals(0, report.counters(ImportCategory.SPAWN).invalid, "FirstSpawn World: None means not set");
        assertEquals(List.of("DEFAULT"), admin.writes);
    }
}
