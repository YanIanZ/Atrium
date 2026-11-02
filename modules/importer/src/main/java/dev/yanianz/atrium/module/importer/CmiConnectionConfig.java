package dev.yanianz.atrium.module.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Read-only connection settings discovered from CMI's own config. The password is kept in memory only and
 * never appears in {@link #describe()} or in logs.
 */
record CmiConnectionConfig(Kind kind, Path sqliteFile, String host, int port, String database,
        String username, String password, String tablePrefix) {

    enum Kind { SQLITE, MYSQL }

    private static final List<String> CONFIG_FILES = List.of(
            "config.yml",
            "Settings/config.yml",
            "Settings/DataBase.yml",
            "Settings/Database.yml",
            "Settings/Db.yml");

    static CmiConnectionConfig read(Path cmiFolder) {
        if (cmiFolder == null || !Files.isDirectory(cmiFolder)) {
            throw new IllegalArgumentException("CMI folder does not exist: " + cmiFolder);
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String relative : CONFIG_FILES) {
            Path file = cmiFolder.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
                flatten("", root, values);
            } catch (IOException | RuntimeException e) {
                throw new IllegalArgumentException("Cannot read CMI config " + file + ": " + e.getMessage(), e);
            }
        }
        String databaseType = lookup(values, "database.type", "datasource.type", "database.engine", "dbtype");
        boolean mysql = truthy(lookup(values,
                "mysql.enabled", "mysql.enable", "mysql.use", "mysql.usemysql",
                "datasource.usemysql", "datasource.mysql.enabled",
                "database.mysql.enabled", "database.mysql.use"))
                || (databaseType != null && databaseType.equalsIgnoreCase("mysql"));
        if (mysql) {
            String host = first(lookup(values, "mysql.host", "mysql.hostname", "datasource.hostname",
                    "datasource.host", "database.host", "database.hostname"), "localhost");
            int port = integer(lookup(values, "mysql.port", "datasource.port", "database.port"), 3306);
            String database = first(lookup(values, "mysql.database", "mysql.db", "mysql.databasename",
                    "datasource.database", "datasource.db", "database.database", "database.name"), "cmi");
            String username = first(lookup(values, "mysql.user", "mysql.username", "datasource.user",
                    "datasource.username", "database.user", "database.username"), "root");
            String password = first(lookup(values, "mysql.password", "mysql.pass", "datasource.password",
                    "datasource.pass", "database.password", "database.pass"), "");
            String prefix = first(lookup(values, "mysql.tableprefix", "mysql.prefix", "datasource.tableprefix",
                    "database.tableprefix", "database.prefix"), "");
            return new CmiConnectionConfig(Kind.MYSQL, null, host, port, database, username, password,
                    prefix == null ? "" : prefix.replaceAll("[^A-Za-z0-9_]", ""));
        }
        String configuredFile = lookup(values, "database.file", "sqlite.file", "database.sqlitefile",
                "sqlite.filename");
        Path sqlite = findSqlite(cmiFolder);
        if (configuredFile != null && !configuredFile.isBlank()) {
            Path candidate = cmiFolder.resolve(configuredFile).normalize();
            if (candidate.startsWith(cmiFolder.normalize())) {
                sqlite = candidate;
            }
        }
        return new CmiConnectionConfig(Kind.SQLITE, sqlite, "", 0, "", "", "", "");
    }

    String jdbcUrl() {
        if (kind == Kind.SQLITE) {
            return "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
        }
        return "jdbc:mariadb://" + host + ":" + port + "/" + database;
    }

    /** Safe for logs and chat: never contains the password. */
    String describe() {
        if (kind == Kind.SQLITE) {
            return "SQLite " + sqliteFile.toAbsolutePath();
        }
        return "MySQL " + host + ":" + port + "/" + database
                + (tablePrefix.isEmpty() ? "" : " (prefix '" + tablePrefix + "')");
    }

    /** Records would otherwise print the password. */
    @Override
    public String toString() {
        return "CmiConnectionConfig[" + describe() + "]";
    }

    private static Path findSqlite(Path cmiFolder) {
        List<String> candidates = List.of("cmi.sqlite.db", "cmi.sqlite", "cmi.db", "DataBase/cmi.sqlite.db");
        for (String candidate : candidates) {
            Path path = cmiFolder.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return cmiFolder.resolve("cmi.sqlite.db");
    }

    private static void flatten(String prefix, ConfigurationNode node, Map<String, String> out) {
        if (node.isMap()) {
            for (var entry : node.childrenMap().entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                flatten(prefix.isEmpty() ? key : prefix + "." + key, entry.getValue(), out);
            }
        } else if (!node.virtual()) {
            String value = node.getString();
            if (value != null) {
                out.put(prefix, value);
            }
        }
    }

    private static String lookup(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null) {
                return value;
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            for (String key : keys) {
                if (entry.getKey().endsWith("." + key) || entry.getKey().equals(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static boolean truthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1", "enabled" -> true;
            default -> false;
        };
    }

    private static String first(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int integer(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 && parsed < 65536 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
