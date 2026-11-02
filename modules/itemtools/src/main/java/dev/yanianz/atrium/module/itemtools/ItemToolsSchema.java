package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.util.List;

/**
 * Schema of the itemtools module: the saved item library, the offline {@code /giveall} queue and the
 * per-player preferences behind {@code /durabilitywarn}. Every table is created here in version 1; later
 * changes must append a new migration instead of editing this one.
 */
final class ItemToolsSchema {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1,
            "saved items, giveall queue and player preferences", dialect -> {
                String blob = dialect == Dialect.MYSQL ? "MEDIUMBLOB" : "BLOB";
                String id = dialect == Dialect.SQLITE
                        ? "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT"
                        : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
                return List.of(
                        "CREATE TABLE {prefix}itemtools_saveditems ("
                                + "name VARCHAR(64) NOT NULL PRIMARY KEY, "
                                + "item " + blob + " NOT NULL, "
                                + "saved_by CHAR(36), "
                                + "created BIGINT NOT NULL)",
                        "CREATE TABLE {prefix}itemtools_giveall ("
                                + id + ", "
                                + "uuid CHAR(36) NOT NULL, "
                                + "items " + blob + " NOT NULL, "
                                + "created BIGINT NOT NULL)",
                        "CREATE INDEX {prefix}itemtools_giveall_uuid ON {prefix}itemtools_giveall (uuid)",
                        "CREATE TABLE {prefix}itemtools_prefs ("
                                + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                                + "durability_warning BOOLEAN NOT NULL DEFAULT FALSE)");
            }));

    private ItemToolsSchema() {
    }
}
