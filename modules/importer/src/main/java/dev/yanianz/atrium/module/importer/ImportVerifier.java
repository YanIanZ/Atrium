package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.homes.HomeService;
import dev.yanianz.atrium.api.kits.KitAdminService;
import dev.yanianz.atrium.api.moderation.ModerationImportService;
import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.api.world.WorldProviderRegistry;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * {@code /atrium-import verify essentials|cmi}: reads the source again and compares it with what Atrium has
 * now (spawns, warps, homes, balances, kit and jail names). Read-only. The comparisons are pure static methods,
 * so the rules are unit tested without a server; {@link #essentials} and {@link #cmi} only collect the data.
 */
final class ImportVerifier {
    /** One compared category: how many records the source has, how many match in Atrium. */
    record Check(String category, int source, int matched) {
        int mismatched() {
            return source - matched;
        }
    }

    record Mismatch(String category, String subject, String detail) {}

    static final class Result {
        final List<Check> checks = new ArrayList<>();
        final List<Mismatch> mismatches = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        void check(String category, int source, int matched) {
            checks.add(new Check(category, source, matched));
        }

        void mismatch(String category, String subject, String detail) {
            mismatches.add(new Mismatch(category, subject, detail));
        }
    }

    /** The Atrium side, as functions so tests can pass fakes. */
    record Atrium(@Nullable SpawnAdminService spawns, @Nullable WarpService warps, @Nullable HomeService homes,
            @Nullable EconomyService economy, Predicate<String> worldKnown) {}

    private static final long TIMEOUT_SECONDS = 15;
    private static final BigDecimal CENT = new BigDecimal("0.01");

    private final ModuleContext ctx;

    ImportVerifier(ModuleContext ctx) {
        this.ctx = ctx;
    }

    // ---- pure comparisons ----

    static boolean same(@Nullable StoredLocation a, @Nullable StoredLocation b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.world().equals(b.world()) && Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.y() - b.y()) < 1e-6
                && Math.abs(a.z() - b.z()) < 1e-6 && Math.abs(a.yaw() - b.yaw()) < 1e-3
                && Math.abs(a.pitch() - b.pitch()) < 1e-3;
    }

    static String describe(@Nullable StoredLocation location) {
        return location == null ? "nothing" : SpawnImport.describe(location);
    }

    /** Spawns of an import plan against the teleport module, including what /spawn would really use. */
    static void compareSpawns(SpawnImport.Plan plan, Atrium atrium, Result result) {
        int source = 0;
        int matched = 0;
        SpawnAdminService admin = atrium.spawns();
        for (SpawnImport.Spawn spawn : plan.spawns()) {
            if (spawn.problem() != null || spawn.location() == null) {
                result.mismatch("spawn", spawn.label(), "not importable: "
                        + (spawn.problem() == null ? "no location" : spawn.problem()));
                source++;
                continue;
            }
            source++;
            if (admin == null) {
                result.mismatch("spawn", spawn.label(), "teleport module is not enabled");
                continue;
            }
            StoredLocation expected = spawn.location();
            StoredLocation actual = spawn.group() != null ? admin.groupSpawns().get(spawn.group())
                    : admin.spawn(spawn.kind(), spawn.world()).orElse(null);
            if (!same(expected, actual)) {
                result.mismatch("spawn", spawn.label(), "source " + describe(expected) + ", Atrium " + describe(actual));
                continue;
            }
            if (!atrium.worldKnown().test(expected.world())) {
                result.mismatch("spawn", spawn.label(), "world '" + expected.world()
                        + "' is not loaded and no world manager knows it; /spawn cannot go there");
                continue;
            }
            if (spawn.group() == null && spawn.kind() == SpawnAdminService.Kind.DEFAULT && admin.perWorldSpawns()) {
                StoredLocation shadow = admin.worldSpawns().get(expected.world());
                if (shadow != null && !same(shadow, expected)) {
                    result.mismatch("spawn", spawn.label(), "the per-world spawn of '" + expected.world() + "' ("
                            + describe(shadow) + ") is used by /spawn there instead; remove it with /delspawn world "
                            + expected.world());
                    continue;
                }
            }
            matched++;
        }
        result.check("spawn", source, matched);
    }

    /** Warps by name (case-insensitive) and location. */
    static void compareWarps(Map<String, StoredLocation> source, Atrium atrium, Result result) {
        int matched = 0;
        WarpService warps = atrium.warps();
        Map<String, String> names = new HashMap<>();
        if (warps != null) {
            for (String name : warps.names()) {
                names.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        for (Map.Entry<String, StoredLocation> entry : source.entrySet()) {
            String atriumName = names.get(entry.getKey().toLowerCase(Locale.ROOT));
            if (warps == null || atriumName == null) {
                result.mismatch("warps", entry.getKey(), "missing in Atrium");
                continue;
            }
            StoredLocation actual = warps.location(atriumName).orElse(null);
            if (entry.getValue() != null && !same(entry.getValue(), actual)) {
                result.mismatch("warps", entry.getKey(), "source " + describe(entry.getValue()) + ", Atrium "
                        + describe(actual));
                continue;
            }
            matched++;
        }
        result.check("warps", source.size(), matched);
    }

    /** One player's source data: home names (only importable ones) and the balance. */
    record SourceUser(UUID uuid, String label, Set<String> homes, @Nullable BigDecimal balance) {}

    /** Homes and balances; the counters are per player. */
    static void compareUsers(List<SourceUser> users, Atrium atrium, Result result) throws Exception {
        int withHomes = 0;
        int homesMatched = 0;
        int homesTotal = 0;
        int withBalance = 0;
        int balanceMatched = 0;
        for (SourceUser user : users) {
            if (!user.homes().isEmpty()) {
                withHomes++;
                homesTotal += user.homes().size();
                if (atrium.homes() == null) {
                    result.mismatch("homes", user.label(), "homes module is not enabled");
                } else {
                    Set<String> actual = new HashSet<>();
                    for (HomeService.HomeInfo home : atrium.homes().homes(user.uuid()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        actual.add(home.name().toLowerCase(Locale.ROOT));
                    }
                    List<String> missing = user.homes().stream()
                            .filter(name -> !actual.contains(name.toLowerCase(Locale.ROOT))).sorted().toList();
                    if (missing.isEmpty()) {
                        homesMatched++;
                    } else {
                        result.mismatch("homes", user.label(), "missing in Atrium: " + String.join(", ", missing)
                                + " (" + actual.size() + " home(s) in Atrium, " + user.homes().size() + " in the source)");
                    }
                }
            }
            if (user.balance() != null) {
                withBalance++;
                if (atrium.economy() == null) {
                    result.mismatch("balances", user.label(), "economy module is not enabled");
                } else {
                    BigDecimal actual = atrium.economy().balance(user.uuid()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (actual != null && actual.subtract(user.balance()).abs().compareTo(CENT) < 0) {
                        balanceMatched++;
                    } else {
                        result.mismatch("balances", user.label(), "source " + user.balance().toPlainString()
                                + ", Atrium " + (actual == null ? "none" : actual.toPlainString()));
                    }
                }
            }
        }
        result.check("homes (players)", withHomes, homesMatched);
        result.notes.add("Homes compared: " + homesTotal + " home(s) of " + withHomes + " player(s)");
        result.check("balances", withBalance, balanceMatched);
    }

    /** Names that exist in the source but not in Atrium. */
    static void compareNames(String category, Collection<String> source, @Nullable Collection<String> atrium,
            Result result) {
        Set<String> present = new HashSet<>();
        if (atrium != null) {
            atrium.forEach(name -> present.add(name.toLowerCase(Locale.ROOT)));
        }
        int matched = 0;
        for (String name : source) {
            if (present.contains(name.toLowerCase(Locale.ROOT))) {
                matched++;
            } else {
                result.mismatch(category, name, atrium == null ? "module is not enabled" : "missing in Atrium");
            }
        }
        result.check(category, source.size(), matched);
    }

    // ---- data collection ----

    private Atrium atrium() {
        Optional<WorldProviderRegistry> registry = ctx.services().get(WorldProviderRegistry.class);
        return new Atrium(ctx.services().get(SpawnAdminService.class).orElse(null),
                ctx.services().get(WarpService.class).orElse(null),
                ctx.services().get(HomeService.class).orElse(null),
                ctx.services().get(EconomyService.class).orElse(null),
                world -> ctx.plugin().getServer().getWorld(world) != null
                        || registry.map(r -> r.find(world).isPresent()).orElse(false));
    }

    private EssentialsLocations.WorldIndex worlds() throws Exception {
        return ctx.scheduler().supplyGlobal(() -> {
            Map<UUID, String> byId = new HashMap<>();
            Set<String> names = new HashSet<>();
            for (World world : ctx.plugin().getServer().getWorlds()) {
                byId.put(world.getUID(), world.getName());
                names.add(world.getName());
            }
            return new EssentialsLocations.WorldIndex(Map.copyOf(byId), Set.copyOf(names));
        }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static ConfigurationNode yaml(Path file) throws Exception {
        return YamlConfigurationLoader.builder().path(file).build().load();
    }

    private static List<Path> ymlFiles(Path folder) throws Exception {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (var stream = Files.list(folder)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    CompletableFuture<Result> essentials(Path folder, @Nullable Path spawnFolder) {
        return ctx.scheduler().supplyIo(() -> {
            Result result = new Result();
            Atrium atrium = atrium();
            EssentialsLocations.WorldIndex worlds = worlds();
            ConfigurationNode config = Files.isRegularFile(folder.resolve("config.yml"))
                    ? yaml(folder.resolve("config.yml")) : null;
            // spawn
            Path spawnFile = spawnFolder == null ? null : spawnFolder.resolve("spawn.yml");
            if (spawnFile != null && Files.isRegularFile(spawnFile)) {
                String newbies = config == null ? null
                        : CmiNodeLocations.text(CmiNodeLocations.child(config, "newbies"), "spawnpoint");
                compareSpawns(EssentialsSpawnParser.plan(EssentialsSpawnParser.parse(yaml(spawnFile)), newbies, worlds),
                        atrium, result);
            } else {
                result.notes.add("No EssentialsSpawn spawn.yml found");
            }
            // warps
            Map<String, StoredLocation> warps = new LinkedHashMap<>();
            for (Path file : ymlFiles(folder.resolve("warps"))) {
                for (EssentialsWarpParser.ParsedWarp warp : EssentialsWarpParser.parse(file.getFileName().toString(), yaml(file))) {
                    if (warp.ok()) {
                        warps.put(warp.name(), worlds.resolve(warp.location()).orElse(null));
                    }
                }
            }
            compareWarps(warps, atrium, result);
            // users
            List<SourceUser> users = new ArrayList<>();
            for (Path file : ymlFiles(folder.resolve("userdata"))) {
                EssentialsUserParser.UserFile user = EssentialsUserParser.parse(file.getFileName().toString(), yaml(file));
                if (user.uuid() == null) {
                    continue;
                }
                Set<String> homes = new HashSet<>();
                for (EssentialsUserParser.RawHome home : user.homes()) {
                    if (home.ok() && worlds.resolve(home.location()).isPresent()) {
                        homes.add(home.name());
                    }
                }
                users.add(new SourceUser(user.uuid(), user.label(), homes, user.money()));
            }
            compareUsers(users, atrium, result);
            // kits and jails (names only)
            List<String> kits = new ArrayList<>();
            List<Path> kitFiles = new ArrayList<>();
            if (Files.isRegularFile(folder.resolve("kits.yml"))) {
                kitFiles.add(folder.resolve("kits.yml"));
            }
            kitFiles.addAll(ymlFiles(folder.resolve("kits")));
            for (Path file : kitFiles) {
                for (EssentialsKitsParser.ParsedKit kit : EssentialsKitsParser.parse(yaml(file))) {
                    if (kit.ok() && !kits.contains(kit.name().toLowerCase(Locale.ROOT))) {
                        kits.add(kit.name().toLowerCase(Locale.ROOT));
                    }
                }
            }
            compareNames("kits", kits, ctx.services().get(KitAdminService.class).map(KitAdminService::kitNames).orElse(null),
                    result);
            Path jails = Files.isRegularFile(folder.resolve("jail.yml")) ? folder.resolve("jail.yml") : folder.resolve("jails.yml");
            if (Files.isRegularFile(jails)) {
                List<String> names = EssentialsJailParser.parse(yaml(jails)).stream()
                        .filter(EssentialsJailParser.ParsedJail::ok).map(EssentialsJailParser.ParsedJail::name).toList();
                compareNames("jails", names, ctx.services().get(ModerationImportService.class)
                        .map(ModerationImportService::jailNames).orElse(null), result);
            }
            return result;
        });
    }

    CompletableFuture<Result> cmi(Path folder) {
        return ctx.scheduler().supplyIo(() -> {
            Result result = new Result();
            Atrium atrium = atrium();
            Path config = folder.resolve("config.yml");
            if (Files.isRegularFile(config)) {
                compareSpawns(CmiSpawnParser.plan(CmiSpawnParser.parse(yaml(config))), atrium, result);
            } else {
                result.notes.add("CMI config.yml not found; spawn not compared");
            }
            Path warpsFile = folder.resolve("Saves").resolve("Warps.yml");
            if (Files.isRegularFile(warpsFile)) {
                Map<String, StoredLocation> warps = new LinkedHashMap<>();
                for (CmiWarpFileParser.ParsedWarp warp : CmiWarpFileParser.parse(yaml(warpsFile))) {
                    if (warp.ok()) {
                        warps.put(warp.name(), warp.location());
                    }
                }
                compareWarps(warps, atrium, result);
            }
            CmiConnectionConfig connection = CmiConnectionConfig.read(folder);
            if (connection.kind() == CmiConnectionConfig.Kind.SQLITE
                    && (connection.sqliteFile() == null || !Files.isRegularFile(connection.sqliteFile()))) {
                result.notes.add("CMI database not found at " + connection.sqliteFile() + "; homes and balances not compared");
                return result;
            }
            List<SourceUser> users = new ArrayList<>();
            try (CmiDatabaseSource source = CmiDatabaseSource.open(connection)) {
                long total = source.userCount();
                for (int offset = 0; offset < total; offset += 500) {
                    List<CmiUserRecord> batch = source.users(offset, 500);
                    if (batch.isEmpty()) {
                        break;
                    }
                    for (CmiUserRecord user : batch) {
                        if (user.uuid() == null) {
                            continue;
                        }
                        Set<String> homes = new HashSet<>();
                        if (user.homes() != null && !user.homes().isBlank()) {
                            for (CmiHomeParser.ParsedHome home : CmiHomeParser.parse(user.homes())) {
                                if (home.ok()) {
                                    homes.add(home.name());
                                }
                            }
                        }
                        BigDecimal balance = user.economy() == null || user.economy().isBlank() ? null
                                : CmiBalanceParser.parse(user.economy()).orElse(null);
                        users.add(new SourceUser(user.uuid(), user.label(), homes, balance));
                    }
                }
            }
            compareUsers(users, atrium, result);
            return result;
        });
    }
}
