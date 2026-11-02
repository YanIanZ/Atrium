package dev.yanianz.atrium.module.worth;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Copies the sell prices of another plugin into Atrium's own {@code worth.yml}.
 *
 * <p>Everything (reading the other plugin's files, mapping the prices, writing worth.yml and the report) runs
 * on the IO thread; the caller only waits for the future. Material names are checked against the item
 * snapshot taken at module start, so no registry access happens off the server thread. Existing entries are
 * skipped unless overwrite is requested, so repeating an import is safe.
 */
final class PriceImportService {
    enum Mode { DRY_RUN, RUN }

    record Options(Mode mode, boolean overwrite) {
        boolean dryRun() {
            return mode == Mode.DRY_RUN;
        }
    }

    record Summary(PriceImportSource source, boolean dryRun, boolean overwrite, int files, int found,
            int imported, int updated, int existing, int skipped, Path report) {}

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private final ModuleContext ctx;
    private final WorthModule module;

    PriceImportService(ModuleContext ctx, WorthModule module) {
        this.ctx = ctx;
        this.module = module;
    }

    CompletableFuture<Summary> run(PriceImportSource source, Options options) {
        return ctx.scheduler().supplyIo(() -> runNow(source, options));
    }

    private Summary runNow(PriceImportSource source, Options options) throws IOException {
        Path pluginsFolder = ctx.plugin().getDataFolder().getParentFile().toPath();
        List<Path> files = source.files(pluginsFolder);
        Map<String, String> texts = new LinkedHashMap<>();
        for (Path file : files) {
            String key = pluginsFolder.relativize(file).toString().replace('\\', '/');
            try {
                texts.put(key, Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                ctx.logger().warn("Cannot read {} for the worth import: {}", file, e.getMessage());
            }
        }
        ImportParse parsed = source.importer().parse(texts);

        ImportPlanner.Result planned = ImportPlanner.plan(parsed.candidates(), parsed.skipped(),
                module.prices().entries(), module.materials(), source == PriceImportSource.ESSENTIALS,
                options.overwrite());
        List<PriceEntry> write = planned.write();
        List<ImportSkip> skipped = planned.skipped();
        int imported = planned.imported();
        int updated = planned.updated();
        int alreadySet = planned.existing();

        if (options.mode() == Mode.RUN && !write.isEmpty()) {
            try {
                module.saveEntries(write);
            } catch (Exception e) {
                throw new IOException("could not write worth.yml: " + e.getMessage(), e);
            }
        }
        Path report = writeReport(source, options, files.size(), parsed, planned);
        return new Summary(source, options.mode() == Mode.DRY_RUN, options.overwrite(), files.size(),
                parsed.candidates().size() + parsed.skipped().size(), imported, updated, alreadySet, skipped.size(),
                report);
    }

    // ---- report ----

    private Path writeReport(PriceImportSource source, Options options, int files, ImportParse parsed,
            ImportPlanner.Result planned) throws IOException {
        List<PriceEntry> write = planned.write();
        int imported = planned.imported();
        int updated = planned.updated();
        int alreadySet = planned.existing();
        List<ImportSkip> skipped = planned.skipped();
        Path dir = ctx.plugin().getDataFolder().toPath().resolve("import-reports");
        Files.createDirectories(dir);
        Path report = dir.resolve(source.id + "-" + LocalDateTime.now().format(STAMP) + ".txt");
        StringBuilder out = new StringBuilder(4096);
        out.append("# Atrium worth import\n");
        out.append("source: ").append(source.id).append('\n');
        out.append("mode: ").append(options.dryRun() ? "dry-run" : "run").append('\n');
        out.append("overwrite: ").append(options.overwrite()).append('\n');
        out.append("files: ").append(files).append('\n');
        out.append("prices found: ").append(parsed.candidates().size() + parsed.skipped().size()).append('\n');
        out.append("imported: ").append(options.dryRun() ? "would write " + imported : imported).append('\n');
        out.append("updated: ").append(options.dryRun() ? "would overwrite " + updated : updated).append('\n');
        out.append("already set: ").append(alreadySet).append('\n');
        out.append("skipped: ").append(skipped.size()).append('\n');
        out.append('\n').append("## imported\n");
        if (write.isEmpty()) {
            out.append("(none)\n");
        }
        for (PriceEntry entry : write) {
            out.append(entry.id()).append(": ").append(entry.material()).append(" = ")
                    .append(entry.price().toPlainString());
            if (entry.name() != null) {
                out.append(" name=\"").append(entry.name()).append('"');
            }
            if (entry.customModelData() != null) {
                out.append(" custom-model-data=").append(String.join(",", entry.customModelData()));
            }
            out.append('\n');
        }
        out.append('\n').append("## skipped\n");
        if (skipped.isEmpty()) {
            out.append("(none)\n");
        }
        for (ImportSkip skip : skipped) {
            out.append(skip.origin()).append(" [").append(skip.reason()).append("] ").append(skip.detail())
                    .append('\n');
        }
        Files.writeString(report, out.toString(), StandardCharsets.UTF_8);
        return report;
    }
}
