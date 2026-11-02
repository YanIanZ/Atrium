package dev.yanianz.atrium.module.economy;

import dev.yanianz.atrium.api.economy.EconomyAccounts;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.economy.TransactionResult.Status;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Dialect;
import dev.yanianz.atrium.core.storage.Migration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.slf4j.Logger;

/** Authoritative ledger. SQLite uses exact TEXT CAS, never SQLite floating point arithmetic. */
final class EconomyStore implements EconomyService, EconomyAccounts {
    static final List<Migration> MIGRATIONS = List.of(new Migration(1, "accounts and ledger", dialect -> {
        String decimal = dialect == Dialect.SQLITE ? "TEXT" : "DECIMAL(30,10)";
        String engine = dialect == Dialect.MYSQL ? " ENGINE=InnoDB" : "";
        return List.of(
            "CREATE TABLE {prefix}economy_accounts (uuid CHAR(36) NOT NULL PRIMARY KEY, balance " + decimal + " NOT NULL, updated_at BIGINT NOT NULL)" + engine,
            "CREATE TABLE {prefix}economy_transactions (id CHAR(36) NOT NULL PRIMARY KEY, account CHAR(36) NOT NULL, counterparty CHAR(36), amount " + decimal + " NOT NULL, balance_after " + decimal + " NOT NULL, reason VARCHAR(255) NOT NULL, created BIGINT NOT NULL)" + engine,
            "CREATE INDEX {prefix}economy_transactions_account ON {prefix}economy_transactions (account, created)");
    }));
    record AccountState(BigDecimal balance) {}
    record Entry(UUID uuid, String name, BigDecimal balance) {}
    record LogEntry(String amount, String balance, String reason, long created) {}
    private record Stored(BigDecimal value, String raw) {}
    private static final class Rejected extends RuntimeException {
        final TransactionResult result;
        Rejected(TransactionResult result) { this.result = result; }
    }
    private record Outcome(TransactionResult result, Map<UUID, BigDecimal> balances) {}
    private final Database db;
    private final Logger logger;
    private final Money money;
    private final BigDecimal start, minimum, maximum;
    private final String singular, plural;
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    private final Map<UUID, BigDecimal> cache = new ConcurrentHashMap<>();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private volatile boolean closed;
    final UserComponentType<AccountState> component;

    EconomyStore(Database db, EconomyConfig config, Logger logger) {
        this.db = db; this.logger = logger;
        money = new Money(config.scale, RoundingMode.valueOf(config.rounding), new BigDecimal(config.maxAmount), config.symbol, config.displayFormat);
        start = money.amount(new BigDecimal(config.startingBalance));
        minimum = new BigDecimal(config.minBalance).setScale(config.scale, money.rounding);
        BigDecimal storageMax = Money.STORAGE_MAX.setScale(config.scale, RoundingMode.DOWN);
        maximum = config.maxBalance.isBlank() ? storageMax : new BigDecimal(config.maxBalance).setScale(config.scale, money.rounding);
        if (minimum.compareTo(storageMax.negate()) < 0 || maximum.compareTo(storageMax) > 0
                || minimum.compareTo(start) > 0 || maximum.compareTo(start) < 0) {
            throw new IllegalArgumentException("Invalid economy balance limits or starting balance");
        }
        singular = config.currencySingular; plural = config.currencyPlural;
        // A read projection only. Never persist a stale UserManager snapshot over the ledger.
        component = new UserComponentType<>("economy:account", id -> balance(id).thenApply(AccountState::new),
                (id, value) -> CompletableFuture.completedFuture(null), () -> new AccountState(start));
    }

    Money money() { return money; }
    BigDecimal startingBalance() { return start; }

    /** Serialize submission through post-commit publication, so late callbacks cannot regress cache. */
    private synchronized <T> CompletableFuture<T> ordered(Supplier<CompletableFuture<T>> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Economy disabled"));
        CompletableFuture<T> next = tail.handle((v, failure) -> null).thenCompose(v -> work.get());
        tail = next.handle((v, failure) -> null);
        return next.copy(); // Caller cancellation must not release our internal queue.
    }

    synchronized CompletableFuture<Void> close() {
        closed = true; online.clear(); cache.clear(); return tail;
    }

