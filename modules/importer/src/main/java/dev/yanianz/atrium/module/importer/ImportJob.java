package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.economy.EconomyAccounts;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.holograms.HologramService;
import dev.yanianz.atrium.api.homes.HomeService;
import dev.yanianz.atrium.api.homes.HomeService.HomeInfo;
import dev.yanianz.atrium.api.nick.NickService;
import dev.yanianz.atrium.api.playtime.PlaytimeAdminService;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.warps.WarpAdminService;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * The shared pipeline of one import run: service lookup, cancel, per-user all-or-nothing writes, progress
 * with a percentage and an ETA, the final summary table and the report file. A source subclass only reads
 * its own format and hands {@link UserRecord}s to {@link #processUser(UserRecord)}.
 *
 * <p>Everything after {@link #start()} happens on the I/O executor; no world or entity is touched. Writes
 * go through the public services of the other modules, and a user's changes are rolled back when a later
 * step fails, so a user is never left with only half of their records.
 *
 * <p><b>Online players are refused, not written.</b> Their data changes on their own thread at any moment
 * (homes, nick, playtime session), so an import write and especially a rollback would race with them. A
 * refused player is listed in the report and is picked up by the next run once they are offline (only
 * players already imported are skipped). For an offline player every previous value is read right before
 * its write, and a rollback undoes only what this import wrote, and only while the stored value still
 * equals what was written.
 *
 * <p>Balances and playtime are absolute values: {@link ImportLedger} remembers who was already imported
 * per source and category, so a re-run never applies them twice unless {@code --overwrite} is given.
 */
abstract class ImportJob {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);
    /** How often a running write is polled; the write is never abandoned. */
    private static final long WRITE_POLL_MILLIS = 5_000L;
    /** How often a running write is mentioned in the log. */
    private static final long WRITE_NOTICE_MILLIS = 30_000L;

    protected final ModuleContext ctx;
    protected final ImporterConfig config;
    protected final ImportOptions options;
    protected final CommandSender sender;
    protected final ImportReport report = new ImportReport();
    protected final ImportProgress progress = new ImportProgress();
    private final Set<String> seenNicknames = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean finished;
    private volatile String reportPath;
    private volatile Status lastStatus;

    protected EconomyService economy;
    protected EconomyAccounts economyAccounts;
    protected HomeService homes;
    protected WarpService warps;
    protected WarpAdminService warpAdmin;
    protected NickService nicks;
    protected PlaytimeService playtime;
    protected PlaytimeAdminService playtimeAdmin;
    protected HologramService holograms;

    protected volatile String sourceDescription = "not opened";
    private boolean notedHomesMissing;
    private boolean notedNickMissing;
    private boolean notedEconomyMissing;
    private boolean notedWarpsMissing;
    private boolean notedWarpReadMissing;
    private boolean notedPlaytimeMissing;
    private boolean notedPlaytimeReadMissing;
    private boolean notedHologramsMissing;
    private boolean notedAccountsMissing;
    private boolean notedExistingAccounts;
    private boolean notedOnlineRefused;
    private ImportLedger ledger;
    /** UUIDs already imported from this source, per ledger category; loaded once, then kept in sync. */
    private final Map<ImportCategory, Set<UUID>> done = new EnumMap<>(ImportCategory.class);

    ImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender) {
        this.ctx = ctx;
        this.config = config;
        this.options = options;
        this.sender = sender;
    }

    /** Display name used in logs, report titles and messages, e.g. {@code CMI}. */
    protected abstract String sourceName();

    /** File-name prefix of the report, e.g. {@code cmi} → {@code import-cmi-...log}. */
    protected abstract String reportPrefix();

    /** Reads the source and imports it. Runs on the I/O executor and may throw. */
    protected abstract void run() throws Exception;

    void start() {
        publishStatus();
        ctx.scheduler().supplyIo(this::execute).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("{} import job ended unexpectedly", sourceName(), error);
            }
        });
    }

    void cancel() {
        cancelled.set(true);
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    boolean isFinished() {
        return finished;
    }

    /** Immutable snapshot for {@code /atrium-import status}; safe to read from any thread. */
    Status status() {
        Status current = lastStatus;
        if (current == null) {
            return buildStatus();
        }
        return current;
    }

    // ---- run ----

    private Void execute() {
        boolean wasCancelled;
        String writtenReport = null;
        try {
            resolveServices();
            run();
        } catch (Exception e) {
            String message = describe(e);
            ctx.logger().error("{} import from {} failed", sourceName(), sourceDescription, e);
            report.failed(ImportCategory.SOURCE, "import", message);
            send("importer.failed", Msg.unparsed("source", sourceName()), Msg.unparsed("reason", message));
        } finally {
            wasCancelled = cancelled.get();
            writtenReport = writeReport(wasCancelled);
            reportPath = writtenReport;
            progress.complete();
            finished = true;
            publishStatus();
            sendSummary(wasCancelled, writtenReport);
        }
        return null;
    }

    protected void resolveServices() {
        economy = ctx.services().get(EconomyService.class).orElse(null);
        economyAccounts = ctx.services().get(EconomyAccounts.class).orElse(null);
        homes = ctx.services().get(HomeService.class).orElse(null);
        warps = ctx.services().get(WarpService.class).orElse(null);
        warpAdmin = ctx.services().get(WarpAdminService.class).orElse(null);
        nicks = ctx.services().get(NickService.class).orElse(null);
        playtime = ctx.services().get(PlaytimeService.class).orElse(null);
        playtimeAdmin = ctx.services().get(PlaytimeAdminService.class).orElse(null);
        holograms = ctx.services().get(HologramService.class).orElse(null);
        if (options.includes(ImportCategory.HOMES) && homes == null) {
            report.note("HomeService is unavailable; homes were not imported");
        }
        if (options.includes(ImportCategory.NICKS) && nicks == null) {
            report.note("NickService is unavailable; nicknames were not imported");
        }
        if (options.includes(ImportCategory.ECONOMY) && economy == null) {
            report.note("EconomyService is unavailable; balances were not imported");
        }
        if (options.includes(ImportCategory.WARPS) && warpAdmin == null) {
            report.note("WarpAdminService is unavailable; warps are counted but not imported");
        }
        if (options.includes(ImportCategory.HOLOGRAMS) && holograms == null) {
            report.note("HologramService is unavailable; holograms were not imported");
        }
        if (options.includes(ImportCategory.PLAYTIME) && playtimeAdmin == null) {
            report.note("PlaytimeAdminService is unavailable; playtime is reported but not imported"
                    + (playtime == null ? " (PlaytimeService is also unavailable)" : ""));
        }
        // Fail closed: without the markers a re-run could apply balances a second time.
        ledger = new ImportLedger(ctx.database());
        for (ImportCategory category : List.of(ImportCategory.ECONOMY, ImportCategory.PLAYTIME)) {
            if (options.includes(category)) {
                done.put(category, new HashSet<>(await(ledger.load(reportPrefix(), category), ImportCategory.SOURCE)));
            }
        }
    }

    private boolean alreadyImported(ImportCategory category, UUID uuid) {
        Set<UUID> set = done.get(category);
        return set != null && set.contains(uuid);
    }

    private void mark(ImportCategory category, UUID uuid) {
        awaitWrite(ledger.mark(reportPrefix(), category, uuid), category, "import marker");
        done.computeIfAbsent(category, ignored -> new HashSet<>()).add(uuid);
    }

    /** Best effort: a marker that cannot be removed only makes a re-run skip that player (no money minted). */
    private void unmark(ImportCategory category, UUID uuid, String label) {
        try {
            awaitWrite(ledger.unmark(reportPrefix(), category, uuid), category, "import marker removal");
            Set<UUID> set = done.get(category);
            if (set != null) {
                set.remove(uuid);
            }
        } catch (StepFailed failure) {
            ctx.logger().error("Removing the {} import marker of {} failed; a re-run needs --overwrite for it",
                    category.key(), label, failure);
            report.note("Import marker of " + label + " (" + category.key() + ") could not be removed; "
                    + "re-run with --overwrite to import it");
        }
    }

    private boolean isOnline(UUID uuid) {
        try {
            return ctx.plugin().getServer().getPlayer(uuid) != null;
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot check whether {} is online; treating them as online", uuid, e);
            return true;
        }
    }

    // ---- per-user pipeline ----

    /** One user decoded from a foreign source, independent of the source format. */
    protected record UserRecord(UUID uuid, String label, List<ImportHome> homes, String nickname,
            BigDecimal balance, String balanceProblem, long playtimeSeconds, boolean playtimeUnavailable) {}

    protected void processUser(UserRecord user) {
        if (user.uuid() == null) {
            report.invalid(ImportCategory.SOURCE, user.label(), "invalid player UUID");
            return;
        }
        try {
            UserPlan plan = planUser(user);
            if (options.dryRun()) {
                countPlanned(plan);
            } else {
                applyUser(plan);
            }
        } catch (StepFailed failure) {
            report.failed(failure.category, user.label(), failure.getMessage());
            ctx.logger().warn("{} import: {} for {}", sourceName(), failure.getMessage(), user.label(), failure);
        }
    }

    private UserPlan planUser(UserRecord user) {
        return new UserPlan(user.uuid(), user.label(), planHomes(user), planNick(user),
                planBalance(user.uuid(), user.balance(), user.label(), user.balanceProblem()), planPlaytime(user));
    }

    private HomeChange planHomes(UserRecord user) {
        if (!options.includes(ImportCategory.HOMES)) {
            return HomeChange.none();
        }
        List<ImportHome> parsed = user.homes();
        if (parsed == null || parsed.isEmpty()) {
            return HomeChange.none();
        }
        List<HomeInfo> incoming = new ArrayList<>();
        for (ImportHome home : parsed) {
            report.found(ImportCategory.HOMES, 1);
            if (!home.ok()) {
                report.invalid(ImportCategory.HOMES, user.label(), home.problem());
                continue;
            }
            if (config.checkWorlds && !worldExists(home.location().world())) {
                report.invalid(ImportCategory.HOMES, user.label(),
                        "home '" + home.name() + "' is in missing world '" + home.location().world() + "'");
                continue;
            }
            incoming.add(new HomeInfo(home.name(), home.location(), 0L));
        }
        if (incoming.isEmpty()) {
            return HomeChange.none();
        }
        if (homes == null) {
            report.unsupported(ImportCategory.HOMES, incoming.size());
            if (!notedHomesMissing) {
                notedHomesMissing = true;
                report.note("HomeService unavailable: homes were counted but not imported");
            }
            return HomeChange.none();
        }
        List<HomeInfo> existing = await(homes.homes(user.uuid()), ImportCategory.HOMES);
        ImportPlanner.Merge merge = ImportPlanner.mergeHomes(existing, incoming, options.overwrite());
        report.skipped(ImportCategory.HOMES, merge.skipped());
        for (String problem : merge.problems()) {
            report.invalid(ImportCategory.HOMES, user.label(), problem);
        }
        int changes = merge.created() + merge.replaced();
        if (changes == 0) {
            return HomeChange.none();
        }
        return new HomeChange(true, List.copyOf(incoming), changes);
    }

    private NickChange planNick(UserRecord user) {
        if (!options.includes(ImportCategory.NICKS)) {
            return NickChange.none();
        }
        if (user.nickname() == null || user.nickname().isBlank()) {
            return NickChange.none();
        }
        report.found(ImportCategory.NICKS, 1);
        String nickname = LegacyText.toMiniMessage(user.nickname()).trim();
        if (nickname.isEmpty()) {
            report.invalid(ImportCategory.NICKS, user.label(), "nickname is empty after colour conversion");
            return NickChange.none();
        }
        if (nicks == null) {
            report.unsupported(ImportCategory.NICKS, 1);
            if (!notedNickMissing) {
                notedNickMissing = true;
                report.note("NickService unavailable: nicknames were counted but not imported");
            }
            return NickChange.none();
        }
        if (nicks.validate(nickname) != NickService.Result.SUCCESS) {
            report.invalid(ImportCategory.NICKS, user.label(),
                    "nickname rejected by the nick module rules: " + LegacyText.plain(nickname));
            return NickChange.none();
        }
        String visible = LegacyText.plain(nickname).toLowerCase(Locale.ROOT);
        if (!seenNicknames.add(visible)) {
            report.invalid(ImportCategory.NICKS, user.label(),
                    "duplicate nickname in the " + sourceName() + " data: " + visible);
            return NickChange.none();
        }
        NickService.NickInfo original = await(nicks.nick(user.uuid()), ImportCategory.NICKS).orElse(null);
        ImportPlanner.NickPlan plan = ImportPlanner.planNick(original != null, true, options.overwrite());
        if (plan.action() == ImportPlanner.Action.SKIP) {
            report.skipped(ImportCategory.NICKS, 1);
            return NickChange.none();
        }
        return new NickChange(true, nickname);
    }

    /**
     * @param parsed      the balance, already parsed by the source, or {@code null} when the source has
     *                    no balance for this user
     * @param sourceLabel short description of the raw value, used when it was malformed
     */
    private BalanceChange planBalance(UUID uuid, BigDecimal parsed, String label, String sourceLabel) {
        if (!options.includes(ImportCategory.ECONOMY)) {
            return BalanceChange.none();
        }
        if (parsed == null) {
            if (sourceLabel != null) {
                report.found(ImportCategory.ECONOMY, 1);
                report.invalid(ImportCategory.ECONOMY, label,
                        "invalid balance (negative, non-finite or malformed): " + shorten(sourceLabel, 160));
            }
            return BalanceChange.none();
        }
        report.found(ImportCategory.ECONOMY, 1);
        if (economy == null) {
            report.unsupported(ImportCategory.ECONOMY, 1);
            if (!notedEconomyMissing) {
                notedEconomyMissing = true;
                report.note("EconomyService unavailable: balances were counted but not imported");
            }
            return BalanceChange.none();
        }
        // EconomyService.balance() reports the starting balance for a missing account, so the decision is
        // made on account existence (EconomyAccounts), never on the balance value.
        boolean exists;
        if (economyAccounts != null) {
            exists = await(economyAccounts.hasAccount(uuid), ImportCategory.ECONOMY);
        } else if (options.overwrite()) {
            exists = true;
        } else {
            report.unsupported(ImportCategory.ECONOMY, 1);
            if (!notedAccountsMissing) {
                notedAccountsMissing = true;
                report.note("EconomyAccounts unavailable: existing accounts cannot be told apart from new ones, "
                        + "so balances are imported only with --overwrite");
            }
            return BalanceChange.none();
        }
        ImportPlanner.BalancePlan plan = ImportPlanner.planBalance(exists,
                alreadyImported(ImportCategory.ECONOMY, uuid), parsed, options.overwrite());
        if (plan.action() == ImportPlanner.Action.INVALID) {
            report.invalid(ImportCategory.ECONOMY, label, plan.problem());
            return BalanceChange.none();
        }
        if (plan.action() == ImportPlanner.Action.SKIP) {
            report.skipped(ImportCategory.ECONOMY, 1);
            noteExistingAccount(exists);
            return BalanceChange.none();
        }
        return new BalanceChange(true, parsed);
    }

    private void noteExistingAccount(boolean exists) {
        if (exists && !notedExistingAccounts) {
            notedExistingAccounts = true;
            report.note("Balances of players who already have an Atrium economy account (or were already imported "
                    + "from this source) were skipped; use --overwrite to replace them");
        }
    }

    private PlaytimeChange planPlaytime(UserRecord user) {
        if (!options.includes(ImportCategory.PLAYTIME) || user.playtimeUnavailable()
                || user.playtimeSeconds() <= 0) {
            return PlaytimeChange.none();
        }
        report.found(ImportCategory.PLAYTIME, 1);
        report.extra(ImportCategory.PLAYTIME, user.playtimeSeconds());
        if (playtimeAdmin == null) {
            report.unsupported(ImportCategory.PLAYTIME, 1);
            if (!notedPlaytimeMissing) {
                notedPlaytimeMissing = true;
                report.note("PlaytimeAdminService unavailable: playtime was counted but not imported");
            }
            return PlaytimeChange.none();
        }
        long existing = 0L;
        if (playtime == null) {
            if (!notedPlaytimeReadMissing) {
                notedPlaytimeReadMissing = true;
                report.note("PlaytimeService unavailable: existing playtime cannot be checked, so an "
                        + "existing total may be replaced");
            }
        } else {
            existing = await(playtime.totalPlaytime(user.uuid()), ImportCategory.PLAYTIME);
        }
        ImportPlanner.PlaytimePlan plan = ImportPlanner.planPlaytime(existing,
                alreadyImported(ImportCategory.PLAYTIME, user.uuid()), user.playtimeSeconds(), options.overwrite());
        if (plan.action() == ImportPlanner.Action.INVALID) {
            report.invalid(ImportCategory.PLAYTIME, user.label(), plan.problem());
            return PlaytimeChange.none();
        }
        if (plan.action() == ImportPlanner.Action.SKIP) {
            report.skipped(ImportCategory.PLAYTIME, 1);
            return PlaytimeChange.none();
        }
        return new PlaytimeChange(true, plan.seconds());
    }

    private void countPlanned(UserPlan plan) {
        if (plan.playtime().active()) {
            report.imported(ImportCategory.PLAYTIME, 1);
        }
        if (plan.homes().active()) {
            report.imported(ImportCategory.HOMES, plan.homes().changes());
        }
        if (plan.nick().active()) {
            report.imported(ImportCategory.NICKS, 1);
        }
        if (plan.balance().active()) {
            report.imported(ImportCategory.ECONOMY, 1);
        }
    }

    // ---- writing (with compensation) ----

    private void applyUser(UserPlan plan) {
        UUID uuid = plan.uuid();
        String label = plan.label();
        // Re-checked right before the first write; see the class comment for why online players are refused.
        if (isOnline(uuid)) {
            refuseOnline(plan);
            return;
        }
        Long playtimeBefore = null;
        boolean playtimeMarked = false;
        boolean playtimeWritten = false;
        NickService.NickInfo nickBefore = null;
        ImportPlanner.Applied nickApplied = ImportPlanner.Applied.NO;
        List<ImportPlanner.HomeUndo> homeUndo = List.of();
        int homesWritten = 0;
        boolean balanceMarked = false;
        boolean balanceWritten = false;
        try {
            // Playtime is written first; it is the cheapest step to compensate.
            if (plan.playtime().active()) {
                playtimeBefore = playtime == null ? null : await(playtime.totalPlaytime(uuid), ImportCategory.PLAYTIME);
                mark(ImportCategory.PLAYTIME, uuid);
                playtimeMarked = true;
                awaitWrite(playtimeAdmin.setPlaytime(uuid, Duration.ofSeconds(plan.playtime().seconds())),
                        ImportCategory.PLAYTIME, "playtime of " + label);
                playtimeWritten = true;
            }
            if (plan.nick().active()) {
                nickBefore = await(nicks.nick(uuid), ImportCategory.NICKS).orElse(null);
                // From here until the result is known the write may or may not have been stored.
                nickApplied = ImportPlanner.Applied.UNKNOWN;
                NickService.Result result = awaitWrite(nicks.setNick(uuid, plan.nick().nickname(),
                        options.overwrite()), ImportCategory.NICKS, "nickname of " + label);
                nickApplied = ImportPlanner.nickApplied(result);
                if (result == NickService.Result.SKIPPED) {
                    report.skipped(ImportCategory.NICKS, 1);
                } else if (result != NickService.Result.SUCCESS) {
                    throw new StepFailed(ImportCategory.NICKS, "nickname was not stored: " + result);
                }
            }
            if (plan.homes().active()) {
                // Merge against the homes stored now, not the planning snapshot, so nothing newer is lost.
                List<HomeInfo> before = await(homes.homes(uuid), ImportCategory.HOMES);
                ImportPlanner.Merge merge = ImportPlanner.mergeHomes(before, plan.homes().incoming(),
                        options.overwrite());
                int changes = merge.created() + merge.replaced();
                if (changes > 0) {
                    homeUndo = merge.undo();
                    HomeService.Result result = awaitWrite(homes.replaceHomes(uuid, merge.target()),
                            ImportCategory.HOMES, "homes of " + label);
                    if (result != HomeService.Result.SUCCESS) {
                        throw new StepFailed(ImportCategory.HOMES, "homes were not stored: " + result);
                    }
                    homesWritten = changes;
                }
            }
            if (plan.balance().active()) {
                if (!options.overwrite() && economyAccounts != null
                        && await(economyAccounts.hasAccount(uuid), ImportCategory.ECONOMY)) {
                    // The account appeared since planning: existing money is never replaced without overwrite.
                    report.skipped(ImportCategory.ECONOMY, 1);
                    noteExistingAccount(true);
                } else {
                    mark(ImportCategory.ECONOMY, uuid);
                    balanceMarked = true;
                    TransactionResult result = awaitWrite(economy.set(uuid, plan.balance().amount(),
                            "import:" + reportPrefix()), ImportCategory.ECONOMY, "balance of " + label);
                    if (!result.success()) {
                        throw new StepFailed(ImportCategory.ECONOMY, "balance was not stored: " + result.status());
                    }
                    balanceWritten = true;
                }
            }
            if (playtimeWritten) {
                report.imported(ImportCategory.PLAYTIME, 1);
            }
            if (nickApplied == ImportPlanner.Applied.YES) {
                report.imported(ImportCategory.NICKS, 1);
            }
            if (homesWritten > 0) {
                report.imported(ImportCategory.HOMES, homesWritten);
            }
            if (balanceWritten) {
                report.imported(ImportCategory.ECONOMY, 1);
            }
        } catch (StepFailed failure) {
            String reason = failure.getMessage();
            report.failed(failure.category, label, reason);
            ctx.logger().warn("{} import: {} for {}; rolling back this import's earlier steps", sourceName(),
                    reason, label, failure);
            // Balance is the last step and a failed set stores nothing, so only its marker is removed.
            if (balanceMarked && !balanceWritten) {
                unmark(ImportCategory.ECONOMY, uuid, label);
            }
            if (!homeUndo.isEmpty()) {
                rollbackHomes(uuid, label, reason, homeUndo);
            }
            // Only a nick write that happened (or may have happened) is undone: a SKIPPED/TAKEN result never
            // touched the player's real nickname.
            if (nickApplied != ImportPlanner.Applied.NO) {
                rollbackNick(uuid, label, reason, nickBefore, plan.nick().nickname(), nickApplied);
            }
            if (playtimeMarked) {
                rollbackPlaytime(uuid, label, reason, playtimeBefore, plan.playtime().seconds(),
                        playtimeWritten ? ImportPlanner.Applied.YES : ImportPlanner.Applied.UNKNOWN);
            }
        }
    }

    private void refuseOnline(UserPlan plan) {
        String message = "player is online; not imported (run the import again after they log out)";
        boolean any = false;
        if (plan.playtime().active()) {
            report.skipped(ImportCategory.PLAYTIME, 1);
            report.issue(new ImportIssue(ImportCategory.PLAYTIME, plan.label(), message));
            any = true;
        }
        if (plan.nick().active()) {
            report.skipped(ImportCategory.NICKS, 1);
            report.issue(new ImportIssue(ImportCategory.NICKS, plan.label(), message));
            any = true;
        }
        if (plan.homes().active()) {
            report.skipped(ImportCategory.HOMES, plan.homes().changes());
            report.issue(new ImportIssue(ImportCategory.HOMES, plan.label(), message));
            any = true;
        }
        if (plan.balance().active()) {
            report.skipped(ImportCategory.ECONOMY, 1);
            report.issue(new ImportIssue(ImportCategory.ECONOMY, plan.label(), message));
            any = true;
        }
        if (any && !notedOnlineRefused) {
            notedOnlineRefused = true;
            report.note("Players who were online were not changed; re-run the import once they are offline "
                    + "(players already imported are skipped)");
        }
    }

    /**
     * Restores the previous total only while the stored total is still the one this import wrote. Without
     * {@link PlaytimeService} the value cannot be verified, so it is left in place and reported.
     */
    private void rollbackPlaytime(UUID uuid, String label, String reason, Long before, long written,
            ImportPlanner.Applied applied) {
        try {
            if (before == null) {
                if (applied == ImportPlanner.Applied.YES) {
                    report.failed(ImportCategory.PLAYTIME, label, "not rolled back (PlaytimeService unavailable "
                            + "to verify the stored total) after: " + reason);
                } else {
                    unmark(ImportCategory.PLAYTIME, uuid, label);
                }
                return;
            }
            Long current = await(playtime.totalPlaytime(uuid), ImportCategory.PLAYTIME);
            if (ImportPlanner.shouldRestore(applied, current, written)) {
                awaitWrite(playtimeAdmin.setPlaytime(uuid, Duration.ofSeconds(before)),
                        ImportCategory.PLAYTIME, "rollback of playtime for " + label);
                unmark(ImportCategory.PLAYTIME, uuid, label);
                report.failed(ImportCategory.PLAYTIME, label, "rolled back after: " + reason);
            } else if (applied != ImportPlanner.Applied.YES) {
                // The write did not land: nothing to undo, and the player was not imported.
                unmark(ImportCategory.PLAYTIME, uuid, label);
            } else {
                report.failed(ImportCategory.PLAYTIME, label,
                        "not rolled back: the total changed since the import wrote it; after: " + reason);
            }
        } catch (StepFailed rollback) {
            ctx.logger().error("Rolling back playtime of {} failed", label, rollback);
            report.failed(ImportCategory.PLAYTIME, label, "rollback failed: " + rollback.getMessage());
        }
    }

    private void rollbackHomes(UUID uuid, String label, String reason, List<ImportPlanner.HomeUndo> undo) {
        int count = undo.size();
        try {
            List<HomeInfo> current = await(homes.homes(uuid), ImportCategory.HOMES);
            ImportPlanner.HomeRollback plan = ImportPlanner.rollbackHomes(current, undo);
            if (plan.target() == null) {
                if (plan.kept() > 0) {
                    ctx.logger().info("{} import: nothing of {}'s homes to roll back ({} not written or changed since)",
                            sourceName(), label, plan.kept());
                }
                return;
            }
            HomeService.Result result = awaitWrite(homes.replaceHomes(uuid, plan.target()),
                    ImportCategory.HOMES, "rollback of homes for " + label);
            String kept = plan.kept() > 0 ? "; kept " + plan.kept() + " changed since the import" : "";
            if (result == HomeService.Result.SUCCESS) {
                report.failed(ImportCategory.HOMES, count, label,
                        "rolled back " + plan.restored() + " home(s)" + kept + " after: " + reason);
            } else {
                report.failed(ImportCategory.HOMES, count, label, "rollback failed (" + result + ") after: " + reason);
            }
        } catch (StepFailed rollback) {
            ctx.logger().error("Rolling back homes of {} failed", label, rollback);
            report.failed(ImportCategory.HOMES, count, label, "rollback failed: " + rollback.getMessage());
        }
    }

    private void rollbackNick(UUID uuid, String label, String reason, NickService.NickInfo before, String written,
            ImportPlanner.Applied applied) {
        try {
            String current = await(nicks.nick(uuid), ImportCategory.NICKS)
                    .map(NickService.NickInfo::nickname).orElse(null);
            if (!ImportPlanner.shouldRestore(applied, current, written)) {
                return;
            }
            NickService.Result result = before == null
                    ? awaitWrite(nicks.clearNick(uuid), ImportCategory.NICKS, "rollback of nickname for " + label)
                    : awaitWrite(nicks.setNick(uuid, before.nickname(), true),
                            ImportCategory.NICKS, "rollback of nickname for " + label);
            if (result == NickService.Result.SUCCESS) {
                report.failed(ImportCategory.NICKS, label, "rolled back after: " + reason);
            } else {
                report.failed(ImportCategory.NICKS, label, "rollback failed (" + result + ") after: " + reason);
            }
        } catch (StepFailed rollback) {
            ctx.logger().error("Rolling back nickname of {} failed", label, rollback);
            report.failed(ImportCategory.NICKS, label, "rollback failed: " + rollback.getMessage());
        }
    }

    // ---- warps ----

    /** One warp decoded from a foreign source. */
    protected record WarpRecord(String name, String category, StoredLocation location, String problem) {}

    protected void importWarps(String phase, List<WarpRecord> parsed) {
        Set<String> existing = warps == null ? Set.of()
                : warps.names().stream().map(name -> name.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
        if (warps == null && warpAdmin != null && !notedWarpReadMissing) {
            notedWarpReadMissing = true;
            report.note("WarpService unavailable: existing warps cannot be checked, so a dry run may overcount");
        }
        beginPhase(phase, parsed.size());
        int processed = 0;
        for (WarpRecord warp : parsed) {
            if (cancelled.get()) {
                break;
            }
            report.found(ImportCategory.WARPS, 1);
            if (warp.problem() != null) {
                report.invalid(ImportCategory.WARPS, warp.name(), warp.problem());
            } else if (config.checkWorlds && !worldExists(warp.location().world())) {
                report.invalid(ImportCategory.WARPS, warp.name(),
                        "warp is in missing world '" + warp.location().world() + "'");
            } else {
                importWarp(warp, existing);
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

    private void importWarp(WarpRecord warp, Set<String> existing) {
        boolean exists = existing.contains(warp.name().toLowerCase(Locale.ROOT));
        ImportPlanner.WarpPlan plan = ImportPlanner.planWarp(exists, true, options.overwrite());
        if (plan.action() == ImportPlanner.Action.SKIP) {
            report.skipped(ImportCategory.WARPS, 1);
            return;
        }
        if (warpAdmin == null) {
            report.unsupported(ImportCategory.WARPS, 1);
            if (!notedWarpsMissing) {
                notedWarpsMissing = true;
                report.note("WarpAdminService unavailable: warps were counted but not imported");
            }
            return;
        }
        if (options.dryRun()) {
            report.imported(ImportCategory.WARPS, 1);
            return;
        }
        // The warps module owns validation, the create event and the storage write; a Location is
        // only a short-lived handle, so a world that unloaded since the check is reported, not stored.
        Optional<Location> location = warp.location().toLocation();
        if (location.isEmpty()) {
            report.invalid(ImportCategory.WARPS, warp.name(),
                    "world '" + warp.location().world() + "' is not loaded");
            return;
        }
        try {
            boolean written = awaitWrite(warpAdmin.importWarp(warp.name(), location.get(), warp.category(),
                    null, null, options.overwrite()), ImportCategory.WARPS, "warp " + warp.name());
            if (written) {
                report.imported(ImportCategory.WARPS, 1);
            } else {
                report.skipped(ImportCategory.WARPS, 1);
            }
        } catch (StepFailed failure) {
            report.failed(ImportCategory.WARPS, warp.name(), failure.getMessage());
            ctx.logger().warn("{} warp '{}' import failed: {}", sourceName(), warp.name(), failure.getMessage());
        }
    }

    // ---- helpers ----

    protected <T> T await(CompletableFuture<T> future, ImportCategory category) {
        try {
            return future.get(config.operationTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailed(category, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new StepFailed(category, describe(cause), cause);
        } catch (TimeoutException e) {
            throw new StepFailed(category, "timed out after " + config.operationTimeoutSeconds() + " s", e);
        } catch (RuntimeException e) {
            throw new StepFailed(category, describe(e), e);
        }
    }

    /**
     * Awaits a write that is already in flight. Deliberately has no deadline: abandoning it would let the
     * write land after the rollback of the user's other steps and leave a half-imported user. Slow writes
     * are logged and then awaited to their real outcome.
     */
    protected <T> T awaitWrite(CompletableFuture<T> future, ImportCategory category, String what) {
        try {
            return ImportWaits.awaitWrite(future, WRITE_POLL_MILLIS, WRITE_NOTICE_MILLIS,
                    seconds -> ctx.logger().warn(
                            "{} import: '{}' is still running after {} s; waiting for its real result",
                            sourceName(), what, seconds));
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new StepFailed(category, describe(cause), cause);
        } catch (RuntimeException e) {
            throw new StepFailed(category, describe(e), e);
        }
    }

    protected boolean worldExists(String world) {
        if (!config.checkWorlds || world == null || world.isBlank()) {
            return true;
        }
        try {
            if (ctx.plugin().getServer().getWorld(world) != null) {
                return true;
            }
            return Files.isDirectory(ctx.plugin().getServer().getWorldContainer().toPath().resolve(world));
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot verify world '{}'; keeping the record", world, e);
            return true;
        }
    }

    protected static ConfigurationNode loadYaml(Path file) throws IOException {
        return YamlConfigurationLoader.builder().path(file).build().load();
    }

    protected static Path findFile(Path directory, String... names) {
        if (directory == null || !Files.isDirectory(directory)) {
            return null;
        }
        for (String name : names) {
            Path file = directory.resolve(name);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    // ---- progress and status ----

    /** Starts a phase; call before iterating its items. */
    protected void beginPhase(String phase, int total) {
        progress.begin(phase, total);
        publishStatus();
    }

    /** Updates the percentage/ETA and sends a progress line. */
    protected void reportProgress(int processed) {
        progress.update(processed);
        publishStatus();
        ImportProgress.Snapshot snapshot = progress.snapshot(true);
        send("importer.progress",
                Msg.unparsed("phase", snapshot.phase()),
                Msg.unparsed("percent", snapshot.percentText()),
                Msg.number("processed", snapshot.processed()),
                Msg.number("total", snapshot.total()),
                Msg.unparsed("eta", snapshot.etaText()));
    }

    private void publishStatus() {
        long imported = 0;
        long skipped = 0;
        long invalid = 0;
        long failed = 0;
        long unsupported = 0;
        for (ImportCategory category : ImportCategory.values()) {
            ImportReport.Counters counter = report.counters(category);
            imported += counter.imported;
            skipped += counter.skipped;
            invalid += counter.invalid;
            failed += counter.failed;
            unsupported += counter.unsupported;
        }
        lastStatus = new Status(sourceName(), options.dryRun(), progress.snapshot(!finished), imported, skipped,
                invalid, failed, unsupported, startedAt, reportPath);
    }

    private Status buildStatus() {
        return new Status(sourceName(), options.dryRun(), progress.snapshot(!finished), 0, 0, 0, 0, 0,
                startedAt, reportPath);
    }

    // ---- report and summary ----

    private String writeReport(boolean wasCancelled) {
        String stamp = LocalDateTime.now().format(STAMP);
        Path folder = ctx.plugin().getDataFolder().toPath();
        Path file = folder.resolve("import-" + reportPrefix() + "-" + stamp + ".log");
        try {
            Files.createDirectories(folder);
            String rendered = report.render(sourceName(), options, sourceDescription, wasCancelled,
                    System.currentTimeMillis() - startedAt, file.toString());
            Files.writeString(file, rendered);
            return file.toString();
        } catch (IOException e) {
            ctx.logger().error("Writing the {} import report {} failed", sourceName(), file, e);
            send("importer.report-failed", Msg.unparsed("reason", describe(e)));
            return null;
        }
    }

    private void sendSummary(boolean wasCancelled, String writtenReport) {
        List<ImportSummary.Row> rows = ImportSummary.rows(report, options);
        ImportSummary.Row total = rows.getLast();
        if (wasCancelled) {
            send("importer.cancelled", Msg.number("imported", total.imported()));
        }
        send("importer.summary-header", Msg.unparsed("source", sourceName()));
        send("importer.summary-line", Msg.unparsed("line", ImportSummary.header()));
        for (ImportSummary.Row row : rows) {
            send("importer.summary-line", Msg.unparsed("line", ImportSummary.format(row)));
        }
        send(options.dryRun() ? "importer.dry-run-finished" : "importer.finished",
                Msg.unparsed("source", sourceName()),
                Msg.number("imported", total.imported()),
                Msg.number("skipped", total.skipped()),
                Msg.number("invalid", total.invalid()),
                Msg.number("failed", total.failed()),
                Msg.number("unsupported", total.unsupported()));

        int shown = config.maxProblemsPerCategory();
        for (ImportCategory category : ImportCategory.values()) {
            if (category != ImportCategory.SOURCE && !options.includes(category)) {
                continue;
            }
            List<ImportIssue> problems = report.issues(category);
            for (int i = 0; i < Math.min(shown, problems.size()); i++) {
                send("importer.problem", Msg.unparsed("problem", problems.get(i).format()));
            }
            if (problems.size() > shown) {
                send("importer.more-problems", Msg.number("count", problems.size() - shown));
            }
        }
        if (writtenReport != null) {
            sendReportPath(writtenReport);
        }
    }

    /** The report path as a click-to-copy component, so an in-game admin needs no file browser. */
    private void sendReportPath(String path) {
        try {
            ctx.scheduler().global(() -> {
                Component hover = ctx.messages().render(sender, "importer.report-copy-hover");
                Component clickable = Component.text(path)
                        .clickEvent(ClickEvent.copyToClipboard(path))
                        .hoverEvent(HoverEvent.showText(hover));
                ctx.messages().send(sender, "importer.report-saved", Msg.component("path", clickable));
            });
        } catch (RuntimeException e) {
            ctx.logger().debug("Cannot send importer report path; the job is shutting down", e);
        }
    }

    protected void send(String key, TagResolver... tags) {
        try {
            ctx.scheduler().global(() -> ctx.messages().send(sender, key, tags));
        } catch (RuntimeException e) {
            ctx.logger().debug("Cannot send importer message {}; the job is shutting down", key, e);
        }
    }

    protected static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    protected static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Keeps a raw foreign value out of the report's formatting while still naming what was wrong. */
    protected static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** Snapshot of a job for {@code /atrium-import status}. */
    record Status(String source, boolean dryRun, ImportProgress.Snapshot progress, long imported, long skipped,
            long invalid, long failed, long unsupported, long startedAt, String reportPath) {
    }

    // ---- plans ----

    /** The valid incoming homes; merged against the stored homes again right before the write. */
    protected record HomeChange(boolean active, List<HomeInfo> incoming, int changes) {
        static HomeChange none() {
            return new HomeChange(false, List.of(), 0);
        }
    }

    protected record NickChange(boolean active, String nickname) {
        static NickChange none() {
            return new NickChange(false, null);
        }
    }

    protected record BalanceChange(boolean active, BigDecimal amount) {
        static BalanceChange none() {
            return new BalanceChange(false, null);
        }
    }

    protected record PlaytimeChange(boolean active, long seconds) {
        static PlaytimeChange none() {
            return new PlaytimeChange(false, 0);
        }
    }

    private record UserPlan(UUID uuid, String label, HomeChange homes, NickChange nick, BalanceChange balance,
            PlaytimeChange playtime) {}

    protected static final class StepFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final ImportCategory category;

        StepFailed(ImportCategory category, String message) {
            super(message);
            this.category = category;
        }

        StepFailed(ImportCategory category, String message, Throwable cause) {
            super(message, cause);
            this.category = category;
        }
    }
}
