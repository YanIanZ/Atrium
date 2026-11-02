package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.bukkit.command.CommandSender;
import org.spongepowered.configurate.BasicConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Imports EssentialsX data through Atrium's public services: money, homes, nicknames, warps, spawn
 * (EssentialsSpawn), kits, jails, mute/jail/ban states, mail, ignore lists and the last location (as a
 * {@code /back} entry). Login timestamps are reported only. The server's ban lists are imported too,
 * under the ledger source {@code vanilla}.
 *
 * <p>Reads run in batches of {@link ImporterConfig#batchSize()} files, never all at once, and a broken
 * file is one report entry instead of a failed run.
 */
final class EssentialsImportJob extends ExtendedImportJob {
    private final Path essentialsFolder;
    private final Path spawnFolder;

    private ConfigurationNode essentialsConfig;

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
        // Server-wide data first: jail sentences in userdata need the jails, kits may be first-join kits.
        if (options.includes(ImportCategory.WARPS)) {
            importWarps();
        }
        if (options.includes(ImportCategory.SPAWN)) {
            importSpawn();
        }
        if (options.includes(ImportCategory.KITS)) {
            importKits();
        }
        if (options.includes(ImportCategory.JAILS) || options.includes(ImportCategory.PUNISHMENTS)) {
            importJailsFile();
        }
        if (readsUsers()) {
            importUsers();
        }
        if (options.includes(ImportCategory.PUNISHMENTS) && !isCancelled()) {
            importVanillaBans(serverFolder());
        }
    }

    /** Warps, spawn, kits and jails need no userdata read at all. */
    private boolean readsUsers() {
        return options.includes(ImportCategory.ECONOMY) || options.includes(ImportCategory.HOMES)
                || options.includes(ImportCategory.NICKS) || options.includes(ImportCategory.LOCATIONS)
                || options.includes(ImportCategory.HISTORY) || options.includes(ImportCategory.IGNORE)
                || options.includes(ImportCategory.MAIL) || options.includes(ImportCategory.PUNISHMENTS);
    }

    /** Essentials' config.yml (per-warp permissions, kit costs, newbie spawn), or an empty node. */
    private ConfigurationNode essentialsConfig() {
        if (essentialsConfig != null) {
            return essentialsConfig;
        }
        Path file = findFile(essentialsFolder, "config.yml", "config.yaml");
        try {
            essentialsConfig = file == null ? BasicConfigurationNode.root() : loadYaml(file);
        } catch (IOException e) {
            ctx.logger().warn("Reading Essentials config.yml failed; kit costs and permission rules are unknown", e);
            report.note("Essentials config.yml could not be read: " + describe(e));
            essentialsConfig = BasicConfigurationNode.root();
        }
        return essentialsConfig;
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
        if (options.includes(ImportCategory.LOCATIONS)) {
            EssentialsLocations.EssLocation last = data.lastLocation() != null ? data.lastLocation() : data.logoutLocation();
            if (last != null) {
                Optional<StoredLocation> resolved = worldIndex().resolve(last);
                if (resolved.isEmpty()) {
                    report.found(ImportCategory.LOCATIONS, 1);
                    report.invalid(ImportCategory.LOCATIONS, data.label(),
                            "last location is in world '" + last.worldToken() + "' which is not loaded");
                } else {
                    importBack(uuid, data.label(), resolved.get(), firstNonNull(data.logout(), data.lastLogin(), data.login()));
                }
            }
        }
        importMail(uuid, data.label(), data.mails(), firstNonNull(data.logout(), data.lastLogin(), data.login()));
        importIgnores(uuid, data.label(), data.ignores());
        if (options.includes(ImportCategory.PUNISHMENTS)) {
            for (PunishmentRecord record : punishmentsOf(uuid, data)) {
                importPunishment(record);
            }
        }
    }

    private static long firstNonNull(Long... values) {
        for (Long value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return 0L;
    }

    /** Mute, jail and (legacy userdata) ban states as punishment records. */
    static List<PunishmentRecord> punishmentsOf(UUID uuid, EssentialsUserParser.UserFile data) {
        EssentialsUserParser.States states = data.states();
        String name = data.label() == null || data.label().length() > 16 ? null : data.label();
        // Essentials keeps no creation time for mutes, jails and bans; the last session is the best guess,
        // and a file without timestamps gets the import time instead of 1970.
        long lastSeen = firstNonNull(data.logout(), data.lastLogin(), data.login());
        Instant created = lastSeen > 0 ? Instant.ofEpochMilli(lastSeen) : Instant.now();
        List<PunishmentRecord> out = new ArrayList<>();
        if (states.muted()) {
            boolean temp = states.muteUntil() > 0;
            out.add(new PunishmentRecord("essentials", temp ? PunishmentType.TEMPMUTE : PunishmentType.MUTE, uuid, name,
                    null, null, states.muteReason(), created, temp ? Instant.ofEpochMilli(states.muteUntil()) : null,
                    null, data.label(), null));
        }
        if (states.jailed()) {
            out.add(new PunishmentRecord("essentials", PunishmentType.JAIL, uuid, name, null, null, null, created,
                    states.jailUntil() > 0 ? Instant.ofEpochMilli(states.jailUntil()) : null, states.jail(), data.label(),
                    null));
        }
        if (states.banned()) {
            boolean temp = states.banUntil() > 0;
            out.add(new PunishmentRecord("essentials", temp ? PunishmentType.TEMPBAN : PunishmentType.BAN, uuid, name,
                    null, null, states.banReason(), created, temp ? Instant.ofEpochMilli(states.banUntil()) : null,
                    null, data.label(), null));
        }
        return out;
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

    /** Login timestamps have no Atrium write API: counted and explained, not written. */
    private void reportExtras(EssentialsUserParser.UserFile data) {
        if (options.includes(ImportCategory.HISTORY)) {
            int found = 0;
            for (Long stamp : new Long[] {data.login(), data.logout(), data.lastLogin()}) {
                if (stamp != null) {
                    found++;
                }
            }
            if (found > 0) {
                report.found(ImportCategory.HISTORY, found);
                report.unsupported(ImportCategory.HISTORY, found,
                        "Atrium derives first-join and last-seen from its own sessions");
            }
        }
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
                    warps.add(new WarpRecord(warp.name(), warp.category(), resolved.get(), null, null, warp.owner(),
                            0L, null, null, -1, warp.notes()));
                }
            }
        }
        if (CmiNodeLocations.truthy(essentialsConfig(), false, "per-warp-permission")) {
            report.note("Essentials 'per-warp-permission' is on: warp access needs essentials.warps.<name> there. "
                    + "In Atrium set requirePerWarpPermission: true in modules/warps.yml and grant atrium.warp.<name> "
                    + "(or atrium.warps.all) instead.");
        }
        importWarps("warps", warps);
    }

    // ---- spawn ----

    private void importSpawn() {
        Path file = spawnFolder == null ? null : spawnFolder.resolve("spawn.yml");
        if (file == null || !Files.isRegularFile(file)) {
            report.note("EssentialsSpawn spawn.yml not found; no spawn to import");
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
        String newbies = CmiNodeLocations.text(CmiNodeLocations.child(essentialsConfig(), "newbies"), "spawnpoint");
        importSpawns(EssentialsSpawnParser.plan(points, newbies, worldIndex()));
    }

    // ---- kits ----

    private void importKits() {
        Path file = findFile(essentialsFolder, "kits.yml", "kits.yaml");
        if (file == null) {
            file = findFile(essentialsFolder, "config.yml", "config.yaml");
        }
        if (file == null) {
            report.note("Essentials kits.yml/config.yml not found; no kits to import");
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
        ConfigurationNode costs = CmiNodeLocations.child(essentialsConfig(), "command-costs");
        List<KitRecord> records = new ArrayList<>();
        for (EssentialsKitsParser.ParsedKit kit : kits) {
            records.add(kitRecord(kit, costs));
        }
        report.note("Essentials kit permissions essentials.kits.<name> become atrium.kit.<name>");
        importKits(records);
    }

    static KitRecord kitRecord(EssentialsKitsParser.ParsedKit kit, ConfigurationNode costs) {
        if (!kit.ok()) {
            return new KitRecord(kit.name() == null ? "?" : kit.name(), kit.name() == null ? "?" : kit.name(), List.of(), 0,
                    BigDecimal.ZERO, null, -1, false, false, null, List.of(), kit.problem());
        }
        List<String> notes = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        String rawCost = costs == null ? null : CmiNodeLocations.text(costs, "kit-" + kit.sourceName(), "kit-" + kit.name());
        if (rawCost == null) {
            rawCost = kit.money();
        }
        if (rawCost != null && !rawCost.isBlank()) {
            try {
                cost = new BigDecimal(rawCost.trim());
                if (cost.signum() < 0) {
                    cost = BigDecimal.ZERO;
                    notes.add("negative cost ignored");
                }
            } catch (NumberFormatException e) {
                notes.add("cost '" + rawCost + "' is not a number; imported as free");
            }
        }
        if (!kit.commands().isEmpty()) {
            notes.add(kit.commands().size() + " command/money line(s) not imported (Atrium kits give items only)");
        }
        return new KitRecord(kit.name().toLowerCase(Locale.ROOT), kit.sourceName(), kit.specs(), kit.delaySeconds(), cost,
                null, -1, kit.oneTime(), false, null, List.copyOf(notes), null);
    }

    // ---- jails ----

    private void importJailsFile() {
        Path file = findFile(essentialsFolder, "jail.yml", "jails.yml");
        if (file == null) {
            if (options.includes(ImportCategory.JAILS)) {
                report.note("Essentials jail.yml/jails.yml not found; no jails to import");
            }
            return;
        }
        List<EssentialsJailParser.ParsedJail> parsed;
        try {
            parsed = EssentialsJailParser.parse(loadYaml(file));
        } catch (Exception e) {
            ctx.logger().error("Reading Essentials jails {} failed", file, e);
            report.failed(ImportCategory.JAILS, file.getFileName().toString(), "cannot read file: " + describe(e));
            return;
        }
        List<JailRecord> jails = new ArrayList<>();
        for (EssentialsJailParser.ParsedJail jail : parsed) {
            if (!jail.ok()) {
                jails.add(new JailRecord(jail.name(), null, 0, null, List.of(), jail.problem()));
                continue;
            }
            Optional<StoredLocation> resolved = worldIndex().resolve(jail.location());
            jails.add(resolved.isPresent()
                    ? new JailRecord(jail.name(), resolved.get(), 0, null, jail.notes(), null)
                    : new JailRecord(jail.name(), null, 0, null, List.of(),
                            "jail is in world '" + jail.location().worldToken() + "' which is not loaded"));
        }
        if (options.includes(ImportCategory.JAILS)) {
            report.note("Essentials jails are points; Atrium jails get the moderation module's default radius");
            importJails(jails);
        } else {
            // Only the names are needed so that jail sentences can be checked in a dry run.
            jails.stream().filter(j -> j.problem() == null).forEach(j -> sourceJails.add(j.name().toLowerCase(Locale.ROOT)));
        }
    }
}
