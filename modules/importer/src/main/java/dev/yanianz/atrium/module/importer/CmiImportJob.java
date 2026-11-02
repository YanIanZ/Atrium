package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
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
 * Imports CMI's database and files. This class only reads CMI's own shape; the writing, rollback,
 * progress, status and reporting all come from {@link ImportJob}.
 */
final class CmiImportJob extends ImportJob {
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
        boolean needsUsers = options.includes(ImportCategory.ECONOMY) || options.includes(ImportCategory.HOMES)
                || options.includes(ImportCategory.NICKS) || options.includes(ImportCategory.PLAYTIME);
        if (needsUsers) {
            CmiConnectionConfig connection = CmiConnectionConfig.read(cmiFolder);
            sourceDescription = connection.describe();
            report.note("Source: " + sourceDescription);
            try (CmiDatabaseSource source = CmiDatabaseSource.open(connection)) {
                noteSourceShape(source);
                importUsers(source);
            }
        } else {
            sourceDescription = cmiFolder.toString();
            report.note("Source: " + sourceDescription
                    + " (database not opened: no user category was selected)");
        }
        if (options.includes(ImportCategory.WARPS)) {
            importWarps();
        }
        if (options.includes(ImportCategory.HOLOGRAMS)) {
            importHolograms();
        }
    }

    private void noteSourceShape(CmiDatabaseSource source) {
        report.note("Columns: homes=" + yesNo(source.hasHomesColumn())
                + ", nickname=" + yesNo(source.hasNickColumn())
                + ", economy=" + yesNo(source.hasEconomyColumn())
                + ", playtime_table=" + yesNo(source.hasPlaytimeTable()));
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
        List<WarpRecord> warps = new ArrayList<>(parsed.size());
        for (CmiWarpFileParser.ParsedWarp warp : parsed) {
            warps.add(warp.ok()
                    ? new WarpRecord(warp.name(), warp.category(), warp.location(), null)
                    : new WarpRecord(warp.name(), warp.category(), null, warp.problem()));
        }
        importWarps("warps", warps);
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
