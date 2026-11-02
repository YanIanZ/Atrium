package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;

/**
 * Imports CMI's database and files: users (money, homes, nicknames, playtime, last location, mutes) from
 * the database, and warps, holograms, spawn points, kits and jails from the files. The server's ban lists
 * (which CMI uses for bans) are imported under the ledger source {@code vanilla}. This class only reads
 * CMI's own shape; writing, rollback, progress and reporting come from {@link ImportJob} and
 * {@link ExtendedImportJob}.
 */
final class CmiImportJob extends ExtendedImportJob {
    private final Path cmiFolder;
    private final HologramWrites hologramWrites = new HologramWrites();
    private boolean hologramsMissingNoted;

    CmiImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender, Path cmiFolder) {
        super(ctx, config, options, sender);
        this.cmiFolder = cmiFolder;
    }

    @Override
    protected String sourceName() {
        return "CMI";
    }

    @Override
    protected String reportPrefix() {
        return "cmi";
    }

    @Override
    protected void run() throws Exception {
        // Files first: jails must exist before jail sentences, kits before players log in.
        if (options.includes(ImportCategory.WARPS)) {
            importWarps();
        }
        if (options.includes(ImportCategory.HOLOGRAMS)) {
            importHolograms();
        }
        if (options.includes(ImportCategory.SPAWN) || options.includes(ImportCategory.KITS)) {
            readConfig();
        }
        if (options.includes(ImportCategory.SPAWN) && cmiConfig != null) {
            importSpawns(spawnRecords(CmiSpawnParser.parse(cmiConfig)));
        }
        if (options.includes(ImportCategory.KITS)) {
            importKits();
        }
        if (options.includes(ImportCategory.JAILS)) {
            importJails();
        }
        boolean needsUsers = options.includes(ImportCategory.ECONOMY) || options.includes(ImportCategory.HOMES)
                || options.includes(ImportCategory.NICKS) || options.includes(ImportCategory.PLAYTIME)
                || options.includes(ImportCategory.LOCATIONS) || options.includes(ImportCategory.PUNISHMENTS)
                || options.includes(ImportCategory.IGNORE);
        if (needsUsers) {
            CmiConnectionConfig connection = CmiConnectionConfig.read(cmiFolder);
            sourceDescription = connection.describe();
            report.note("Source: " + sourceDescription);
            if (connection.kind() == CmiConnectionConfig.Kind.SQLITE
                    && (connection.sqliteFile() == null || !Files.isRegularFile(connection.sqliteFile()))) {
                // Only files were copied over (or CMI never used SQLite): the file categories above are done,
                // the per-player ones cannot be read. Not an internal error, so no stack trace.
                String problem = "CMI SQLite database not found at " + connection.sqliteFile()
                        + "; player data (economy, homes, nicks, playtime, last location, mutes) was not read."
                        + " Copy CMI's database file, or configure CMI's MySQL connection in config.yml";
                ctx.logger().warn(problem);
                report.failed(ImportCategory.SOURCE, "database", problem);
            } else {
                try (CmiDatabaseSource source = CmiDatabaseSource.open(connection)) {
                    noteSourceShape(source);
                    importUsers(source);
                }
            }
        } else {
            sourceDescription = cmiFolder.toString();
            report.note("Source: " + sourceDescription
                    + " (database not opened: no user category was selected)");
        }
        if (options.includes(ImportCategory.PUNISHMENTS) && !isCancelled()) {
            importVanillaBans(serverFolder());
        }
    }

    // ---- config.yml: spawn, warp permission default, first-join kit ----

    private org.spongepowered.configurate.ConfigurationNode cmiConfig;

    private void readConfig() {
        Path file = findFile(cmiFolder, "config.yml", "config.yaml");
        if (file == null) {
            report.note("CMI config.yml not found; no spawn points to import");
            return;
        }
        try {
            cmiConfig = loadYaml(file);
        } catch (Exception e) {
            ctx.logger().error("Reading {} failed", file, e);
            report.failed(ImportCategory.SPAWN, "config.yml", "cannot read file: " + describe(e));
        }
    }

    private List<SpawnRecord> spawnRecords(CmiSpawnParser.Result parsed) {
        List<SpawnRecord> out = new ArrayList<>();
        for (CmiSpawnParser.ParsedSpawn spawn : parsed.spawns()) {
            out.add(new SpawnRecord(spawn.label(), spawn.kind(), null, spawn.location(), spawn.problem(), null));
        }
        if (out.isEmpty()) {
            report.note("CMI config.yml has no Spawn.Main or Spawn.FirstSpawn location");
        }
        return out;
    }

    private void noteSourceShape(CmiDatabaseSource source) {
        report.note("Columns: homes=" + yesNo(source.hasHomesColumn())
                + ", nickname=" + yesNo(source.hasNickColumn())
                + ", economy=" + yesNo(source.hasEconomyColumn())
                + ", playtime_table=" + yesNo(source.hasPlaytimeTable())
                + ", last_location=" + (source.locationColumn() == null ? "no" : source.locationColumn())
                + ", mute=" + (source.muteColumn() == null ? "no" : source.muteColumn())
                + ", ignore=" + (source.ignoreColumn() == null ? "no" : source.ignoreColumn()));
        if (options.includes(ImportCategory.PUNISHMENTS) && source.muteColumn() == null) {
            noteOnce("cmi-mutes", "No mute column found in the CMI users table: CMI mutes are not imported "
                    + "(re-apply them with /mute). CMI bans are read from the server's ban lists.");
        }
        if (options.includes(ImportCategory.IGNORE) && source.ignoreColumn() == null) {
            noteOnce("cmi-ignores", "No ignore column found in the CMI users table: ignore lists are not imported.");
        }
    }

    // ---- users ----

    private void importUsers(CmiDatabaseSource source) throws SQLException {
        int total = Math.toIntExact(Math.min(Integer.MAX_VALUE, source.userCount()));
        report.note("CMI users: " + total);
        beginPhase("users", total);
        int processed = 0;
        int batchSize = config.batchSize();
        int every = config.progressEveryUsers();
        for (int offset = 0; offset < total && !isCancelled(); offset += batchSize) {
            List<CmiUserRecord> batch = source.users(offset, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            Map<Long, Long> playtimes = Map.of();
            Set<Long> playtimeUnavailable = Set.of();
            if (options.includes(ImportCategory.PLAYTIME) && source.hasPlaytimeTable()) {
                List<Long> ids = batch.stream().filter(user -> user.uuid() != null).map(CmiUserRecord::id).toList();
                try {
                    playtimes = source.playtimeSeconds(ids);
                } catch (SQLException e) {
                    ctx.logger().error("Reading CMI playtime failed; the whole batch is imported without playtime", e);
                    // One failed batch read means every user in it has no usable playtime. Counting a single
                    // failure would hide up to batchSize users that would otherwise look imported with 0 s.
                    playtimeUnavailable = new HashSet<>(ids);
                    for (CmiUserRecord user : batch) {
                        report.failed(ImportCategory.PLAYTIME, user.label(),
                                "playtime could not be read: " + describe(e));
                    }
                }
            }
            for (CmiUserRecord user : batch) {
                if (isCancelled()) {
                    break;
                }
                processUser(recordOf(user, playtimes.getOrDefault(user.id(), 0L),
                        playtimeUnavailable.contains(user.id())));
                importUserExtras(user);
                processed++;
                if (processed % every == 0) {
                    reportProgress(processed);
                }
            }
        }
        if (processed > 0 && processed % config.progressEveryUsers() != 0) {
            reportProgress(processed);
        }
        report.note("Users processed: " + processed + (isCancelled() ? " (cancelled)" : ""));
    }

    /** The last location as a /back entry, an active mute and the ignore list. */
    private void importUserExtras(CmiUserRecord user) {
        if (user.uuid() == null) {
            return;
        }
        if (options.includes(ImportCategory.IGNORE) && user.ignored() != null && !user.ignored().isBlank()) {
            importIgnores(user.uuid(), user.label(), IgnoreEntries.parse(user.ignored()));
        }
        if (options.includes(ImportCategory.LOCATIONS) && user.lastLocation() != null && !user.lastLocation().isBlank()) {
            Optional<dev.yanianz.atrium.api.teleport.StoredLocation> location = CmiLocations.parse(user.lastLocation());
            if (location.isEmpty()) {
                report.found(ImportCategory.LOCATIONS, 1);
                report.invalid(ImportCategory.LOCATIONS, user.label(), "last location could not be parsed");
            } else {
                importBack(user.uuid(), user.label(), location.get(), 0L);
            }
        }
        if (options.includes(ImportCategory.PUNISHMENTS) && user.mutedUntil() != null && !user.mutedUntil().isBlank()) {
            long until;
            try {
                until = Long.parseLong(user.mutedUntil().trim());
            } catch (NumberFormatException e) {
                until = 0L;
            }
            if (until > System.currentTimeMillis()) {
                String name = user.name() != null && user.name().length() <= 16 ? user.name() : null;
                importPunishment(new PunishmentRecord("cmi", dev.yanianz.atrium.api.moderation.PunishmentType.TEMPMUTE,
                        user.uuid(), name, null, null, null, java.time.Instant.now(), java.time.Instant.ofEpochMilli(until),
                        null, user.label(), null));
            }
        }
    }

    private UserRecord recordOf(CmiUserRecord user, long playtimeSeconds, boolean playtimeUnavailable) {
        List<ImportHome> parsedHomes = List.of();
        if (options.includes(ImportCategory.HOMES) && user.homes() != null && !user.homes().isBlank()) {
            List<CmiHomeParser.ParsedHome> parsed = CmiHomeParser.parse(user.homes());
            if (parsed.isEmpty()) {
                report.invalid(ImportCategory.HOMES, user.label(), "homes data could not be parsed");
            } else {
                List<ImportHome> homes = new ArrayList<>(parsed.size());
                for (CmiHomeParser.ParsedHome home : parsed) {
                    homes.add(home.ok()
                            ? new ImportHome(home.name(), home.location(), null)
                            : ImportHome.broken(home.problem()));
                }
                parsedHomes = List.copyOf(homes);
            }
        }
        BigDecimal balance = null;
        String balanceProblem = null;
        if (options.includes(ImportCategory.ECONOMY) && user.economy() != null && !user.economy().isBlank()) {
            Optional<BigDecimal> parsed = CmiBalanceParser.parse(user.economy());
            if (parsed.isPresent()) {
                balance = parsed.get();
            } else {
                balanceProblem = user.economy();
            }
        }
        String nickname = options.includes(ImportCategory.NICKS) ? user.nickname() : null;
        return new UserRecord(user.uuid(), user.label(), parsedHomes, nickname, balance, balanceProblem,
                playtimeSeconds, playtimeUnavailable);
    }

    // ---- warps ----

    private void importWarps() {
        Path file = findFile(cmiFolder.resolve("Saves"), "Warps.yml", "warps.yml");
        if (file == null) {
            report.note("Warps file not found; no warps to import");
            return;
        }
        List<CmiWarpFileParser.ParsedWarp> parsed;
        try {
            parsed = CmiWarpFileParser.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading {} failed", file, e);
            report.failed(ImportCategory.WARPS, file.getFileName().toString(), "cannot read file: " + describe(e));
            return;
        }
        boolean requireDefault = false;
        Path config = findFile(cmiFolder, "config.yml", "config.yaml");
        if (config != null) {
            try {
                requireDefault = CmiNodeLocations.truthy(CmiNodeLocations.child(loadYaml(config), "Warps"), false,
                        "requirePerm", "RequirePerm");
            } catch (Exception e) {
                ctx.logger().warn("Reading {} for Warps.requirePerm failed", config, e);
            }
        }
        List<WarpRecord> warps = new ArrayList<>(parsed.size());
        int permissionWarps = 0;
        for (CmiWarpFileParser.ParsedWarp warp : parsed) {
            if (!warp.ok()) {
                warps.add(new WarpRecord(warp.name(), warp.category(), null, warp.problem()));
                continue;
            }
            boolean needsPermission = warp.permissionRequired() != null ? warp.permissionRequired() : requireDefault;
            if (needsPermission) {
                permissionWarps++;
            }
            warps.add(new WarpRecord(warp.name(), warp.category(), byName(warp.location()), null, null, warp.owner(),
                    warp.createdAt(), warp.icon(), null, -1, warp.notes()));
        }
        if (permissionWarps > 0) {
            report.note(permissionWarps + " CMI warp(s) required a permission (cmi.command.warp.<name>). Atrium has one "
                    + "switch for all warps: set requirePerWarpPermission: true in modules/warps.yml and grant "
                    + "atrium.warp.<name> (atrium.warps.all for every warp). With it off, every warp is public.");
        }
        importWarps("warps", warps);
    }

    // ---- kits ----

    private void importKits() {
        Path folder = cmiFolder.resolve("Kits");
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (var stream = Files.list(folder)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".yml"))
                        .sorted().forEach(files::add);
            } catch (java.io.IOException e) {
                report.failed(ImportCategory.KITS, "Kits", "cannot list folder: " + describe(e));
                return;
            }
        }
        if (files.isEmpty()) {
            report.note("CMI Kits folder has no kit files; no kits to import");
            return;
        }
        String firstJoinKit = cmiConfig == null ? null : CmiSpawnParser.parse(cmiConfig).firstJoinKit();
        List<KitRecord> records = new ArrayList<>();
        for (Path file : files) {
            List<CmiKitParser.ParsedKit> kits;
            try {
                kits = CmiKitParser.parse(loadYaml(file));
            } catch (Exception e) {
                ctx.logger().error("Reading CMI kit file {} failed", file, e);
                report.failed(ImportCategory.KITS, file.getFileName().toString(), "cannot read file: " + describe(e));
                continue;
            }
            for (CmiKitParser.ParsedKit kit : kits) {
                boolean firstJoin = firstJoinKit != null && firstJoinKit.equalsIgnoreCase(kit.sourceName());
                records.add(new KitRecord(kit.name(), kit.sourceName(), kit.items(), kit.delaySeconds(), kit.cost(),
                        null, kit.maxUses(), false, firstJoin, kit.icon(), kit.notes(), kit.problem()));
            }
        }
        report.note("CMI kit permissions cmi.kit.<name> become atrium.kit.<name>");
        importKits(records);
    }

    // ---- jails ----

    private void importJails() {
        Path file = findFile(cmiFolder.resolve("Saves"), "Jails.yml", "jails.yml");
        if (file == null) {
            report.note("CMI Saves/Jails.yml not found; no jails to import");
            return;
        }
        List<CmiJailParser.ParsedJail> parsed;
        try {
            parsed = CmiJailParser.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading {} failed", file, e);
            report.failed(ImportCategory.JAILS, file.getFileName().toString(), "cannot read file: " + describe(e));
            return;
        }
        List<JailRecord> jails = new ArrayList<>();
        for (CmiJailParser.ParsedJail jail : parsed) {
            jails.add(new JailRecord(jail.name(), jail.spawn(), jail.radius(), jail.release(), jail.notes(), jail.problem()));
        }
        importJails(jails);
    }

    // ---- holograms ----

    private void importHolograms() {
        Path file = findFile(cmiFolder.resolve("Saves"), "Holograms.yml", "holograms.yml");
        if (file == null) {
            report.note("Holograms file not found; no holograms to import");
            return;
        }
        List<CmiHologramFileParser.ParsedHologram> parsed;
        try {
            parsed = CmiHologramFileParser.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading {} failed", file, e);
            report.failed(ImportCategory.HOLOGRAMS, file.getFileName().toString(), "cannot read file: " + describe(e));
            return;
        }
        beginPhase("holograms", parsed.size());
        int processed = 0;
        for (CmiHologramFileParser.ParsedHologram hologram : parsed) {
            if (isCancelled()) {
                break;
            }
            report.found(ImportCategory.HOLOGRAMS, 1);
            if (!hologram.ok()) {
                report.invalid(ImportCategory.HOLOGRAMS, hologram.name(), hologram.problem());
            } else if (!hologram.enabled() && !config.importDisabledHolograms) {
                report.skipped(ImportCategory.HOLOGRAMS, 1);
            } else if (config.checkWorlds && !worldExists(hologram.location().world())) {
                report.invalid(ImportCategory.HOLOGRAMS, hologram.name(),
                        "hologram is in missing world '" + hologram.location().world() + "'");
            } else {
                importHologram(hologram);
            }
            processed++;
            if (processed % config.progressEveryUsers() == 0) {
                reportProgress(processed);
            }
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    private void importHologram(CmiHologramFileParser.ParsedHologram hologram) {
        if (holograms == null) {
            report.unsupported(ImportCategory.HOLOGRAMS, 1);
            if (noteHologramsMissing()) {
                report.note("HologramService unavailable: holograms were counted but not imported");
            }
            return;
        }
        boolean exists = holograms.get(hologram.name()).isPresent();
        if (exists && !options.overwrite()) {
            report.skipped(ImportCategory.HOLOGRAMS, 1);
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.HOLOGRAMS, 1);
            return;
        }
        HologramImport.Outcome outcome = HologramImport.install(holograms, hologramWrites, hologram.name(),
                hologram.location(), hologram.pages(), exists);
        if (outcome.success()) {
            report.imported(ImportCategory.HOLOGRAMS, 1);
            return;
        }
        report.failed(ImportCategory.HOLOGRAMS, hologram.name(), outcome.problem());
        ctx.logger().warn("CMI hologram '{}' import failed: {}", hologram.name(), outcome.problem(),
                outcome.cause());
        if (exists && !outcome.restored()) {
            ctx.logger().error("CMI hologram '{}' could not be restored; check the holograms module data",
                    hologram.name());
        }
    }

    // ---- helpers ----

    private boolean noteHologramsMissing() {
        if (hologramsMissingNoted) {
            return false;
        }
        hologramsMissingNoted = true;
        return true;
    }

    private final class HologramWrites implements HologramImport.Waiter {
        @Override
        public <T> T await(CompletableFuture<T> future, String what) {
            return awaitWrite(future, ImportCategory.HOLOGRAMS, what);
        }
    }
}
