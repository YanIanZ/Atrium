package dev.yanianz.atrium.module.importer;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;

/**
 * Imports CMI or EssentialsX data through the services of the other Atrium modules, and backs up the
 * SQLite database. Every read and write happens asynchronously and can be cancelled; the report path is
 * click-to-copy so an admin without console access can still open it.
 */
public final class ImporterModule implements AtriumModule {
    static final String PERM_IMPORT = "atrium.import";
    static final String PERM_STATUS = "atrium.import.status";
    static final String PERM_BACKUP = "atrium.backup";

    private ModuleContext ctx;
    private ConfigHandle<ImporterConfig> config;
    /** The current or last job; claimed with compareAndSet so two imports can never start at once. */
    private final ImportSlot<ImportJob> active = new ImportSlot<>(ImportJob::isFinished);

    @Override
    public String id() {
        return "importer";
    }

    @Override
    public String description() {
        return "CMI, EssentialsX, vanilla ban list and Vault economy importer plus a SQLite backup helper";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("economy", "homes", "warps", "nick", "playtime", "holograms", "teleport", "moderation", "kits",
                "mail", "chat", "inventories");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(ImporterConfig.class, ImporterConfig::new);
        context.migrate(ImportLedger.MIGRATIONS);

        context.permission(PERM_IMPORT, PermissionDefault.OP, "Import data from CMI, EssentialsX, vanilla ban lists or a Vault economy");
        context.permission(PERM_STATUS, PermissionDefault.OP, "Show the status of the running import");
        context.permission(PERM_BACKUP, PermissionDefault.OP, "Back up the Atrium database");

        var root = Commands.literal("atrium-import")
                .requires(source -> source.getSender().hasPermission(PERM_IMPORT)
                        || source.getSender().hasPermission(PERM_STATUS))
                .executes(this::usage)
                .then(Cmds.literal("cmi").requires(Cmds.permission(PERM_IMPORT))
                        .executes(c -> cmi(c, ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(suggestions(ImportCategory.cmi()))
                                .executes(c -> cmi(c, StringArgumentType.getString(c, "args")))))
                .then(Cmds.literal("essentials").requires(Cmds.permission(PERM_IMPORT))
                        .executes(c -> essentials(c, ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(suggestions(ImportCategory.essentials()))
                                .executes(c -> essentials(c, StringArgumentType.getString(c, "args")))))
                .then(Cmds.literal("vanilla").requires(Cmds.permission(PERM_IMPORT))
                        .executes(c -> vanilla(c, ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(suggestions(ImportCategory.vanilla()))
                                .executes(c -> vanilla(c, StringArgumentType.getString(c, "args")))))
                .then(Cmds.literal("pvx").requires(Cmds.permission(PERM_IMPORT))
                        .executes(c -> pvx(c, ""))
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests(suggestions(ImportCategory.pvx()))
                                .executes(c -> pvx(c, StringArgumentType.getString(c, "args")))))
                .then(Cmds.literal("economy").requires(Cmds.permission(PERM_IMPORT))
                        .executes(this::economyUsage)
                        .then(Commands.argument("plugin", StringArgumentType.word())
                                .suggests(this::providerSuggestions)
                                .executes(c -> economy(c, ""))
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .suggests(suggestions(ImportCategory.economyOnly()))
                                        .executes(c -> economy(c, StringArgumentType.getString(c, "args"))))))
                .then(Cmds.literal("sources").requires(Cmds.permission(PERM_IMPORT)).executes(this::sources))
                .then(Cmds.literal("status")
                        .requires(source -> source.getSender().hasPermission(PERM_STATUS)
                                || source.getSender().hasPermission(PERM_IMPORT))
                        .executes(this::status))
                .then(Cmds.literal("cancel").requires(Cmds.permission(PERM_IMPORT)).executes(this::cancel));
        context.command(CommandSpec.of(root.build(),
                "Import CMI, EssentialsX, PlayerVaultsX, vanilla ban lists or any Vault economy: balances, homes, "
                        + "warps, spawn, kits, jails, punishments, mail, ignore lists and vaults"));

        context.command(CommandSpec.of(Cmds.literal("atrium-backup", PERM_BACKUP)
                .executes(this::backup)
                .build(), "Back up the Atrium SQLite database"));
    }

    @Override
    public void disable() {
        ImportJob job = active.get();
        if (job != null) {
            job.cancel();
        }
    }

    private SuggestionProvider<CommandSourceStack> suggestions(Set<ImportCategory> allowed) {
        return (c, builder) -> {
            for (String suggestion : ImportOptions.suggestions(builder.getRemaining(), allowed)) {
                builder.suggest(suggestion);
            }
            return builder.buildFuture();
        };
    }

    private int usage(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        ctx.messages().send(sender, "importer.usage",
                Msg.unparsed("sources", "cmi|essentials|pvx|vanilla|economy <plugin>|sources|status|cancel"),
                Msg.component("reason", ctx.messages().render(sender, "importer.reason-missing-subcommand")));
        return Cmds.OK;
    }

    private int cmi(CommandContext<CommandSourceStack> context, String rawArgs) {
        return start(context, rawArgs, "cmi", ImportCategory.cmi());
    }

    private int essentials(CommandContext<CommandSourceStack> context, String rawArgs) {
        return start(context, rawArgs, "essentials", ImportCategory.essentials());
    }

    private int vanilla(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSender sender = context.getSource().getSender();
        ImportOptions options = parseOptions(sender, rawArgs, "vanilla", ImportCategory.vanilla());
        if (options == null) {
            return Cmds.OK;
        }
        launch(sender, options, new VanillaImportJob(ctx, config.get(), options, sender), "server folder");
        return Cmds.OK;
    }

    private int pvx(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSender sender = context.getSource().getSender();
        ImportOptions options = parseOptions(sender, rawArgs, "pvx", ImportCategory.pvx());
        if (options == null) {
            return Cmds.OK;
        }
        Path folder = resolvePvxFolder();
        if (folder == null) {
            ctx.messages().send(sender, "importer.no-pvx-folder", Msg.unparsed("path", pvxFolderHint()));
            return Cmds.OK;
        }
        launch(sender, options, new PvxImportJob(ctx, config.get(), options, sender, folder), folder.toString());
        return Cmds.OK;
    }

    private int economyUsage(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        ctx.messages().send(sender, "importer.usage-economy",
                Msg.component("reason", ctx.messages().render(sender, "importer.reason-missing-plugin")));
        return Cmds.OK;
    }

    private int economy(CommandContext<CommandSourceStack> context, String rawArgs) {
        CommandSender sender = context.getSource().getSender();
        String plugin = StringArgumentType.getString(context, "plugin");
        ImportOptions options = parseOptions(sender, rawArgs, "economy", ImportCategory.economyOnly());
        if (options == null) {
            return Cmds.OK;
        }
        if (!BalanceSource.vaultInstalled(ctx.plugin().getServer())) {
            ctx.messages().send(sender, "importer.economy-no-vault");
            return Cmds.OK;
        }
        String atrium = ctx.plugin().getName();
        java.util.List<BalanceSource.Provider> providers = VaultProviders.list(ctx.plugin().getServer(), atrium);
        if (BalanceSource.pick(providers, plugin).isEmpty()) {
            String known = providers.stream().filter(BalanceSource.Provider::importable)
                    .map(BalanceSource.Provider::pluginName).distinct().reduce((a, b) -> a + ", " + b).orElse("none");
            ctx.messages().send(sender, "importer.economy-no-provider", Msg.unparsed("plugin", plugin),
                    Msg.unparsed("providers", known));
            return Cmds.OK;
        }
        EconomyImportJob job = new EconomyImportJob(ctx, config.get(), options, sender, plugin,
                wanted -> VaultProviders.find(ctx.plugin().getServer(), atrium, wanted));
        launch(sender, options, job, "Vault provider " + plugin);
        return Cmds.OK;
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> providerSuggestions(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (BalanceSource.vaultInstalled(ctx.plugin().getServer())) {
            String typed = builder.getRemainingLowerCase();
            VaultProviders.list(ctx.plugin().getServer(), ctx.plugin().getName()).stream()
                    .filter(BalanceSource.Provider::importable).map(BalanceSource.Provider::pluginName).distinct()
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(typed)).forEach(builder::suggest);
        }
        return builder.buildFuture();
    }

    private int sources(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        ImporterConfig cfg = config.get();
        Path cmi = resolveCmiFolder();
        Path essentials = resolveEssentialsFolder();
        java.util.List<ImportSources.Source> found = ImportSources.scan(
                cmi, cmiFolderHint(), essentials, essentialsFolderHint(),
                essentials == null ? null : resolveSpawnFolder(essentials),
                resolvePvxFolder(), pvxFolderHint(),
                Path.of("").toAbsolutePath());
        ctx.messages().send(sender, "importer.sources-header");
        for (ImportSources.Source source : found) {
            ctx.messages().send(sender, source.available() ? "importer.sources-line" : "importer.sources-missing",
                    Msg.unparsed("name", source.name()), Msg.unparsed("command", source.command()),
                    Msg.unparsed("detail", source.detail()));
        }
        if (!BalanceSource.vaultInstalled(ctx.plugin().getServer())) {
            ctx.messages().send(sender, "importer.sources-no-vault");
        } else {
            java.util.List<BalanceSource.Provider> providers = VaultProviders.list(ctx.plugin().getServer(),
                    ctx.plugin().getName());
            if (providers.isEmpty()) {
                ctx.messages().send(sender, "importer.sources-no-providers");
            }
            for (BalanceSource.Provider provider : providers) {
                ctx.messages().send(sender, provider.importable() ? "importer.sources-provider" : "importer.sources-provider-own",
                        Msg.unparsed("plugin", provider.pluginName()), Msg.unparsed("provider", provider.providerName()));
            }
        }
        if (cfg.checkWorlds) {
            ctx.messages().send(sender, "importer.sources-footer");
        }
        return Cmds.OK;
    }

    /** Parses the options or tells the sender why not; {@code null} on failure. */
    private ImportOptions parseOptions(CommandSender sender, String rawArgs, String source, Set<ImportCategory> categories) {
        try {
            return ImportOptions.parse(rawArgs, true, categories);
        } catch (ImportOptions.ParseException e) {
            Component reason = ctx.messages().render(sender, "importer.reason-" + e.reasonKey(),
                    Msg.unparsed("value", e.value()));
            ctx.messages().send(sender, "importer.usage-" + source,
                    Msg.unparsed("categories", ImportCategory.keys(categories)),
                    Msg.component("reason", reason));
            return null;
        }
    }

    private void launch(CommandSender sender, ImportOptions options, ImportJob job, String path) {
        if (!active.tryClaim(job)) {
            ctx.messages().send(sender, "importer.already-running");
            return;
        }
        ctx.messages().send(sender, options.dryRun() ? "importer.dry-run-started" : "importer.started",
                Msg.unparsed("source", job.sourceName()),
                Msg.unparsed("options", options.describe()),
                Msg.unparsed("path", path));
        job.start();
    }

    private int start(CommandContext<CommandSourceStack> context, String rawArgs, String source,
            Set<ImportCategory> categories) {
        CommandSender sender = context.getSource().getSender();
        ImportOptions options;
        try {
            options = ImportOptions.parse(rawArgs, true, categories);
        } catch (ImportOptions.ParseException e) {
            Component reason = ctx.messages().render(sender, "importer.reason-" + e.reasonKey(),
                    Msg.unparsed("value", e.value()));
            ctx.messages().send(sender, "importer.usage-" + source,
                    Msg.unparsed("categories", ImportCategory.keys(categories)),
                    Msg.component("reason", reason));
            return Cmds.OK;
        }
        if (active.busy()) {
            ctx.messages().send(sender, "importer.already-running");
            return Cmds.OK;
        }
        Path folder = source.equals("cmi") ? resolveCmiFolder() : resolveEssentialsFolder();
        if (folder == null) {
            ctx.messages().send(sender, "importer.no-" + source + "-folder",
                    Msg.unparsed("path", source.equals("cmi") ? cmiFolderHint() : essentialsFolderHint()));
            return Cmds.OK;
        }
        ImportJob job = source.equals("cmi")
                ? new CmiImportJob(ctx, config.get(), options, sender, folder)
                : new EssentialsImportJob(ctx, config.get(), options, sender, folder, resolveSpawnFolder(folder));
        // The check above is only a fast path: two senders can both pass it. Only the caller whose claim
        // succeeds may start; the other one is told an import is already running.
        if (!active.tryClaim(job)) {
            ctx.messages().send(sender, "importer.already-running");
            return Cmds.OK;
        }
        ctx.messages().send(sender, options.dryRun() ? "importer.dry-run-started" : "importer.started",
                Msg.unparsed("source", job.sourceName()),
                Msg.unparsed("options", options.describe()),
                Msg.unparsed("path", folder.toString()));
        job.start();
        return Cmds.OK;
    }

    private int status(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        ImportJob job = active.get();
        if (job == null) {
            ctx.messages().send(sender, "importer.status-none");
            return Cmds.OK;
        }
        ImportJob.Status status = job.status();
        ImportProgress.Snapshot progress = status.progress();
        ctx.messages().send(sender, "importer.status-line",
                Msg.unparsed("source", status.source()),
                Msg.unparsed("mode", status.dryRun() ? "dry-run" : "run"),
                Msg.unparsed("phase", progress.phase()),
                Msg.unparsed("percent", progress.percentText()),
                Msg.number("processed", progress.processed()),
                Msg.number("total", progress.total()),
                Msg.unparsed("eta", progress.etaText()),
                Msg.duration("elapsed", Duration.ofMillis(progress.elapsedMillis())),
                Msg.unparsed("state", job.isFinished()
                        ? (job.isCancelled() ? "cancelled" : "finished")
                        : (job.isCancelled() ? "cancelling" : "running")));
        ctx.messages().send(sender, "importer.status-line2",
                Msg.number("imported", status.imported()),
                Msg.number("skipped", status.skipped()),
                Msg.number("invalid", status.invalid()),
                Msg.number("failed", status.failed()),
                Msg.number("unsupported", status.unsupported()));
        if (status.reportPath() != null) {
            sendPath(sender, "importer.status-report", status.reportPath());
        }
        return Cmds.OK;
    }

    private int cancel(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        ImportJob job = active.get();
        if (job == null || job.isFinished()) {
            ctx.messages().send(sender, "importer.cancel-none");
            return Cmds.OK;
        }
        job.cancel();
        ctx.messages().send(sender, "importer.cancel-requested");
        return Cmds.OK;
    }

    private int backup(CommandContext<CommandSourceStack> context) {
        BackupService.start(ctx, config.get(), context.getSource().getSender());
        return Cmds.OK;
    }

    /** A click-to-copy report path; the hover text is translated, the path itself is literal text. */
    private void sendPath(CommandSender sender, String key, String path) {
        Component hover = ctx.messages().render(sender, "importer.report-copy-hover");
        Component clickable = Component.text(path)
                .clickEvent(ClickEvent.copyToClipboard(path))
                .hoverEvent(HoverEvent.showText(hover));
        ctx.messages().send(sender, key, Msg.component("path", clickable));
    }

    private Path resolveCmiFolder() {
        return resolveFolder(config.get().cmiFolder(), "CMI");
    }

    private Path resolveEssentialsFolder() {
        return resolveFolder(config.get().essentialsFolder(), "Essentials");
    }

    private Path resolvePvxFolder() {
        String configured = config.get().pvxFolder();
        Path folder;
        if (!configured.isBlank()) {
            folder = absolute(configured);
        } else {
            folder = pluginsDirectory().resolve("PlayerVaults").resolve("newvaults");
        }
        return Files.isDirectory(folder) ? folder.toAbsolutePath() : null;
    }

    private Path resolveSpawnFolder(Path essentialsFolder) {
        String configured = config.get().essentialsSpawnFolder();
        return EssentialsSpawnParser.spawnFolder(essentialsFolder, configured.isBlank() ? null : absolute(configured));
    }

    private Path resolveFolder(String configured, String defaultName) {
        Path folder = configured.isBlank() ? pluginsDirectory().resolve(defaultName) : absolute(configured);
        return Files.isDirectory(folder) ? folder.toAbsolutePath() : null;
    }

    private String cmiFolderHint() {
        return folderHint(config.get().cmiFolder(), "CMI");
    }

    private String essentialsFolderHint() {
        return folderHint(config.get().essentialsFolder(), "Essentials");
    }

    private String pvxFolderHint() {
        String configured = config.get().pvxFolder();
        Path folder = configured.isBlank()
                ? pluginsDirectory().resolve("PlayerVaults").resolve("newvaults")
                : absolute(configured);
        return folder.toAbsolutePath().toString();
    }

    private String folderHint(String configured, String defaultName) {
        return (configured.isBlank() ? pluginsDirectory().resolve(defaultName) : absolute(configured))
                .toAbsolutePath().toString();
    }

    private Path absolute(String configured) {
        Path folder = Path.of(configured);
        return folder.isAbsolute() ? folder : pluginsDirectory().resolve(configured);
    }

    private Path pluginsDirectory() {
        Path data = ctx.plugin().getDataFolder().toPath();
        Path parent = data.getParent();
        return parent == null ? data : parent;
    }
}