    CompletableFuture<Void> online(UUID id) {
        online.add(id);
        return ensure(id).thenAccept(value -> {});
    }
    void offline(UUID id) { online.remove(id); cache.remove(id); }

    CompletableFuture<BigDecimal> ensure(UUID id) {
        return ordered(() -> db.transaction(c -> ensureRow(c, id).value()).thenApply(value -> {
            publish(Map.of(id, value)); return value;
        }));
    }

    @Override public CompletableFuture<BigDecimal> balance(UUID id) {
        Objects.requireNonNull(id);
        return ordered(() -> db.query(c -> {
            Stored stored = read(c, id, false);
            return stored == null ? start : stored.value();
        }).thenApply(value -> { publish(Map.of(id, value)); return value; }));
    }
    CompletableFuture<Boolean> exists(UUID id) {
        return ordered(() -> db.query(c -> read(c, id, false) != null));
    }
    @Override public CompletableFuture<Boolean> hasAccount(UUID id) { Objects.requireNonNull(id); return exists(id); }
    @Override public BigDecimal cachedBalance(UUID id) { return cache.get(id); }
    @Override public CompletableFuture<TransactionResult> deposit(UUID id, BigDecimal amount, String reason) {
        return change(id, null, amount, reason, "give");
    }
    @Override public CompletableFuture<TransactionResult> withdraw(UUID id, BigDecimal amount, String reason) {
        return change(id, null, amount, reason, "take");
    }
    @Override public CompletableFuture<TransactionResult> set(UUID id, BigDecimal amount, String reason) {
        return change(id, null, amount, reason, "set");
    }
    @Override public CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount, String reason) {
        Objects.requireNonNull(to);
        if (from.equals(to)) return rejected(from, Status.INVALID_AMOUNT);
        return change(from, to, amount, reason, "pay");
    }
    private CompletableFuture<TransactionResult> rejected(UUID id, Status status) {
        return balance(id).thenApply(value -> new TransactionResult(status, BigDecimal.ZERO, value));
    }
    private CompletableFuture<TransactionResult> change(UUID id, UUID other, BigDecimal input, String reason, String kind) {
        Objects.requireNonNull(id);
        BigDecimal amount;
        try { amount = money.amount(input); }
        catch (IllegalArgumentException | ArithmeticException e) { return rejected(id, Status.INVALID_AMOUNT); }
        if (reason == null || reason.length() > 255) return rejected(id, Status.INVALID_AMOUNT);
        return ordered(() -> db.transaction(c -> {
            // Includes insertion locks: acquire both accounts in the same lexicographic order.
            Map<UUID, Stored> rows = new HashMap<>();
            List<UUID> ids = new ArrayList<>(List.of(id));
            if (other != null) ids.add(other);
            ids.sort(Comparator.comparing(UUID::toString));
            for (UUID account : ids) rows.put(account, ensureRow(c, account));
            BigDecimal old = rows.get(id).value();
            BigDecimal next = switch (kind) {
                case "set" -> amount;
                case "take", "pay" -> old.subtract(amount);
                default -> old.add(amount);
            };
            Status status = within(next);
            BigDecimal received = other == null ? null : rows.get(other).value().add(amount);
            if (status == Status.SUCCESS && received != null) status = within(received);
            if (status != Status.SUCCESS) throw new Rejected(new TransactionResult(status, amount, old));
            update(c, id, rows.get(id), next);
            log(c, id, other, next.subtract(old), next, reason);
            Map<UUID, BigDecimal> changed = new HashMap<>(); changed.put(id, next);
            if (other != null) {
                update(c, other, rows.get(other), received);
                log(c, other, id, amount, received, reason);
                changed.put(other, received);
            }
            return new Outcome(new TransactionResult(Status.SUCCESS, amount, next), changed);
        }).thenApply(outcome -> { publish(outcome.balances()); return outcome.result(); }))
            .exceptionally(failure -> {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof Rejected rejected) return rejected.result;
                logger.error("Economy {} failed for {} (counterparty {})", kind, id, other, failure);
                return new TransactionResult(Status.FAILED, amount, cache.getOrDefault(id, start));
            });
    }
    private Status within(BigDecimal amount) {
        if (amount.compareTo(minimum) < 0) return Status.INSUFFICIENT_FUNDS;
        return amount.compareTo(maximum) > 0 ? Status.LIMIT_REACHED : Status.SUCCESS;
    }
    private void publish(Map<UUID, BigDecimal> balances) {
        balances.forEach((id, value) -> {
            if (online.contains(id)) {
                cache.put(id, value);
                if (!online.contains(id)) cache.remove(id);
            }
        });
    }
    private Stored read(Connection c, UUID id, boolean lock) throws SQLException {
        String sql = "SELECT balance FROM " + db.table("economy_accounts") + " WHERE uuid = ?"
                + (lock && db.dialect() == Dialect.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String raw = rs.getString(1);
                // Changing scale cannot silently destroy persisted money.
                return new Stored(new BigDecimal(raw).setScale(money.scale, RoundingMode.UNNECESSARY), raw);
            }
        }
    }
    private Stored ensureRow(Connection c, UUID id) throws SQLException {
        String insert = (db.dialect() == Dialect.SQLITE ? "INSERT INTO " : "INSERT IGNORE INTO ")
                + db.table("economy_accounts") + " (uuid, balance, updated_at) VALUES (?, ?, ?)"
                + (db.dialect() == Dialect.SQLITE ? " ON CONFLICT(uuid) DO NOTHING" : "");
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            Database.bind(ps, id.toString(), start.toPlainString(), System.currentTimeMillis());
            int inserted = ps.executeUpdate();
            if (inserted == 1) log(c, id, null, start, start, "account:opening");
            else if (inserted != 0) throw new SQLException("Unexpected account insertion count: " + inserted);
        }
        Stored stored = read(c, id, true);
        if (stored == null) throw new SQLException("Account creation did not produce a row: " + id);
        return stored;
    }
    private void update(Connection c, UUID id, Stored before, BigDecimal after) throws SQLException {
        if (before.value().compareTo(after) == 0) return; // Row is already locked; MySQL may report zero changed rows.
        // CAS is exact for TEXT on SQLite and DECIMAL on MySQL; no REAL coercion or stale overwrite.
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("economy_accounts")
                + " SET balance = ?, updated_at = ? WHERE uuid = ? AND balance = ?")) {
            Database.bind(ps, db.dialect() == Dialect.MYSQL ? after : after.toPlainString(),
                    System.currentTimeMillis(), id.toString(), db.dialect() == Dialect.MYSQL ? before.value() : before.raw());
            if (ps.executeUpdate() != 1) throw new SQLException("Concurrent account modification: " + id);
        }
    }
    private void log(Connection c, UUID id, UUID other, BigDecimal amount, BigDecimal after, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("economy_transactions")
                + " (id, account, counterparty, amount, balance_after, reason, created) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            Database.bind(ps, UUID.randomUUID().toString(), id.toString(), other == null ? null : other.toString(),
                    amount.toPlainString(), after.toPlainString(), reason, System.currentTimeMillis());
            if (ps.executeUpdate() != 1) throw new SQLException("Ledger insertion failed: " + id);
        }
    }
    CompletableFuture<List<Entry>> leaderboard() {
        return ordered(() -> db.query(c -> {
            List<Entry> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT a.uuid, u.name, a.balance FROM "
                    + db.table("economy_accounts") + " a LEFT JOIN " + db.table("users") + " u ON a.uuid = u.uuid");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    result.add(new Entry(id, rs.getString(2) == null ? id.toString() : rs.getString(2), new BigDecimal(rs.getString(3))));
                }
            }
            result.sort(Comparator.comparing(Entry::balance).reversed().thenComparing(e -> e.uuid().toString()));
            return List.copyOf(result);
        }));
    }
    CompletableFuture<List<LogEntry>> history(UUID id) {
        return ordered(() -> db.query(c -> {
            List<LogEntry> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT amount, balance_after, reason, created FROM "
                    + db.table("economy_transactions") + " WHERE account = ? ORDER BY created DESC, id DESC LIMIT 10")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(new LogEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
            }
            return List.copyOf(result);
        }));
    }
    @Override public String format(BigDecimal amount) { return money.format(amount); }
    @Override public String currencyNameSingular() { return singular; }
    @Override public String currencyNamePlural() { return plural; }
}
