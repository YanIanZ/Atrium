package dev.yanianz.atrium.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.yanianz.atrium.core.config.CoreConfig;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;

/**
 * Pooled JDBC access. Every operation runs on the I/O executor (virtual threads) and returns a future.
 * {@link #transaction} commits only when the function returns normally; any exception rolls back and
 * fails the future, so callers never see a "success" for a write that did not happen.
 */
public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final Dialect dialect;
    private final String prefix;
    private final Executor executor;
    private final Logger logger;

    public Database(CoreConfig.Database config, Path dataFolder, Executor executor, Logger logger) {
        this.executor = executor;
        this.logger = logger;
        this.prefix = config.tablePrefix.replaceAll("[^A-Za-z0-9_]", "");
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("atrium");
        String type = config.type.toUpperCase(Locale.ROOT);
        switch (type) {
            case "SQLITE" -> {
                dialect = Dialect.SQLITE;
                hikari.setDriverClassName("org.sqlite.JDBC");
                hikari.setJdbcUrl("jdbc:sqlite:" + dataFolder.resolve("atrium.db").toAbsolutePath());
                // One connection: SQLite serialises writers anyway, and this avoids SQLITE_BUSY.
                hikari.setMaximumPoolSize(1);
                // Passed to the driver as connection properties (sqlite-jdbc SQLiteConfig pragmas).
                hikari.addDataSourceProperty("journal_mode", "WAL");
                hikari.addDataSourceProperty("foreign_keys", "true");
                hikari.addDataSourceProperty("busy_timeout", "5000");
            }
            case "MYSQL", "MARIADB" -> {
                dialect = Dialect.MYSQL;
                hikari.setDriverClassName("org.mariadb.jdbc.Driver");
                String params = config.parameters.isBlank() ? defaultParameters(config.host) : config.parameters;
                hikari.setJdbcUrl("jdbc:mariadb://" + config.host + ":" + config.port + "/" + config.database
                        + (params.isBlank() ? "" : "?" + params));
                hikari.setUsername(config.username);
                hikari.setPassword(config.password);
                hikari.setMaximumPoolSize(Math.max(2, config.poolSize));
            }
            default -> throw new IllegalArgumentException("Unknown database type " + config.type);
        }
        hikari.setConnectionTimeout(10_000);
        this.dataSource = new HikariDataSource(hikari);
    }

    private static String defaultParameters(String host) {
        boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        return local ? "" : "sslMode=verify-full";
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Full table name with the configured prefix. */
    public String table(String name) {
        return prefix + name;
    }

    public String expand(String sql) {
        return sql.replace("{prefix}", prefix);
    }

    /** Runs with an auto-commit connection. */
    public <T> CompletableFuture<T> query(SqlFunction<Connection, T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new StorageException(e);
            }
        }, executor);
    }

    /** Runs in a single transaction. */
    public <T> CompletableFuture<T> transaction(SqlFunction<Connection, T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    T result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (SQLException | RuntimeException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollback) {
                        e.addSuppressed(rollback);
                    }
                    throw e;
                } finally {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                throw new StorageException(e);
            }
        }, executor);
    }

    /** Executes a single update statement with positional parameters. */
    public CompletableFuture<Integer> update(String sql, Object... params) {
        String expanded = expand(sql);
        return query(c -> {
            try (PreparedStatement statement = c.prepareStatement(expanded)) {
                bind(statement, params);
                return statement.executeUpdate();
            }
        });
    }

    public static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
    }

    /**
     * Applies pending migrations for one namespace (module id) and blocks until done. Called during
     * startup only. Each migration runs in its own transaction together with its version bump.
     */
    public void migrate(String namespace, List<Migration> migrations) throws SQLException {
        String versions = table("schema_versions");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + versions
                    + " (namespace VARCHAR(64) NOT NULL PRIMARY KEY, version INT NOT NULL)");
        }
        int current;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT version FROM " + versions + " WHERE namespace = ?")) {
            ps.setString(1, namespace);
            try (ResultSet rs = ps.executeQuery()) {
                current = rs.next() ? rs.getInt(1) : 0;
            }
        }
        List<Migration> pending = migrations.stream()
                .filter(m -> m.version() > current)
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();
        for (Migration migration : pending) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    for (String statement : migration.statements().apply(dialect)) {
                        s.executeUpdate(expand(statement));
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            dialect.upsert(versions, "namespace, version", "?, ?", "namespace", "version"))) {
                        ps.setString(1, namespace);
                        ps.setInt(2, migration.version());
                        ps.executeUpdate();
                    }
                    c.commit();
                    logger.info("Migrated {} to schema v{} ({})", namespace, migration.version(), migration.description());
                } catch (SQLException e) {
                    c.rollback();
                    throw new SQLException("Migration " + namespace + " v" + migration.version() + " failed", e);
                }
            }
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    public static final class StorageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public StorageException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
