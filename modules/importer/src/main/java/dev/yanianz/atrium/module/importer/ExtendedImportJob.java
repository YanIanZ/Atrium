package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.kits.KitAdminService;
import dev.yanianz.atrium.api.mail.MailAdminService;
import dev.yanianz.atrium.api.moderation.ModerationImportService;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentService;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import dev.yanianz.atrium.api.teleport.BackHistoryAdminService;
import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
import org.bukkit.inventory.ItemStack;

/**
 * {@link ImportJob} plus the categories that are not part of a player's all-or-nothing record: spawn
 * points, jails, kits, punishments, mail and the imported {@code /back} entry. Each of them is written
 * through its own module's import service and is idempotent on its own: name-keyed data (spawn, jails,
 * kits) is skipped when it already exists, per-player data is recorded in the {@link ImportLedger}.
 */
abstract class ExtendedImportJob extends ImportJob {
    protected SpawnAdminService spawnAdmin;
    protected BackHistoryAdminService backAdmin;
    protected ModerationImportService moderation;
    protected PunishmentService punishments;
    protected KitAdminService kitAdmin;
    protected MailAdminService mailAdmin;

    private EssentialsLocations.WorldIndex worldIndex;
    /** Jails found in the source (lower case), so a dry run can tell whether a jail sentence would import. */
    protected final Set<String> sourceJails = new HashSet<>();
    private final Set<String> notedOnce = new HashSet<>();

    ExtendedImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender) {
        super(ctx, config, options, sender);
    }

    @Override
    protected void resolveServices() {
        super.resolveServices();
        spawnAdmin = ctx.services().get(SpawnAdminService.class).orElse(null);
        backAdmin = ctx.services().get(BackHistoryAdminService.class).orElse(null);
        moderation = ctx.services().get(ModerationImportService.class).orElse(null);
        punishments = ctx.services().get(PunishmentService.class).orElse(null);
        kitAdmin = ctx.services().get(KitAdminService.class).orElse(null);
        mailAdmin = ctx.services().get(MailAdminService.class).orElse(null);
    }

    /** Adds a report note only the first time {@code key} is seen. */
    protected void noteOnce(String key, String note) {
        if (notedOnce.add(key)) {
            report.note(note);
        }
    }

    // ---- worlds ----

    /** Loaded worlds by UUID and name, captured once on the global thread. */
    protected EssentialsLocations.WorldIndex worldIndex() {
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
            ctx.logger().warn("Cannot list loaded worlds; world UUIDs in the source will not resolve", failure);
            report.note("Loaded worlds could not be listed: " + failure.getMessage());
            worldIndex = EssentialsLocations.WorldIndex.empty();
        }
        return worldIndex;
    }

    /**
     * A location whose world may be written as a UUID: a loaded world's UUID becomes its name. A name is
     * kept as it is (it may be a world that is not loaded right now).
     */
    protected StoredLocation byName(StoredLocation location) {
        if (location == null) {
            return null;
        }
        UUID id = CmiWarpFileParser.uuid(location.world());
        if (id == null) {
            return location;
        }
        String name = worldIndex().byId().get(id);
        return name == null ? location : new StoredLocation(name, location.x(), location.y(), location.z(),
                location.yaw(), location.pitch());
    }

    /** A problem when the world is a UUID that no loaded world has, or a missing world folder. */
    protected String worldProblem(StoredLocation location) {
        if (CmiWarpFileParser.uuid(location.world()) != null) {
            return "world " + location.world() + " is not loaded and has no known name";
        }
        if (config.checkWorlds && !worldExists(location.world())) {
            return "world '" + location.world() + "' does not exist";
        }
        return null;
    }

    // ---- spawn ----

    /**
     * One spawn point. {@code unsupported} is set when Atrium has no matching spawn kind (per-group spawns);
     * the location is then reported so an admin can recreate it.
     */
    protected record SpawnRecord(String label, SpawnAdminService.Kind kind, String world, StoredLocation location,
            String problem, String unsupported) {}

    protected void importSpawns(List<SpawnRecord> spawns) {
        beginPhase("spawn", spawns.size());
        int processed = 0;
        for (SpawnRecord spawn : spawns) {
            if (isCancelled()) {
                break;
            }
            report.found(ImportCategory.SPAWN, 1);
            importSpawn(spawn);
            processed++;
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    private void importSpawn(SpawnRecord spawn) {
        if (spawn.problem() != null) {
            report.invalid(ImportCategory.SPAWN, spawn.label(), spawn.problem());
            return;
        }
        StoredLocation location = byName(spawn.location());
        if (spawn.unsupported() != null) {
            report.unsupported(ImportCategory.SPAWN, 1, spawn.unsupported());
            report.issue(new ImportIssue(ImportCategory.SPAWN, spawn.label(), "not imported (" + spawn.unsupported()
                    + "); it is at " + describe(location)));
            return;
        }
        String problem = worldProblem(location);
        if (problem != null) {
            report.invalid(ImportCategory.SPAWN, spawn.label(), problem);
            return;
        }
        if (spawnAdmin == null) {
            report.unsupported(ImportCategory.SPAWN, 1, "teleport module (SpawnAdminService) not available");
            return;
        }
        if (spawnAdmin.spawn(spawn.kind(), spawn.world()).isPresent() && !options.overwrite()) {
            report.skipped(ImportCategory.SPAWN, 1, "Atrium already has this spawn set (use --overwrite)");
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.SPAWN, 1);
            return;
        }
        try {
            awaitWrite(spawnAdmin.setSpawn(spawn.kind(), spawn.world(), location), ImportCategory.SPAWN,
                    "spawn " + spawn.label());
            report.imported(ImportCategory.SPAWN, 1);
            report.note("Spawn '" + spawn.label() + "' set to " + describe(location));
        } catch (StepFailed failure) {
            report.failed(ImportCategory.SPAWN, spawn.label(), failure.getMessage());
        }
    }

    protected static String describe(StoredLocation location) {
        return location.world() + " " + location.blockX() + " " + location.blockY() + " " + location.blockZ();
    }

    // ---- jails ----

    /** {@code radius <= 0} uses the moderation module's default radius. */
    protected record JailRecord(String name, StoredLocation location, double radius, StoredLocation release,
            List<String> notes, String problem) {}

    protected void importJails(List<JailRecord> jails) {
        Set<String> existing = new HashSet<>();
        if (moderation != null) {
            moderation.jailNames().forEach(name -> existing.add(name.toLowerCase(Locale.ROOT)));
        }
        beginPhase("jails", jails.size());
        int processed = 0;
        for (JailRecord jail : jails) {
            if (isCancelled()) {
                break;
            }
            report.found(ImportCategory.JAILS, 1);
            importJail(jail, existing);
            processed++;
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    private void importJail(JailRecord jail, Set<String> existing) {
        if (jail.problem() != null) {
            report.invalid(ImportCategory.JAILS, jail.name(), jail.problem());
            return;
        }
        StoredLocation location = byName(jail.location());
        String problem = worldProblem(location);
        if (problem != null) {
            report.invalid(ImportCategory.JAILS, jail.name(), problem);
            return;
        }
        sourceJails.add(jail.name().toLowerCase(Locale.ROOT));
        for (String note : jail.notes()) {
            report.issue(new ImportIssue(ImportCategory.JAILS, jail.name(), "note: " + note));
        }
        if (moderation == null) {
            report.unsupported(ImportCategory.JAILS, 1, "moderation module (ModerationImportService) not available");
            return;
        }
        if (existing.contains(jail.name().toLowerCase(Locale.ROOT)) && !options.overwrite()) {
            report.skipped(ImportCategory.JAILS, 1, "jail already exists (use --overwrite)");
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.JAILS, 1);
            return;
        }
        StoredLocation release = jail.release() == null ? null : byName(jail.release());
        try {
            boolean written = awaitWrite(moderation.importJail(new ModerationImportService.JailImport(jail.name(),
                    location, jail.radius(), release), options.overwrite()), ImportCategory.JAILS, "jail " + jail.name());
            if (written) {
                report.imported(ImportCategory.JAILS, 1);
            } else {
                report.skipped(ImportCategory.JAILS, 1, "jail already exists (use --overwrite)");
            }
        } catch (StepFailed failure) {
            report.failed(ImportCategory.JAILS, jail.name(), failure.getMessage());
        }
    }

    // ---- kits ----

    /** One kit, independent of the source format. {@code permission == null} uses {@code atrium.kit.<name>}. */
    protected record KitRecord(String name, String sourceName, List<KitItemSpec> items, long cooldownSeconds,
            BigDecimal cost, String permission, int maxUses, boolean oneTime, boolean firstJoin, byte[] icon,
            List<String> notes, String problem) {}

    protected void importKits(List<KitRecord> kits) {
        Set<String> existing = new HashSet<>();
        if (kitAdmin != null) {
            kitAdmin.kitNames().forEach(name -> existing.add(name.toLowerCase(Locale.ROOT)));
        }
        Set<String> seen = new HashSet<>();
        beginPhase("kits", kits.size());
        int processed = 0;
        for (KitRecord kit : kits) {
            if (isCancelled()) {
                break;
            }
            report.found(ImportCategory.KITS, 1);
            if (kit.problem() != null) {
                report.invalid(ImportCategory.KITS, kit.sourceName(), kit.problem());
            } else if (!seen.add(kit.name().toLowerCase(Locale.ROOT))) {
                report.invalid(ImportCategory.KITS, kit.sourceName(),
                        "duplicate kit name '" + kit.name() + "' (kit names are case-insensitive)");
            } else {
                importKit(kit, existing);
            }
            processed++;
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    private void importKit(KitRecord kit, Set<String> existing) {
        String label = kit.sourceName().equals(kit.name()) ? kit.name() : kit.sourceName() + " -> " + kit.name();
        if (!kit.sourceName().equalsIgnoreCase(kit.name())) {
            report.issue(new ImportIssue(ImportCategory.KITS, label, "note: renamed (Atrium kit names are a-z, 0-9, _ and -)"));
        }
        for (String note : kit.notes()) {
            report.issue(new ImportIssue(ImportCategory.KITS, label, "note: " + note));
        }
        if (kitAdmin == null) {
            report.unsupported(ImportCategory.KITS, 1, "kits module (KitAdminService) not available");
            return;
        }
        if (existing.contains(kit.name().toLowerCase(Locale.ROOT)) && !options.overwrite()) {
            report.skipped(ImportCategory.KITS, 1, "kit already exists (use --overwrite)");
            return;
        }
        BuiltKit built;
        try {
            built = await(ctx.scheduler().supplyGlobal(() -> buildItems(kit)), ImportCategory.KITS);
        } catch (StepFailed failure) {
            report.failed(ImportCategory.KITS, label, "items could not be built: " + failure.getMessage());
            return;
        }
        for (String problem : built.problems()) {
            report.issue(new ImportIssue(ImportCategory.KITS, label, "note: " + problem));
        }
        if (built.count() == 0 && !kit.items().isEmpty()) {
            report.invalid(ImportCategory.KITS, label, "none of its " + kit.items().size() + " item(s) could be imported");
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.KITS, 1);
            return;
        }
        try {
            boolean written = awaitWrite(kitAdmin.importKit(new KitAdminService.KitImport(kit.name(), built.slots(),
                    kit.cooldownSeconds(), kit.cost(), kit.permission(), kit.maxUses(), kit.oneTime(), kit.firstJoin(),
                    built.icon()), options.overwrite()), ImportCategory.KITS, "kit " + kit.name());
            if (written) {
                report.imported(ImportCategory.KITS, 1);
            } else {
                report.skipped(ImportCategory.KITS, 1, "kit already exists (use --overwrite)");
            }
        } catch (StepFailed failure) {
            report.failed(ImportCategory.KITS, label, failure.getMessage());
        }
    }

    private record BuiltKit(List<ItemStack> slots, int count, ItemStack icon, List<String> problems) {}

    /** Runs on the global thread: item construction needs the server registries. */
    private static BuiltKit buildItems(KitRecord kit) {
        ItemStack[] slots = new ItemStack[KitAdminService.SLOTS];
        List<String> problems = new ArrayList<>();
        List<ItemStack> overflow = new ArrayList<>();
        int count = 0;
        int index = 0;
        for (KitItemSpec spec : kit.items()) {
            index++;
            KitItemFactory.Built built = KitItemFactory.build(spec);
            if (built.item() == null) {
                problems.add("item #" + index + " (" + spec.label() + ") not imported: " + built.problem());
                continue;
            }
            if (!built.dropped().isEmpty()) {
                problems.add("item #" + index + " (" + spec.label() + ") imported without: "
                        + String.join(", ", built.dropped()));
            }
            count++;
            if (spec.slot() >= 0 && spec.slot() < slots.length && slots[spec.slot()] == null) {
                slots[spec.slot()] = built.item();
            } else {
                overflow.add(built.item());
            }
        }
        for (ItemStack item : overflow) {
            boolean placed = false;
            for (int slot = 0; slot < 36 && !placed; slot++) {
                if (slots[slot] == null) {
                    slots[slot] = item;
                    placed = true;
                }
            }
            if (!placed) {
                problems.add("no free slot left for " + item.getType().name().toLowerCase(Locale.ROOT));
                count--;
            }
        }
        ItemStack icon = null;
        if (kit.icon() != null) {
            KitItemFactory.Built built = KitItemFactory.build(KitItemSpec.serialized(-1, kit.icon()));
            if (built.item() == null) {
                problems.add("icon not imported: " + built.problem());
            } else {
                icon = built.item();
            }
        }
        return new BuiltKit(java.util.Arrays.asList(slots), count, icon, problems);
    }

    // ---- punishments ----

    /**
     * One punishment from a source. {@code source} is the ledger source ({@code cmi}, {@code essentials},
     * {@code vanilla}); {@code target == null} only for IP bans.
     */
    protected record PunishmentRecord(String source, PunishmentType type, UUID target, String targetName, String ip,
            String actor, String reason, Instant created, Instant expires, String jail, String label, String problem) {}

    /** Ledger key of a punishment: one per kind group and target, so a ban and a mute are tracked apart. */
    static UUID punishmentKey(PunishmentType type, UUID target, String ip) {
        String group = switch (type) {
            case BAN, TEMPBAN -> "ban";
            case MUTE, TEMPMUTE, SHADOWMUTE -> "mute";
            case IPBAN -> "ipban";
            default -> type.name().toLowerCase(Locale.ROOT);
        };
        String who = type == PunishmentType.IPBAN ? String.valueOf(ip) : String.valueOf(target);
        return UUID.nameUUIDFromBytes((group + ':' + who).getBytes(StandardCharsets.UTF_8));
    }

    protected void importPunishment(PunishmentRecord record) {
        report.found(ImportCategory.PUNISHMENTS, 1);
        if (record.problem() != null) {
            report.invalid(ImportCategory.PUNISHMENTS, record.label(), record.problem());
            return;
        }
        Instant now = Instant.now();
        if (record.expires() != null && !record.expires().isAfter(now)) {
            report.skipped(ImportCategory.PUNISHMENTS, 1, "already expired");
            return;
        }
        if (moderation == null) {
            report.unsupported(ImportCategory.PUNISHMENTS, 1, "moderation module (ModerationImportService) not available");
            return;
        }
        UUID key = punishmentKey(record.type(), record.target(), record.ip());
        if (!options.overwrite() && alreadyImported(record.source(), ImportCategory.PUNISHMENTS, key)) {
            report.skipped(ImportCategory.PUNISHMENTS, 1, "already imported from " + record.source()
                    + " (lifted punishments are not re-applied; use --overwrite)");
            return;
        }
        if (record.target() != null && isOnline(record.target())) {
            report.skipped(ImportCategory.PUNISHMENTS, 1, "player online (re-run after they log out)");
            return;
        }
        if (activeInAtrium(record)) {
            report.skipped(ImportCategory.PUNISHMENTS, 1, "an active punishment of this kind already exists in Atrium");
            return;
        }
        if (options.dryRun()) {
            if (record.type() == PunishmentType.JAIL && !jailKnown(record.jail())) {
                report.invalid(ImportCategory.PUNISHMENTS, record.label(),
                        "jail '" + record.jail() + "' exists neither in Atrium nor in the source");
                return;
            }
            report.imported(ImportCategory.PUNISHMENTS, 1);
            return;
        }
        try {
            mark(record.source(), ImportCategory.PUNISHMENTS, key);
        } catch (StepFailed failure) {
            report.failed(ImportCategory.PUNISHMENTS, record.label(), failure.getMessage());
            return;
        }
        try {
            ModerationImportService.Status status = awaitWrite(moderation.importPunishment(
                    new ModerationImportService.PunishmentImport(record.type(), record.target(), record.targetName(),
                            record.ip(), record.actor(), record.reason(), record.created(), record.expires(),
                            record.jail())), ImportCategory.PUNISHMENTS, record.type() + " of " + record.label());
            switch (status) {
                case IMPORTED -> report.imported(ImportCategory.PUNISHMENTS, 1);
                case DUPLICATE -> report.skipped(ImportCategory.PUNISHMENTS, 1,
                        "an active punishment of this kind already exists in Atrium");
                case EXPIRED -> {
                    report.skipped(ImportCategory.PUNISHMENTS, 1, "already expired");
                    unmark(record.source(), ImportCategory.PUNISHMENTS, key, record.label());
                }
                case NO_JAIL -> {
                    report.invalid(ImportCategory.PUNISHMENTS, record.label(),
                            "jail '" + record.jail() + "' does not exist in Atrium (import or create it first)");
                    unmark(record.source(), ImportCategory.PUNISHMENTS, key, record.label());
                }
                case INVALID -> {
                    report.invalid(ImportCategory.PUNISHMENTS, record.label(),
                            "rejected by the moderation module (" + record.type() + ")");
                    unmark(record.source(), ImportCategory.PUNISHMENTS, key, record.label());
                }
            }
        } catch (StepFailed failure) {
            report.failed(ImportCategory.PUNISHMENTS, record.label(), failure.getMessage());
            unmark(record.source(), ImportCategory.PUNISHMENTS, key, record.label());
        }
    }

    private boolean jailKnown(String jail) {
        if (moderation != null && !moderation.jailNames().isEmpty() && (jail == null || jail.isBlank())) {
            return true;
        }
        String wanted = jail == null ? "" : jail.toLowerCase(Locale.ROOT);
        if (sourceJails.contains(wanted) || (wanted.isEmpty() && !sourceJails.isEmpty())) {
            return true;
        }
        return moderation != null && moderation.jailNames().stream().anyMatch(name -> name.equalsIgnoreCase(wanted));
    }

    /** Read-only duplicate check so a dry run counts like the real run. */
    private boolean activeInAtrium(PunishmentRecord record) {
        if (punishments == null) {
            return false;
        }
        try {
            if (record.type() == PunishmentType.IPBAN) {
                return await(punishments.activeIpBan(record.ip()), ImportCategory.PUNISHMENTS).isPresent();
            }
            Instant now = Instant.now();
            List<Punishment> active = await(punishments.active(record.target()), ImportCategory.PUNISHMENTS);
            return active.stream().anyMatch(p -> record.type().group().contains(p.type()) && p.isActive(now));
        } catch (StepFailed failure) {
            return false; // the import service checks again before it writes
        }
    }

    /** Imports {@code banned-players.json} and {@code banned-ips.json} under the ledger source {@code vanilla}. */
    protected void importVanillaBans(Path serverFolder) {
        for (String file : List.of("banned-players.json", "banned-ips.json")) {
            Path path = serverFolder == null ? null : serverFolder.resolve(file);
            if (path == null || !Files.isRegularFile(path)) {
                continue;
            }
            List<VanillaBanParser.Ban> bans;
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                bans = file.equals("banned-ips.json") ? VanillaBanParser.parseIps(json) : VanillaBanParser.parsePlayers(json);
            } catch (IOException | RuntimeException e) {
                ctx.logger().error("Reading {} failed", path, e);
                report.failed(ImportCategory.PUNISHMENTS, file, "cannot read file: " + ImportJob.describe(e));
                continue;
            }
            report.note("vanilla " + file + ": " + bans.size() + " entr" + (bans.size() == 1 ? "y" : "ies"));
            beginPhase("vanilla " + file, bans.size());
            int processed = 0;
            for (VanillaBanParser.Ban ban : bans) {
                if (isCancelled()) {
                    return;
                }
                processed++;
                if (!ban.ok()) {
                    importPunishment(new PunishmentRecord("vanilla", PunishmentType.BAN, null, null, null, null, null,
                            null, null, null, "vanilla:" + file, ban.problem()));
                    continue;
                }
                PunishmentType type = ban.uuid() == null ? PunishmentType.IPBAN
                        : ban.expires() == null ? PunishmentType.BAN : PunishmentType.TEMPBAN;
                String actor = ban.source() == null || ban.source().isBlank() ? null : ban.source();
                importPunishment(new PunishmentRecord("vanilla", type, ban.uuid(), ban.name(), ban.ip(), actor,
                        ban.reason(), ban.created() == null ? Instant.now() : ban.created(), ban.expires(), null,
                        "vanilla:" + ban.label(), null));
            }
            if (processed > 0) {
                reportProgress(processed);
            }
        }
    }

    // ---- per-player extras ----

    /** Adds one imported {@code /back} entry; ledger category {@link ImportCategory#LOCATIONS}. */
    protected void importBack(UUID uuid, String label, StoredLocation raw, long recordedAt) {
        if (!options.includes(ImportCategory.LOCATIONS) || raw == null) {
            return;
        }
        report.found(ImportCategory.LOCATIONS, 1);
        StoredLocation location = byName(raw);
        String problem = worldProblem(location);
        if (problem != null) {
            report.invalid(ImportCategory.LOCATIONS, label, problem);
            return;
        }
        if (backAdmin == null) {
            report.unsupported(ImportCategory.LOCATIONS, 1, "teleport module (BackHistoryAdminService) not available");
            return;
        }
        if (!options.overwrite() && alreadyImported(ledgerSource(), ImportCategory.LOCATIONS, uuid)) {
            report.skipped(ImportCategory.LOCATIONS, 1, "already imported (use --overwrite)");
            return;
        }
        if (isOnline(uuid)) {
            report.skipped(ImportCategory.LOCATIONS, 1, "player online (re-run after they log out)");
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.LOCATIONS, 1);
            return;
        }
        try {
            mark(ledgerSource(), ImportCategory.LOCATIONS, uuid);
            boolean added = awaitWrite(backAdmin.addBackLocation(uuid, location, recordedAt), ImportCategory.LOCATIONS,
                    "/back entry of " + label);
            if (added) {
                report.imported(ImportCategory.LOCATIONS, 1);
            } else {
                report.skipped(ImportCategory.LOCATIONS, 1, "already the newest /back entry");
            }
        } catch (StepFailed failure) {
            report.failed(ImportCategory.LOCATIONS, label, failure.getMessage());
            unmark(ledgerSource(), ImportCategory.LOCATIONS, uuid, label);
        }
    }

    /** Imports a player's mail; ledger category {@link ImportCategory#MAIL}, identical mail is never stored twice. */
    protected void importMail(UUID uuid, String label, List<EssentialsUserParser.ParsedMail> mail, long fallbackTime) {
        if (!options.includes(ImportCategory.MAIL) || mail.isEmpty()) {
            return;
        }
        report.found(ImportCategory.MAIL, mail.size());
        long now = System.currentTimeMillis();
        List<MailAdminService.ImportedMail> keep = new ArrayList<>();
        int expired = 0;
        for (EssentialsUserParser.ParsedMail message : mail) {
            if (message.expires() > 0 && message.expires() <= now) {
                expired++;
                continue;
            }
            long created = message.created() > 0 ? message.created() : fallbackTime;
            keep.add(new MailAdminService.ImportedMail(message.sender(), message.senderName(), message.message(),
                    created, message.read()));
        }
        report.skipped(ImportCategory.MAIL, expired, "mail already expired");
        if (keep.isEmpty()) {
            return;
        }
        if (mailAdmin == null) {
            report.unsupported(ImportCategory.MAIL, keep.size(), "mail module (MailAdminService) not available");
            return;
        }
        if (!options.overwrite() && alreadyImported(ledgerSource(), ImportCategory.MAIL, uuid)) {
            report.skipped(ImportCategory.MAIL, keep.size(), "mailbox already imported (use --overwrite)");
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.MAIL, keep.size());
            return;
        }
        try {
            mark(ledgerSource(), ImportCategory.MAIL, uuid);
            int stored = awaitWrite(mailAdmin.importMail(uuid, keep), ImportCategory.MAIL, "mail of " + label);
            report.imported(ImportCategory.MAIL, stored);
            report.skipped(ImportCategory.MAIL, keep.size() - stored, "identical mail already in the mailbox");
        } catch (StepFailed failure) {
            report.failed(ImportCategory.MAIL, keep.size(), label, failure.getMessage());
            unmark(ledgerSource(), ImportCategory.MAIL, uuid, label);
        }
    }

    /** Path of the server folder (where the vanilla ban lists live). */
    protected Path serverFolder() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("banned-players.json")) || Files.isRegularFile(cwd.resolve("server.properties"))) {
            return cwd;
        }
        try {
            return ctx.plugin().getServer().getWorldContainer().toPath().toAbsolutePath();
        } catch (RuntimeException e) {
            return cwd;
        }
    }

    /** Small helper for parsers that only return an optional. */
    protected static <T> T orNull(Optional<T> value) {
        return value.orElse(null);
    }
}
