package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

/**
 * Imports balances from any economy plugin registered as a Vault provider (RoyaleEconomy, XConomy,
 * EssentialsX Economy, CMI Economy, TheNewEconomy, ...). The players are every offline player the server
 * knows plus every account Atrium knows. Balances are read on the global region thread in batches of
 * {@link ImporterConfig#batchSize()} (many providers are not thread-safe) and written through
 * {@code EconomyService} with the shared per-user pipeline, so the ledger ({@code vault:<plugin>}),
 * {@code --overwrite}, the online-player rule and the report work exactly as for CMI and EssentialsX.
 */
final class EconomyImportJob extends ImportJob {
    /** Finds the provider on the global thread; separate so tests can pass a fake source. */
    interface SourceLookup extends Function<String, Optional<BalanceSource>> {}

    private final String wanted;
    private final SourceLookup lookup;
    private String ledger;
    private String prefix;

    EconomyImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender,
            String wanted, SourceLookup lookup) {
        super(ctx, config, options, sender);
        this.wanted = wanted;
        this.lookup = lookup;
        this.ledger = BalanceSource.ledgerSource(wanted);
        this.prefix = "economy-" + safe(wanted);
    }

    @Override
    protected String sourceName() {
        return "Vault economy (" + wanted + ")";
    }

    @Override
    protected String reportPrefix() {
        return prefix;
    }

    @Override
    protected String ledgerSource() {
        return ledger;
    }

    static String safe(String name) {
        String cleaned = ImportNames.sanitize(name);
        return cleaned == null ? "provider" : cleaned.toLowerCase(Locale.ROOT);
    }

    @Override
    protected void run() {
        Optional<BalanceSource> found = await(ctx.scheduler().supplyGlobal(() -> lookup.apply(wanted)), ImportCategory.SOURCE);
        if (found.isEmpty()) {
            throw new StepFailed(ImportCategory.SOURCE, "no Vault economy provider owned by '" + wanted
                    + "' (see /atrium-import sources)");
        }
        BalanceSource source = found.get();
        // The ledger is keyed by the owning plugin, whatever spelling the admin typed.
        ledger = BalanceSource.ledgerSource(source.pluginName());
        prefix = "economy-" + safe(source.pluginName());
        int digits = source.fractionalDigits();
        sourceDescription = "Vault provider '" + source.providerName() + "' of plugin " + source.pluginName()
                + " (fractional digits: " + (digits < 0 ? "not reported" : digits) + ")";
        report.note("Source: " + sourceDescription);
        report.note("Ledger source: " + ledger + " (a re-run skips players already imported unless --overwrite)");

        Map<UUID, String> players = await(ctx.scheduler().supplyGlobal(this::players), ImportCategory.SOURCE);
        report.note("Players checked: " + players.size() + " (server offline players + Atrium accounts)");
        List<Map.Entry<UUID, String>> all = new ArrayList<>(players.entrySet());
        beginPhase("balances", all.size());
        int processed = 0;
        int batchSize = config.batchSize();
        for (int from = 0; from < all.size() && !isCancelled(); from += batchSize) {
            List<Map.Entry<UUID, String>> batch = all.subList(from, Math.min(all.size(), from + batchSize));
            Map<UUID, Read> reads = await(ctx.scheduler().supplyGlobal(() -> readBatch(source, batch)), ImportCategory.ECONOMY);
            for (Map.Entry<UUID, String> player : batch) {
                if (isCancelled()) {
                    break;
                }
                handle(player.getKey(), player.getValue(), reads.get(player.getKey()), digits);
                processed++;
                if (processed % config.progressEveryUsers() == 0) {
                    reportProgress(processed);
                }
            }
        }
        if (processed > 0) {
            reportProgress(processed);
        }
    }

    /** One provider read: the balance, nothing (no account), or the provider's error. */
    record Read(Double balance, String error) {}

    /** Runs on the global thread. A provider that throws for one player does not stop the batch. */
    static Map<UUID, Read> readBatch(BalanceSource source, List<Map.Entry<UUID, String>> batch) {
        Map<UUID, Read> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, String> player : batch) {
            try {
                out.put(player.getKey(), new Read(source.balance(player.getKey(), player.getValue()).orElse(null), null));
            } catch (RuntimeException | LinkageError e) {
                out.put(player.getKey(), new Read(null, describe(e)));
            }
        }
        return out;
    }

    private void handle(UUID uuid, String name, Read read, int digits) {
        String label = name == null || name.isBlank() ? uuid.toString() : name + " (" + uuid + ")";
        if (read == null || read.error() != null) {
            report.found(ImportCategory.ECONOMY, 1);
            report.failed(ImportCategory.ECONOMY, label, "provider could not read the balance: "
                    + (read == null ? "no result" : read.error()));
            return;
        }
        if (read.balance() == null) {
            report.skipped(ImportCategory.ECONOMY, 1, "no account in " + wanted);
            return;
        }
        Optional<BigDecimal> exact = BalanceSource.toDecimal(read.balance(), digits);
        processUser(new UserRecord(uuid, label, List.of(), null, exact.orElse(null),
                exact.isPresent() ? null : String.valueOf(read.balance()), 0L, true));
    }

    /** Runs on the global thread: offline players of the server plus every account Atrium knows. */
    private Map<UUID, String> players() {
        Map<UUID, String> players = new LinkedHashMap<>();
        for (OfflinePlayer player : ctx.plugin().getServer().getOfflinePlayers()) {
            players.put(player.getUniqueId(), player.getName());
        }
        for (PlayerRef ref : ctx.users().knownAccounts(Integer.MAX_VALUE)) {
            players.putIfAbsent(ref.uuid(), ref.name());
        }
        return players;
    }
}
