package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.command.CommandSender;

/** Imports only the server's ban lists ({@code banned-players.json}, {@code banned-ips.json}). */
final class VanillaImportJob extends ExtendedImportJob {
    VanillaImportJob(ModuleContext ctx, ImporterConfig config, ImportOptions options, CommandSender sender) {
        super(ctx, config, options, sender);
    }

    @Override
    protected String sourceName() {
        return "Vanilla ban lists";
    }

    @Override
    protected String reportPrefix() {
        return "vanilla";
    }

    @Override
    protected void run() {
        var folder = serverFolder();
        sourceDescription = folder.toString();
        report.note("Source: " + sourceDescription);
        importVanillaBans(folder);
    }
}
