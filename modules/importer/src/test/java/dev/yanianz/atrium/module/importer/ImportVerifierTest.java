package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.homes.HomeService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.warps.WarpService;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** /atrium-import verify: source data against fake Atrium services, after a (fake) import of the same data. */
class ImportVerifierTest {
    private static final UUID WORLD = UUID.fromString("89050808-64e9-4f0b-bfaf-883cebd1ad0c");
    private static final EssentialsLocations.WorldIndex WORLDS =
            new EssentialsLocations.WorldIndex(Map.of(WORLD, "world"), Set.of("world"));

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, Map<String, java.util.function.Function<Object[], Object>> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            var handler = methods.get(method.getName());
            if (handler == null) {
                throw new UnsupportedOperationException(method.getName());
            }
            return handler.apply(args);
        });
    }

    private static SpawnImport.Plan plan() throws Exception {
        Path file = Path.of(ImportVerifierTest.class.getResource("/fixtures/spawn/essentials-2.22-spawn.yml").toURI());
        return EssentialsSpawnParser.plan(EssentialsSpawnParser.parse(YamlConfigurationLoader.builder().path(file).build().load()),
                "newbies", WORLDS);
    }

    @Test
    void spawnsMatchAfterTheImportAndAShadowingWorldSpawnIsReported() throws Exception {
        SpawnImportTest.FakeSpawnAdmin admin = new SpawnImportTest.FakeSpawnAdmin();
        ImportReport report = new ImportReport();
        for (SpawnImport.Spawn spawn : plan().spawns()) {
            SpawnImport.apply(spawn, admin, report, false, false, new SpawnImportTest.FakeEnv());
        }
        ImportVerifier.Atrium atrium = new ImportVerifier.Atrium(admin, null, null, null, world -> true);
        ImportVerifier.Result ok = new ImportVerifier.Result();
        ImportVerifier.compareSpawns(plan(), atrium, ok);
        assertEquals(List.of(new ImportVerifier.Check("spawn", 3, 3)), ok.checks);

        // An admin later runs "/setspawn world" in the main spawn's world: /spawn there no longer uses the import.
        admin.stored.put("world:world", new StoredLocation("world", 0, 64, 0, 0f, 0f));
        ImportVerifier.Result shadowed = new ImportVerifier.Result();
        ImportVerifier.compareSpawns(plan(), new ImportVerifier.Atrium(admin, null, null, null, w -> true), shadowed);
        assertEquals(1, shadowed.mismatches.size());
        assertTrue(shadowed.mismatches.getFirst().detail().contains("/delspawn world world"));

        // A world nobody can load is a mismatch too: /spawn could not go there.
        ImportVerifier.Result unknown = new ImportVerifier.Result();
        admin.stored.remove("world:world");
        ImportVerifier.compareSpawns(plan(), new ImportVerifier.Atrium(admin, null, null, null, w -> false), unknown);
        assertEquals(3, unknown.mismatches.size());
    }

    @Test
    void warpsHomesAndBalances() throws Exception {
        StoredLocation shop = new StoredLocation("world", 100.5, -60, 100.5, 180f, 0f);
        WarpService warps = fake(WarpService.class, Map.of(
                "names", args -> List.of("Shop"),
                "location", args -> "Shop".equals(args[0]) ? Optional.of(shop) : Optional.empty()));
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        HomeService homes = fake(HomeService.class, Map.of("homes", args -> CompletableFuture.completedFuture(
                args[0].equals(alice) ? List.of(new HomeService.HomeInfo("home", shop, 0L)) : List.of())));
        EconomyService economy = fake(EconomyService.class, Map.of("balance", args -> CompletableFuture.completedFuture(
                args[0].equals(alice) ? new BigDecimal("1234.56") : new BigDecimal("1.00"))));
        ImportVerifier.Atrium atrium = new ImportVerifier.Atrium(null, warps, homes, economy, w -> true);

        ImportVerifier.Result result = new ImportVerifier.Result();
        Map<String, StoredLocation> source = new LinkedHashMap<>();
        source.put("shop", shop);
        source.put("mine", shop);
        ImportVerifier.compareWarps(source, atrium, result);
        ImportVerifier.compareUsers(List.of(
                new ImportVerifier.SourceUser(alice, "Alice", Set.of("home"), new BigDecimal("1234.560")),
                new ImportVerifier.SourceUser(bob, "Bob", Set.of("base"), new BigDecimal("109.75"))), atrium, result);
        assertEquals(List.of(new ImportVerifier.Check("warps", 2, 1), new ImportVerifier.Check("homes (players)", 2, 1),
                new ImportVerifier.Check("balances", 2, 1)), result.checks);
        assertEquals(List.of("mine", "Bob", "Bob"), result.mismatches.stream().map(ImportVerifier.Mismatch::subject).toList());
    }
}
