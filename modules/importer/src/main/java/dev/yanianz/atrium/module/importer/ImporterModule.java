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
        return "CMI and EssentialsX data importer plus a SQLite backup helper";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("economy", "homes", "warps", "nick", "playtime", "holograms");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(ImporterConfig.class, ImporterConfig::new);
        context.migrate(ImportLedger.MIGRATIONS);

        context.permission(PERM_IMPORT, PermissionDefault.OP, "Import CMI or EssentialsX data");
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
                .then(Cmds.literal("status")
                        .requires(source -> source.getSender().hasPermission(PERM_STATUS)
                                || source.getSender().hasPermission(PERM_IMPORT))
                        .executes(this::status))
                .then(Cmds.literal("cancel").requires(Cmds.permission(PERM_IMPORT)).executes(this::cancel));
        context.command(CommandSpec.of(root.build(),
                "Import CMI or EssentialsX economy, homes, warps, nicknames, playtime and more"));

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
                Msg.unparsed("sources", "cmi|essentials|status|cancel"),
                Msg.component("reason", ctx.messages().render(sender, "importer.reason-missing-subcommand")));
        return Cmds.OK;
    }

    private int cmi(CommandContext<CommandSourceStack> context, String rawArgs) {
        return start(context, rawArgs, "cmi", ImportCategory.cmi());
    }

    private int essentials(CommandContext<CommandSourceStack> context, String rawArgs) {
        return start(context, rawArgs, "essentials", ImportCategory.essentials());
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

    private Path resolveSpawnFolder(Path essentialsFolder) {
        String configured = config.get().essentialsSpawnFolder();
        Path folder;
        if (!configured.isBlank()) {
            folder = absolute(configured);
        } else {
            Path parent = essentialsFolder.getParent() == null ? pluginsDirectory() : essentialsFolder.getParent();
            folder = parent.resolve("EssentialsSpawn");
        }
        return Files.isDirectory(folder) ? folder.toAbsolutePath() : null;
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
