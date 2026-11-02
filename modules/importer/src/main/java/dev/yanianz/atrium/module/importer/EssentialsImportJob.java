package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/**
 * Imports EssentialsX data through Atrium's public services. Money, homes and nicknames are written;
 * warps and kits are written or reported; spawn, last locations, timestamps, ignore lists, mail and
 * punishment states are reported because Atrium has no write API for them yet.
 *
 * <p>Reads run in batches of {@link ImporterConfig#batchSize()} files, never all at once, and a broken
 * file is one report entry instead of a failed run.
 */
final class EssentialsImportJob extends ImportJob {
    private final Path essentialsFolder;
    private final Path spawnFolder;

    private EssentialsLocations.WorldIndex worldIndex;
    private boolean notedLocations;
    private boolean notedHistory;
    private boolean notedIgnore;
    private boolean notedMail;
    private boolean notedStates;

    EssentialsImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender,
            Path essentialsFolder, Path spawnFolder) {
        super(ctx, config, options, sender);
        this.essentialsFolder = essentialsFolder;
        this.spawnFolder = spawnFolder;
    }

    @Override
    protected String sourceName() {
        return "EssentialsX";
    }

    @Override
    protected String reportPrefix() {
        return "essentials";
    }

    @Override
    protected void run() throws Exception {
        sourceDescription = essentialsFolder.toString();
        report.note("Source: " + sourceDescription);
        if (readsUsers()) {
            importUsers();
        }
        if (options.includes(ImportCategory.WARPS)) {
            importWarps();
        }
        if (options.includes(ImportCategory.SPAWN)) {
            importSpawn();
        }
        if (options.includes(ImportCategory.KITS)) {
            importKits();
        }
    }

    /** Warps, spawn and kits need no userdata read at all. */
    private boolean readsUsers() {
        return options.includes(ImportCategory.ECONOMY) || options.includes(ImportCategory.HOMES)
                || options.includes(ImportCategory.NICKS) || options.includes(ImportCategory.LOCATIONS)
                || options.includes(ImportCategory.HISTORY) || options.includes(ImportCategory.IGNORE)
                || options.includes(ImportCategory.MAIL) || options.includes(ImportCategory.STATES);
    }

    // ---- users ----

    private void importUsers() {
        Path userdata = essentialsFolder.resolve("userdata");
        if (!Files.isDirectory(userdata)) {
            report.note("Essentials userdata folder not found: " + userdata + "; no users to import");
            return;
        }
        List<Path> files;
        try (var stream = Files.list(userdata)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            ctx.logger().error("Listing Essentials userdata in {} failed", userdata, e);
            report.failed(ImportCategory.SOURCE, "userdata", "cannot list folder: " + describe(e));
            return;
        }
        report.note("Essentials userdata files: " + files.size());
        beginPhase("users", files.size());
        int processed = 0;
        int every = config.progressEveryUsers();
        int batchSize = config.batchSize();
        for (int offset = 0; offset < files.size() && !isCancelled(); offset += batchSize) {
            List<Path> batch = files.subList(offset, Math.min(files.size(), offset + batchSize));
            for (Path file : batch) {
                if (isCancelled()) {
                    break;
                }
                importUserFile(file);
                processed++;
                if (processed % every == 0) {
                    reportProgress(processed);
                }
            }
        }
        if (processed > 0 && processed % every != 0) {
            reportProgress(processed);
        }
        report.note("Users processed: " + processed + (isCancelled() ? " (cancelled)" : ""));
    }

    private void importUserFile(Path file) {
        EssentialsUserParser.UserFile data;
        try {
            data = EssentialsUserParser.parse(file.getFileName().toString(), loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading Essentials userdata {} failed", file, e);
            report.failed(ImportCategory.SOURCE, file.getFileName().toString(), "cannot read file: " + describe(e));
            return;
        }
        UUID uuid = data.uuid();
        if (uuid == null) {
            // Legacy Essentials allowed name-based files; Atrium only knows the account if it has seen it.
            try {
                Optional<AtriumUser> known = await(ctx.users().loadByName(data.id()), ImportCategory.SOURCE);
                uuid = known.map(AtriumUser::uuid).orElse(null);
            } catch (StepFailed failure) {
                report.failed(ImportCategory.SOURCE, data.label(),
                        "cannot resolve legacy userdata name '" + data.id() + "': " + failure.getMessage());
                return;
            }
            if (uuid == null) {
                report.invalid(ImportCategory.SOURCE, data.label(),
                        "userdata file '" + data.id() + "' is not a UUID and the name is unknown to Atrium");
                return;
            }
        }
        List<ImportHome> homes = homesOf(data);
        reportExtras(data);
        processUser(new UserRecord(uuid, data.label(), homes, data.nickname(), data.money(), data.moneyProblem(),
                0L, false));
    }

    private List<ImportHome> homesOf(EssentialsUserParser.UserFile data) {
        if (!options.includes(ImportCategory.HOMES) || data.homes().isEmpty()) {
            return List.of();
        }
        List<ImportHome> homes = new ArrayList<>(data.homes().size());
        for (EssentialsUserParser.RawHome raw : data.homes()) {
            if (!raw.ok()) {
                homes.add(ImportHome.broken(raw.problem()));
                continue;
            }
            Optional<StoredLocation> resolved = worldIndex().resolve(raw.location());
            if (resolved.isEmpty()) {
                homes.add(new ImportHome(raw.name(), null,
                        "home '" + raw.name() + "' is in world '" + raw.location().worldToken()
                                + "' which is not loaded and has no resolvable name"));
            } else {
                homes.add(new ImportHome(raw.name(), resolved.get(), null));
            }
        }
        return List.copyOf(homes);
    }

    private void reportExtras(EssentialsUserParser.UserFile data) {
        if (options.includes(ImportCategory.LOCATIONS)) {
            int found = (data.lastLocation() == null ? 0 : 1) + (data.logoutLocation() == null ? 0 : 1);
            if (found > 0) {
                report.found(ImportCategory.LOCATIONS, found);
                report.unsupported(ImportCategory.LOCATIONS, found);
                if (!notedLocations) {
                    notedLocations = true;
                    report.note("Last/logout locations are reported only: Atrium keeps the server-side position "
                            + "and has no per-player join-location API.");
                }
            }
        }
        if (options.includes(ImportCategory.HISTORY)) {
            int found = 0;
            for (Long stamp : new Long[] {data.login(), data.logout(), data.lastLogin()}) {
                if (stamp != null) {
                    found++;
                }
            }
            if (found > 0) {
                report.found(ImportCategory.HISTORY, found);
                report.unsupported(ImportCategory.HISTORY, found);
                if (!notedHistory) {
                    notedHistory = true;
                    report.note("Login/logout timestamps are reported only: Atrium derives first-join and "
                            + "last-seen from its own sessions.");
                }
            }
        }
        if (options.includes(ImportCategory.IGNORE) && data.ignored() > 0) {
            report.found(ImportCategory.IGNORE, data.ignored());
            report.unsupported(ImportCategory.IGNORE, data.ignored());
            if (!notedIgnore) {
                notedIgnore = true;
                report.note("Ignore lists are reported only: IgnoreService has no write method.");
            }
        }
        if (options.includes(ImportCategory.MAIL) && data.mail() > 0) {
            report.found(ImportCategory.MAIL, data.mail());
            report.unsupported(ImportCategory.MAIL, data.mail());
            if (!notedMail) {
                notedMail = true;
                report.note("Mail is reported only: the mail module has no public API.");
            }
        }
        if (options.includes(ImportCategory.STATES)) {
            int found = (data.jailed() ? 1 : 0) + (data.muted() ? 1 : 0) + (data.banned() ? 1 : 0);
            if (found > 0) {
                report.found(ImportCategory.STATES, found);
                report.unsupported(ImportCategory.STATES, found);
                if (!notedStates) {
                    notedStates = true;
                    report.note("Jail, mute and ban states are reported only: PunishmentService has no import "
                            + "entry point and punish() would enforce the state live. Example: " + data.label()
                            + " is " + stateText(data) + "; re-apply it with /ban, /mute or /jail.");
                }
            }
        }
    }

    private static String stateText(EssentialsUserParser.UserFile data) {
        List<String> states = new ArrayList<>();
        if (data.banned()) {
            states.add("banned" + (data.stateDetail() == null ? "" : " (" + data.stateDetail() + ")"));
        }
        if (data.muted()) {
            states.add("muted");
        }
        if (data.jailed()) {
            states.add("jailed");
        }
        return String.join(", ", states);
    }

    // ---- warps ----

    private void importWarps() {
        Path directory = essentialsFolder.resolve("warps");
        if (!Files.isDirectory(directory)) {
            report.note("Essentials warps folder not found; no warps to import");
            return;
        }
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            ctx.logger().error("Listing Essentials warps in {} failed", directory, e);
            report.failed(ImportCategory.WARPS, "warps", "cannot list folder: " + describe(e));
            return;
        }
        List<WarpRecord> warps = new ArrayList<>();
        for (Path file : files) {
            List<EssentialsWarpParser.ParsedWarp> parsed;
            try {
                parsed = EssentialsWarpParser.parse(file.getFileName().toString(), loadYaml(file));
            } catch (Exception e) {
                ctx.logger().error("Reading Essentials warp file {} failed", file, e);
                report.failed(ImportCategory.WARPS, file.getFileName().toString(), "cannot read file: " + describe(e));
                continue;
            }
            for (EssentialsWarpParser.ParsedWarp warp : parsed) {
                if (!warp.ok()) {
                    warps.add(new WarpRecord(warp.name(), warp.category(), null, warp.problem()));
                    continue;
                }
                Optional<StoredLocation> resolved = worldIndex().resolve(warp.location());
                if (resolved.isEmpty()) {
                    warps.add(new WarpRecord(warp.name(), warp.category(), null,
                            "warp is in world '" + warp.location().worldToken() + "' which is not loaded"));
                } else {
                    warps.add(new WarpRecord(warp.name(), warp.category(), resolved.get(), null));
                }
            }
        }
        importWarps("warps", warps);
    }

    // ---- spawn ----

    private void importSpawn() {
        Path file = spawnFolder == null ? null : spawnFolder.resolve("spawn.yml");
        if (file == null || !Files.isRegularFile(file)) {
            report.note("EssentialsSpawn spawn.yml not found; no spawn to report");
            return;
        }
        List<EssentialsSpawnParser.SpawnPoint> points;
        try {
            points = EssentialsSpawnParser.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading EssentialsSpawn {} failed", file, e);
            report.failed(ImportCategory.SPAWN, "spawn.yml", "cannot read file: " + describe(e));
            return;
        }
        beginPhase("spawn", points.size());
        int processed = 0;
        for (EssentialsSpawnParser.SpawnPoint point : points) {
            if (isCancelled()) {
                break;
            }
            report.found(ImportCategory.SPAWN, 1);
            Optional<StoredLocation> resolved = worldIndex().resolve(point.location());
            if (resolved.isEmpty()) {
                report.invalid(ImportCategory.SPAWN, point.group(),
                        "spawn is in world '" + point.location().worldToken() + "' which is not loaded");
            } else {
                report.unsupported(ImportCategory.SPAWN, 1);
                if (point == EssentialsSpawnParser.preferred(points)) {
                    StoredLocation location = resolved.get();
                    report.note("Spawn group '" + point.group() + "' is at " + location.world() + " "
                            + location.blockX() + " " + location.blockY() + " " + location.blockZ()
                            + "; reported only, set it with /setspawn default at that position.");
                }
            }
            processed++;
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    // ---- kits ----

    private void importKits() {
        Path file = findFile(essentialsFolder, "kits.yml", "kits.yaml");
        if (file == null) {
            file = findFile(essentialsFolder, "config.yml", "config.yaml");
        }
        if (file == null) {
            report.note("Essentials kits.yml/config.yml not found; no kits to report");
            return;
        }
        List<EssentialsKitsParser.ParsedKit> kits;
        try {
            kits = EssentialsKitsParser.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading Essentials kits {} failed", file, e);
            report.failed(ImportCategory.KITS, file.getFileName().toString(), "cannot read file: " + describe(e));
            return;
        }
        beginPhase("kits", kits.size());
        int processed = 0;
        List<String> names = new ArrayList<>();
        for (EssentialsKitsParser.ParsedKit kit : kits) {
            if (isCancelled()) {
                break;
            }
            report.found(ImportCategory.KITS, 1);
            if (!kit.ok()) {
                report.invalid(ImportCategory.KITS, kit.name(), kit.problem());
            } else {
                report.unsupported(ImportCategory.KITS, 1);
                if (names.size() < 25) {
                    names.add(kit.name() + " (" + kit.items() + " item(s)"
                            + (kit.delaySeconds() > 0 ? ", delay " + kit.delaySeconds() + "s" : "") + ")");
                }
            }
            processed++;
        }
        if (!names.isEmpty()) {
            report.note("Kits are reported only: the kits module has no public write API. Recreate them with "
                    + "/kiteditor: " + String.join(", ", names)
                    + (kits.size() > names.size() ? ", ..." : ""));
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    // ---- worlds ----

    private EssentialsLocations.WorldIndex worldIndex() {
        if (worldIndex != null) {
            return worldIndex;
        }
        try {
            worldIndex = await(ctx.scheduler().supplyGlobal(() -> {
                Map<UUID, String> byId = new HashMap<>();
                Set<String> names = new HashSet<>();
                for (World world : ctx.plugin().getServer().getWorlds()) {
                    byId.put(world.getUID(), world.getName());
                    names.add(world.getName());
                }
                return new EssentialsLocations.WorldIndex(Map.copyOf(byId), Set.copyOf(names));
            }), ImportCategory.SOURCE);
        } catch (StepFailed failure) {
            ctx.logger().warn("Cannot list loaded worlds; world UUIDs in Essentials data will not resolve", failure);
            report.note("Loaded worlds could not be listed: " + failure.getMessage());
            worldIndex = EssentialsLocations.WorldIndex.empty();
        }
        return worldIndex;
    }
}
