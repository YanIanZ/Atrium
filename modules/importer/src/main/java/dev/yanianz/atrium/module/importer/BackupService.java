package dev.yanianz.atrium.module.importer;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;

/**
 * {@code /atrium-backup}: a consistent SQLite snapshot with {@code VACUUM INTO}, or instructions for
 * mysqldump when the shared Atrium database is MySQL/MariaDB. All file and JDBC work happens on the I/O
 * executor.
 */
final class BackupService {
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT);

    private BackupService() {}

    static void start(ModuleContext ctx, ImporterConfig config, CommandSender sender) {
        CoreConfig.Database database = ctx.coreConfig().database;
        if (database.type == null || !database.type.equalsIgnoreCase("SQLITE")) {
            ctx.messages().send(sender, "importer.backup-mysql",
                    Msg.unparsed("host", database.host),
                    Msg.unparsed("port", Integer.toString(database.port)),
                    Msg.unparsed("user", database.username),
                    Msg.unparsed("database", database.database));
            return;
        }
        Path root = ctx.plugin().getDataFolder().toPath();
        Path configured = root.resolve(config.backupDirectory()).normalize();
        final Path directory = configured.startsWith(root.normalize()) ? configured : root.resolve("backups");
        Path source = root.resolve("atrium.db");
        if (!Files.isRegularFile(source)) {
            ctx.messages().send(sender, "importer.backup-missing", Msg.unparsed("file", source.toString()));
            return;
        }
        ctx.messages().send(sender, "importer.backup-started");
        int keep = config.backupKeep();
        ctx.scheduler().supplyIo(() -> backup(directory, source, keep)).whenComplete((result, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                ctx.logger().error("Backing up {} failed", source, cause);
                ctx.scheduler().global(() -> ctx.messages().send(sender, "importer.backup-failed",
                        Msg.unparsed("reason", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage())));
                return;
            }
            ctx.scheduler().global(() -> ctx.messages().send(sender, "importer.backup-done",
                    Msg.unparsed("file", result.file().toString()),
                    Msg.number("removed", result.removed())));
        });
    }

    private static Result backup(Path directory, Path source, int keep) throws IOException, SQLException {
        Files.createDirectories(directory);
        String stamp = LocalDateTime.now().format(STAMP);
        Path target = directory.resolve("atrium-" + stamp + ".db");
        String quoted = target.toAbsolutePath().toString().replace("'", "''");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("VACUUM INTO '" + quoted + "'");
        }
        int removed = prune(directory, keep);
        return new Result(target, removed);
    }

    private static int prune(Path directory, int keep) throws IOException {
        List<Path> backups = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("atrium-") && name.endsWith(".db");
                    })
                    .forEach(backups::add);
        }
        backups.sort(Comparator.comparingLong((Path path) -> {
            try {
                return Files.getLastModifiedTime(path).toMillis();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).reversed());
        int removed = 0;
        for (int i = keep; i < backups.size(); i++) {
            try {
                if (Files.deleteIfExists(backups.get(i))) {
                    removed++;
                }
            } catch (IOException e) {
                // Keep the remaining backups; a failed delete is reported by the caller's log line.
                throw new IOException("Cannot delete old backup " + backups.get(i), e);
            }
        }
        return removed;
    }

    private record Result(Path file, int removed) {}
}
