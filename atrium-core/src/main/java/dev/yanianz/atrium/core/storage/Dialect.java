package dev.yanianz.atrium.core.storage;

public enum Dialect {
    SQLITE,
    MYSQL;

    /** Upsert clause differs between SQLite ({@code ON CONFLICT}) and MySQL ({@code ON DUPLICATE KEY}). */
    public String upsert(String table, String columns, String placeholders, String keyColumns, String updateColumns) {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" (").append(columns)
                .append(") VALUES (").append(placeholders).append(") ");
        String[] updates = updateColumns.split("\\s*,\\s*");
        if (this == SQLITE) {
            sql.append("ON CONFLICT(").append(keyColumns).append(") DO UPDATE SET ");
            for (int i = 0; i < updates.length; i++) {
                sql.append(i == 0 ? "" : ", ").append(updates[i]).append(" = excluded.").append(updates[i]);
            }
        } else {
            sql.append("ON DUPLICATE KEY UPDATE ");
            for (int i = 0; i < updates.length; i++) {
                sql.append(i == 0 ? "" : ", ").append(updates[i]).append(" = VALUES(").append(updates[i]).append(')');
            }
        }
        return sql.toString();
    }
}
