package dev.yanianz.atrium.module.mail;

import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Mail rows in {@code {prefix}mail_messages}. Everything is paginated and filtered in SQL; the module never
 * loads a whole mailbox into memory.
 */
final class MailStore {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "mail_messages", dialect -> {
        String id = dialect == Dialect.SQLITE
                ? "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT"
                : "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY";
        return List.of(
                "CREATE TABLE {prefix}mail_messages (" + id
                        + ", recipient CHAR(36) NOT NULL, sender CHAR(36), sender_name VARCHAR(64) NOT NULL,"
                        + " body TEXT NOT NULL, created BIGINT NOT NULL, expires BIGINT NOT NULL DEFAULT 0,"
                        + " is_read BOOLEAN NOT NULL DEFAULT FALSE)",
                "CREATE INDEX {prefix}mail_recipient ON {prefix}mail_messages (recipient, id)",
                "CREATE INDEX {prefix}mail_expires ON {prefix}mail_messages (expires)");
    }), Migration.of(2, "mail dropped messages",
            "ALTER TABLE {prefix}mail_messages ADD COLUMN dropped BOOLEAN NOT NULL DEFAULT FALSE"));

    /** One stored message. {@code sender} is {@code null} for console mail. */
    record Entry(long id, UUID sender, String senderName, String body, long created, long expires, boolean read) {}

    /**
     * One page of a mailbox. {@code unread} is the number still unread after this page was marked as read.
     * Dropped messages (the recipient ignores the sender) are never part of a page.
     */
    record Page(int page, int pages, int total, int unread, List<Entry> entries) {}

    /**
     * A message about to be stored. {@code expires == 0} means it never expires. {@code dropped} stores the
     * message for the record without ever showing it: the recipient's ignore list hid it from them and the
     * sender must not learn that.
     */
    record Draft(UUID recipient, UUID sender, String senderName, String body, long created, long expires,
            boolean dropped) {
        Draft(UUID recipient, UUID sender, String senderName, String body, long created, long expires) {
            this(recipient, sender, senderName, body, created, expires, false);
        }
    }

    private final Database db;
    private final String table;

    MailStore(Database db) {
        this.db = db;
        this.table = db.table("mail_messages");
    }

    /**
     * Inserts every draft whose recipient still has room, within one transaction. Expired rows are removed
     * first so they never consume a mailbox slot. Dropped drafts take no slot, but they are still refused
     * when the visible mailbox is full so a sender cannot detect the drop from a different "full" reply.
     * Returns the recipients that actually received the mail.
     */
    CompletableFuture<List<UUID>> insertAll(List<Draft> drafts, int mailboxLimit) {
        if (drafts.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        int limit = Math.max(1, mailboxLimit);
        long now = System.currentTimeMillis();
        return db.transaction(connection -> {
            try (PreparedStatement purge = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE expires > 0 AND expires <= ?")) {
                purge.setLong(1, now);
                purge.executeUpdate();
            }
            List<UUID> delivered = new ArrayList<>();
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE recipient = ? AND dropped = FALSE"
                            + " AND (expires = 0 OR expires > ?)");
                    PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + table
                                    + " (recipient, sender, sender_name, body, created, expires, is_read, dropped)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)")) {
                for (Draft draft : drafts) {
                    if (draft.expires() > 0 && draft.expires() <= now) {
                        continue;
                    }
                    count.setString(1, draft.recipient().toString());
                    count.setLong(2, now);
                    int stored;
                    try (ResultSet rs = count.executeQuery()) {
                        stored = rs.next() ? rs.getInt(1) : 0;
                    }
                    if (stored >= limit) {
                        continue;
                    }
                    Database.bind(insert, draft.recipient().toString(),
                            draft.sender() == null ? null : draft.sender().toString(),
                            draft.senderName(), draft.body(), draft.created(), draft.expires(), draft.dropped());
                    insert.executeUpdate();
                    delivered.add(draft.recipient());
                }
            }
            return List.copyOf(delivered);
        });
    }

    /** Reads (and marks as read) one page of a mailbox. Dropped messages are never shown. */
    CompletableFuture<Page> readPage(UUID recipient, int requestedPage, int pageSize) {
        int size = Math.max(1, pageSize);
        long now = System.currentTimeMillis();
        return db.transaction(connection -> {
            int total;
            int unreadBefore;
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*), COALESCE(SUM(CASE WHEN is_read = FALSE THEN 1 ELSE 0 END), 0)"
                            + " FROM " + table + " WHERE recipient = ? AND dropped = FALSE"
                            + " AND (expires = 0 OR expires > ?)")) {
                ps.setString(1, recipient.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                    unreadBefore = rs.getInt(2);
                }
            }
            int pages = MailFormat.pageCount(total, size);
            int page = MailFormat.page(requestedPage, total, size);
            int offset = (page - 1) * size;
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT id, sender, sender_name, body, created, expires, is_read FROM " + table
                            + " WHERE recipient = ? AND dropped = FALSE AND (expires = 0 OR expires > ?)"
                            + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, recipient.toString());
                ps.setLong(2, now);
                ps.setInt(3, size);
                ps.setInt(4, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        entries.add(new Entry(rs.getLong(1), uuid(rs.getString(2)), rs.getString(3),
                                rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getBoolean(7)));
                    }
                }
            }
            int unreadOnPage = (int) entries.stream().filter(entry -> !entry.read()).count();
            markRead(connection, recipient, entries);
            return new Page(page, pages, total, Math.max(0, unreadBefore - unreadOnPage), List.copyOf(entries));
        });
    }

    private void markRead(Connection connection, UUID recipient, List<Entry> entries) throws SQLException {
        List<Long> unread = entries.stream().filter(entry -> !entry.read()).map(Entry::id).toList();
        if (unread.isEmpty()) {
            return;
        }
        String placeholders = String.join(", ", Collections.nCopies(unread.size(), "?"));
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + table + " SET is_read = TRUE WHERE recipient = ? AND id IN (" + placeholders + ")")) {
            ps.setString(1, recipient.toString());
            for (int i = 0; i < unread.size(); i++) {
                ps.setLong(i + 2, unread.get(i));
            }
            ps.executeUpdate();
        }
    }

    /** Number of unread, non-expired, visible messages. */
    CompletableFuture<Integer> unread(UUID recipient, long now) {
        return db.query(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM " + table + " WHERE recipient = ? AND is_read = FALSE"
                            + " AND dropped = FALSE AND (expires = 0 OR expires > ?)")) {
                ps.setString(1, recipient.toString());
                ps.setLong(2, now);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    CompletableFuture<Integer> clearAll(UUID recipient) {
        return db.update("DELETE FROM " + table + " WHERE recipient = ?", recipient.toString());
    }

    /** Deletes one visible message only when it belongs to the recipient. */
    CompletableFuture<Boolean> clearOne(UUID recipient, long id) {
        return db.update("DELETE FROM " + table + " WHERE recipient = ? AND id = ? AND dropped = FALSE",
                recipient.toString(), id).thenApply(deleted -> deleted > 0);
    }

    CompletableFuture<Integer> purgeExpired(long now) {
        return db.update("DELETE FROM " + table + " WHERE expires > 0 AND expires <= ?", now);
    }

    private static UUID uuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
