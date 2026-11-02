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
    }), new Migration(2, "cheques", dialect -> {
        String decimal = dialect == Dialect.SQLITE ? "TEXT" : "DECIMAL(30,10)";
        String engine = dialect == Dialect.MYSQL ? " ENGINE=InnoDB" : "";
        return List.of(
            "CREATE TABLE {prefix}economy_cheques (id CHAR(36) NOT NULL PRIMARY KEY, value " + decimal + " NOT NULL, creator CHAR(36) NOT NULL, created BIGINT NOT NULL, redeemed_by CHAR(36), redeemed BIGINT)" + engine,
            "CREATE INDEX {prefix}economy_cheques_redeemed ON {prefix}economy_cheques (redeemed)");
    }), new Migration(3, "world group balances", dialect -> {
        // Accounts are rebuilt with a (uuid, world_group) key. Every existing balance moves to the default
        // group's fixed storage key, so enabling groups later (or renaming the default group) keeps all money.
        String group = "world_group VARCHAR(32) NOT NULL DEFAULT '" + WorldGroups.DEFAULT_KEY + "'";
        if (dialect == Dialect.MYSQL) {
            // MySQL commits DDL implicitly, so the key change is one atomic ALTER; existing rows get the default key.
            // Each statement is atomic on its own; the key change of the accounts table runs last. A run that
            // stops half-way is completed by repairPartialGroupMigration() before the next start migrates.
            return List.of(
                "ALTER TABLE {prefix}economy_transactions ADD COLUMN " + group
                    + ", ADD INDEX {prefix}economy_transactions_group (account, world_group, created)",
                "ALTER TABLE {prefix}economy_cheques ADD COLUMN " + group,
                "ALTER TABLE {prefix}economy_accounts ADD COLUMN " + group + " AFTER uuid, DROP PRIMARY KEY,"
                    + " ADD PRIMARY KEY (uuid, world_group)");
        }
        // SQLite cannot change a primary key in place; the rebuild runs inside the migration transaction.
        return List.of(
            "CREATE TABLE {prefix}economy_accounts_v3 (uuid CHAR(36) NOT NULL, " + group
                + ", balance TEXT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (uuid, world_group))",
            "INSERT INTO {prefix}economy_accounts_v3 (uuid, world_group, balance, updated_at) SELECT uuid, '"
                + WorldGroups.DEFAULT_KEY + "', balance, updated_at FROM {prefix}economy_accounts",
            "DROP TABLE {prefix}economy_accounts",
            "ALTER TABLE {prefix}economy_accounts_v3 RENAME TO {prefix}economy_accounts",
            "ALTER TABLE {prefix}economy_transactions ADD COLUMN " + group,
            "CREATE INDEX {prefix}economy_transactions_group ON {prefix}economy_transactions (account, world_group, created)",
            "ALTER TABLE {prefix}economy_cheques ADD COLUMN " + group);
    }));
    /**
     * MySQL only: commits every DDL statement on its own, so migration 3 can stop half-way (e.g. a lost
     * connection). Its version row is then still 2 and the next start would fail with "Duplicate column".
     * This completes whatever is missing, statement by statement, and records version 3. A no-op on SQLite
     * (one transaction) and when nothing was started.
     */
    static java.util.concurrent.CompletableFuture<Void> repairPartialGroupMigration(
            dev.yanianz.atrium.core.storage.Database db) {
        if (db.dialect() != Dialect.MYSQL) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        return db.transaction(connection -> {
            int version = schemaVersion(connection, db);
            if (version != 2) {
                return null;
            }
            String group = "world_group VARCHAR(32) NOT NULL DEFAULT '" + WorldGroups.DEFAULT_KEY + "'";
            String transactions = db.table("economy_transactions");
            String cheques = db.table("economy_cheques");
            String accounts = db.table("economy_accounts");
            boolean tx = hasColumn(connection, transactions, "world_group");
            boolean ch = hasColumn(connection, cheques, "world_group");
            boolean ac = hasColumn(connection, accounts, "world_group");
            if (!tx && !ch && !ac) {
                return null; // migration 3 never started: let it run normally
            }
            try (java.sql.Statement st = connection.createStatement()) {
                if (!tx) {
                    st.executeUpdate("ALTER TABLE " + transactions + " ADD COLUMN " + group);
                }
                if (!hasIndex(connection, transactions, db.table("economy_transactions_group"))) {
                    st.executeUpdate("ALTER TABLE " + transactions + " ADD INDEX " + db.table("economy_transactions_group")
                            + " (account, world_group, created)");
                }
                if (!ch) {
                    st.executeUpdate("ALTER TABLE " + cheques + " ADD COLUMN " + group);
                }
                if (!ac) {
                    st.executeUpdate("ALTER TABLE " + accounts + " ADD COLUMN " + group + " AFTER uuid, DROP PRIMARY KEY,"
                            + " ADD PRIMARY KEY (uuid, world_group)");
                }
            }
            String upsert = db.dialect().upsert(db.table("schema_versions"), "namespace, version", "?, ?",
                    "namespace", "version");
            try (java.sql.PreparedStatement ps = connection.prepareStatement(upsert)) {
                ps.setString(1, "economy");
                ps.setInt(2, 3);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static int schemaVersion(java.sql.Connection connection, dev.yanianz.atrium.core.storage.Database db)
            throws java.sql.SQLException {
        String table = db.table("schema_versions");
        if (!tableExists(connection, table)) {
            return 0;
        }
        try (java.sql.PreparedStatement ps = connection.prepareStatement(
                "SELECT version FROM " + table + " WHERE namespace = ?")) {
            ps.setString(1, "economy");
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static boolean tableExists(java.sql.Connection connection, String table) throws java.sql.SQLException {
        try (java.sql.ResultSet rs = connection.getMetaData().getTables(connection.getCatalog(), null, table, null)) {
            return rs.next();
        }
    }

    private static boolean hasColumn(java.sql.Connection connection, String table, String column)
            throws java.sql.SQLException {
        try (java.sql.ResultSet rs = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
            return rs.next();
        }
    }

    private static boolean hasIndex(java.sql.Connection connection, String table, String index)
            throws java.sql.SQLException {
        try (java.sql.ResultSet rs = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (rs.next()) {
                if (index.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    record AccountState(BigDecimal balance) {}
    /** One balance: an account in a world group (storage key, see {@link WorldGroups}). */
    record Key(UUID id, String group) {
        Key { Objects.requireNonNull(id); Objects.requireNonNull(group); }
    }
    record Entry(UUID uuid, String name, BigDecimal balance) {}
    record LogEntry(String amount, String balance, String reason, long created, String group) {}
    /** Outcome of a redemption attempt. {@code value} is only meaningful for {@link Claim#OK}. */
    enum Claim { OK, MISSING, ALREADY_REDEEMED, WRONG_GROUP, FAILED }
    /** {@code group} is the storage key of the group the cheque was written in. */
    record ClaimResult(Claim status, BigDecimal value, String group) {}
    private record Stored(BigDecimal value, String raw) {}
    private static final class Rejected extends RuntimeException {
        final TransactionResult result;
        Rejected(TransactionResult result) { this.result = result; }
    }
    private record Outcome(TransactionResult result, Map<Key, BigDecimal> balances) {}
    private static final int RECENT_READ_LIMIT = 4096;
    /** Insertion-ordered LRU for offline read values. Guarded by {@link Collections#synchronizedMap}. */
    private static final class ReadCache extends LinkedHashMap<Key, BigDecimal> {
        private static final long serialVersionUID = 1L;
        private final int limit;
        ReadCache(int limit) { super(64, 0.75f, true); this.limit = limit; }
        @Override protected boolean removeEldestEntry(Map.Entry<Key, BigDecimal> eldest) { return size() > limit; }
    }
    private final Database db;
    private final Logger logger;
    private final Money money;
    private final BigDecimal start, minimum, maximum;
    private final String singular, plural;
    private final WorldGroups groups;
    private final Set<UUID> online = ConcurrentHashMap.newKeySet();
    /** Group key of the world each online player is in. Absent: offline, which reads as the default group. */
    private final Map<UUID, String> current = new ConcurrentHashMap<>();
    /** Committed balances of online players, one entry per configured group. */
    private final Map<Key, BigDecimal> cache = new ConcurrentHashMap<>();
    /** Values loaded for offline Vault reads; bounded so a placeholder loop cannot grow it without limit. */
    private final Map<Key, BigDecimal> recentReads = Collections.synchronizedMap(new ReadCache(RECENT_READ_LIMIT));
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicBoolean rankingChanged = new java.util.concurrent.atomic.AtomicBoolean();
    final UserComponentType<AccountState> component;

    EconomyStore(Database db, EconomyConfig config, Logger logger) {
        this(db, config, WorldGroups.from(config.groups), logger);
    }

    EconomyStore(Database db, EconomyConfig config, WorldGroups groups, Logger logger) {
        this.db = db; this.logger = logger; this.groups = Objects.requireNonNull(groups);
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
    WorldGroups worldGroups() { return groups; }

    /**
     * Group key used by every method without a group: the current world's group while the player is online,
     * otherwise the default group. With groups disabled it is always the default group.
     */
    String contextGroup(UUID id) {
        if (!groups.enabled()) return WorldGroups.DEFAULT_KEY;
        return current.getOrDefault(id, WorldGroups.DEFAULT_KEY);
    }

    private Key context(UUID id) { return new Key(Objects.requireNonNull(id), contextGroup(id)); }

    /** Balance key of a Vault call: the group of {@code world}, or the context group when no world is given. */
    Key keyFor(UUID id, String world) {
        return new Key(Objects.requireNonNull(id), world == null ? contextGroup(id) : groups.keyOfWorld(world));
    }

    /** Records the world an online player is now in (join, world change). Ignored for offline players. */
    void moved(UUID id, String world) {
        if (online.contains(id)) current.put(id, groups.keyOfWorld(world));
    }

    /** Serialize submission through post-commit publication, so late callbacks cannot regress cache. */
    private synchronized <T> CompletableFuture<T> ordered(Supplier<CompletableFuture<T>> work) {
        if (closed) return CompletableFuture.failedFuture(new IllegalStateException("Economy disabled"));
        CompletableFuture<T> next = tail.handle((v, failure) -> null).thenCompose(v -> work.get());
        tail = next.handle((v, failure) -> null);
        return next.copy(); // Caller cancellation must not release our internal queue.
    }

    synchronized CompletableFuture<Void> close() {
        closed = true;
        online.clear();
        current.clear();
        cache.clear();
        recentReads.clear();
        return tail;
    }

    /**
     * Loads every group's balance before login and keeps them in the bounded read cache until the player
     * joins. The world is not known before login, so all groups are loaded with one query. A pre-login read
     * of a missing account reports the starting balance without creating a row, as all reads do.
     */
    CompletableFuture<Void> warm(UUID id) {
        return balances(id).thenAccept(values -> values.forEach((group, value) -> rememberRead(new Key(id, group), value)));
    }

    /** Tracks an online player in the default group; see {@link #online(UUID, String)}. */
    CompletableFuture<Void> online(UUID id) { return online(id, null); }

    /**
     * Marks the player online in the group of {@code world}, creates the account in that group and caches
     * every group's committed balance, so online reads in any group (and every commit from now on) are cached.
     */
    CompletableFuture<Void> online(UUID id, String world) {
        online.add(id);
        String group = groups.keyOfWorld(world);
        current.put(id, group);
        // The warmed values stay readable until the committed balances replace them, so the cache never has a gap.
        return ordered(() -> db.transaction(c -> {
            ensureRow(c, new Key(id, group));
            return readAll(c, id);
        }).thenAccept(values -> publish(keyed(id, values))));
    }

    boolean isOnline(UUID id) { return online.contains(id); }

    /** Newest known value for Vault reads in the context group; see {@link #cachedRead(Key)}. */
    BigDecimal cachedRead(UUID id) { return cachedRead(context(id)); }

    /** Newest known value for Vault reads: the committed balance of an online player, else a recent read. */
    BigDecimal cachedRead(Key key) {
        BigDecimal value = cache.get(key);
        return value != null ? value : recentReads.get(key);
    }

    void rememberRead(UUID id, BigDecimal value) { rememberRead(context(id), value); }

    /** Keeps an asynchronously loaded offline value so a later read that cannot wait answers from it. */
    void rememberRead(Key key, BigDecimal value) {
        if (!online.contains(key.id())) recentReads.put(key, value);
    }

    /**
     * Stops caching at quit and evicts once the queue has drained, so a commit that is still in flight
     * cannot repopulate a value after the player left.
     */
    synchronized CompletableFuture<Void> offline(UUID id) {
        online.remove(id);
        current.remove(id);
        return tail.handle((value, failure) -> null).thenRun(() -> {
            cache.keySet().removeIf(key -> key.id().equals(id));
            synchronized (recentReads) {
                recentReads.keySet().removeIf(key -> key.id().equals(id));
            }
        });
    }

    CompletableFuture<BigDecimal> ensure(UUID id) { return ensure(context(id)); }

    CompletableFuture<BigDecimal> ensure(Key key) {
        return ordered(() -> db.transaction(c -> ensureRow(c, key).value()).thenApply(value -> {
            publish(Map.of(key, value)); return value;
        }));
    }

    /** Storage key of a group name, or {@code null} when no such group is configured. */
    String key(String group) { return groups.keyOf(group); }

    @Override public CompletableFuture<BigDecimal> balance(UUID id) { return balance(context(id)); }

    @Override public CompletableFuture<BigDecimal> balance(UUID id, String group) {
        Objects.requireNonNull(id);
        String key = key(group);
        if (key == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown economy group: " + group));
        return balance(new Key(id, key));
    }

    CompletableFuture<BigDecimal> balance(Key key) {
        return ordered(() -> db.query(c -> {
            Stored stored = read(c, key, false);
            return stored == null ? start : stored.value();
        }).thenApply(value -> { publish(Map.of(key, value)); return value; }));
    }

    /** Every configured group's balance (starting balance where no row exists) plus any other stored group. */
    CompletableFuture<Map<String, BigDecimal>> balances(UUID id) {
        Objects.requireNonNull(id);
        return ordered(() -> db.query(c -> readAll(c, id)).thenApply(values -> {
            publish(keyed(id, values)); return values;
        }));
    }

    private static Map<Key, BigDecimal> keyed(UUID id, Map<String, BigDecimal> values) {
        Map<Key, BigDecimal> keyed = new HashMap<>();
        values.forEach((group, value) -> keyed.put(new Key(id, group), value));
        return keyed;
    }

    CompletableFuture<Boolean> exists(UUID id) { return exists(context(id)); }

    CompletableFuture<Boolean> exists(Key key) {
        return ordered(() -> db.query(c -> read(c, key, false) != null));
    }
    @Override public CompletableFuture<Boolean> hasAccount(UUID id) { Objects.requireNonNull(id); return exists(id); }
    @Override public CompletableFuture<Boolean> hasAccount(UUID id, String group) {
        Objects.requireNonNull(id);
        String key = key(group);
        return key == null ? CompletableFuture.completedFuture(false) : exists(new Key(id, key));
    }
    @Override public BigDecimal cachedBalance(UUID id) { return cache.get(context(id)); }
    @Override public BigDecimal cachedBalance(UUID id, String group) {
        String key = key(group);
        return key == null ? null : cache.get(new Key(id, key));
    }
    @Override public boolean groupsEnabled() { return groups.enabled(); }
    @Override public String defaultGroup() { return groups.defaultName(); }
    @Override public List<String> groups() { return groups.names(); }
    @Override public String groupOfWorld(String world) { return groups.display(groups.keyOfWorld(world)); }
    @Override public String currentGroup(UUID id) { return groups.display(contextGroup(id)); }

    private static CompletableFuture<TransactionResult> unknownGroup() {
        return CompletableFuture.completedFuture(new TransactionResult(Status.NO_ACCOUNT, BigDecimal.ZERO, BigDecimal.ZERO));
    }
    @Override public CompletableFuture<TransactionResult> deposit(UUID id, String group, BigDecimal amount, String reason) {
        String key = key(group);
        return key == null ? unknownGroup() : deposit(new Key(id, key), amount, reason);
    }
    @Override public CompletableFuture<TransactionResult> withdraw(UUID id, String group, BigDecimal amount, String reason) {
        String key = key(group);
        return key == null ? unknownGroup() : withdraw(new Key(id, key), amount, reason);
    }
    @Override public CompletableFuture<TransactionResult> set(UUID id, String group, BigDecimal amount, String reason) {
        String key = key(group);
        return key == null ? unknownGroup() : set(new Key(id, key), amount, reason);
    }
    @Override public CompletableFuture<TransactionResult> transfer(UUID from, UUID to, String group, BigDecimal amount, String reason) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        String key = key(group);
        return key == null ? unknownGroup() : transferTaxed(new Key(from, key), new Key(to, key), amount, BigDecimal.ZERO, reason);
    }
    CompletableFuture<TransactionResult> deposit(Key key, BigDecimal amount, String reason) {
        return change(key, null, amount, BigDecimal.ZERO, reason, "give");
    }
    CompletableFuture<TransactionResult> withdraw(Key key, BigDecimal amount, String reason) {
        return change(key, null, amount, BigDecimal.ZERO, reason, "take");
    }
    CompletableFuture<TransactionResult> set(Key key, BigDecimal amount, String reason) {
        // A set (/eco set, imports) can move an account anywhere in /baltop: refresh it soon, not in minutes.
        return change(key, null, amount, BigDecimal.ZERO, reason, "set").whenComplete((result, error) -> rankingChanged.set(true));
    }

    private static String cachedName(UUID id) {
        try {
            String name = org.bukkit.Bukkit.getOfflinePlayer(id).getName();
            return name == null || name.isBlank() ? id.toString() : name;
        } catch (RuntimeException e) {
            return id.toString();
        }
    }

    /** True once after a balance was set since the last call (the leaderboard should be refreshed). */
    boolean takeRankingChanged() {
        return rankingChanged.getAndSet(false);
    }
    @Override public CompletableFuture<TransactionResult> deposit(UUID id, BigDecimal amount, String reason) {
        return deposit(context(id), amount, reason);
    }
    @Override public CompletableFuture<TransactionResult> withdraw(UUID id, BigDecimal amount, String reason) {
        return withdraw(context(id), amount, reason);
    }
    @Override public CompletableFuture<TransactionResult> set(UUID id, BigDecimal amount, String reason) {
        return set(context(id), amount, reason);
    }
    /** Both sides use the payer's context group, so an implicit transfer never moves money between groups. */
    @Override public CompletableFuture<TransactionResult> transfer(UUID from, UUID to, BigDecimal amount, String reason) {
        Objects.requireNonNull(to);
        String group = contextGroup(from);
        return transferTaxed(new Key(from, group), new Key(to, group), amount, BigDecimal.ZERO, reason);
    }

    /** Taxed transfer inside the payer's context group. */
    CompletableFuture<TransactionResult> transferTaxed(UUID from, UUID to, BigDecimal amount, BigDecimal fee, String reason) {
        Objects.requireNonNull(to);
        String group = contextGroup(from);
        return transferTaxed(new Key(from, group), new Key(to, group), amount, fee, reason);
    }

    /**
     * Moves {@code amount} to {@code to} and removes {@code fee} from {@code from} in the same transaction.
     * The fee is a sink: it is never credited anywhere, and both debit rows are written to the ledger. The two
     * keys may name different groups (a permitted cross-group payment); callers decide whether that is allowed.
     */
    CompletableFuture<TransactionResult> transferTaxed(Key from, Key to, BigDecimal amount, BigDecimal fee, String reason) {
        Objects.requireNonNull(to);
        Objects.requireNonNull(fee);
        if (from.id().equals(to.id())) return rejected(from, Status.INVALID_AMOUNT);
        return change(from, to, amount, fee, reason, "pay");
    }
    private CompletableFuture<TransactionResult> rejected(Key key, Status status) {
        return balance(key).thenApply(value -> new TransactionResult(status, BigDecimal.ZERO, value));
    }
    private CompletableFuture<TransactionResult> change(Key id, Key other, BigDecimal input, BigDecimal feeInput,
            String reason, String kind) {
        Objects.requireNonNull(id);
        BigDecimal amount;
        BigDecimal fee;
        try {
            amount = money.amount(input);
            fee = feeInput == null || feeInput.signum() == 0 ? BigDecimal.ZERO : money.amount(feeInput);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return rejected(id, Status.INVALID_AMOUNT);
        }
        if (reason == null || reason.length() > 255) return rejected(id, Status.INVALID_AMOUNT);
        return ordered(() -> db.transaction(c -> {
            // Includes insertion locks: acquire both accounts in the same lexicographic order.
            Map<Key, Stored> rows = new HashMap<>();
            List<Key> ids = new ArrayList<>(List.of(id));
            if (other != null) ids.add(other);
            ids.sort(Comparator.comparing((Key key) -> key.id().toString()).thenComparing(Key::group));
            for (Key account : ids) rows.put(account, ensureRow(c, account));
            BigDecimal old = rows.get(id).value();
            BigDecimal next = switch (kind) {
                case "set" -> amount;
                case "take", "pay" -> old.subtract(amount).subtract(fee);
                default -> old.add(amount);
            };
            Status status = within(next);
            BigDecimal received = other == null ? null : rows.get(other).value().add(amount);
            if (status == Status.SUCCESS && received != null) status = within(received);
            if (status != Status.SUCCESS) throw new Rejected(new TransactionResult(status, amount, old));
            update(c, id, rows.get(id), next);
            log(c, id, other == null ? null : other.id(), next.subtract(old), next, reason);
            Map<Key, BigDecimal> changed = new HashMap<>(); changed.put(id, next);
            if (other != null) {
                update(c, other, rows.get(other), received);
                log(c, other, id.id(), amount, received, reason);
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
    private void publish(Map<Key, BigDecimal> balances) {
        balances.forEach((key, value) -> {
            UUID id = key.id();
            if (online.contains(id)) {
                cache.put(key, value);
                recentReads.remove(key);
                if (!online.contains(id)) cache.remove(key);
            } else {
                // Every commit invalidates an offline read value, so a stale entry can never be served.
                recentReads.remove(key);
            }
        });
    }
    private BigDecimal exact(String raw) {
        // Changing scale cannot silently destroy persisted money.
        return new BigDecimal(raw).setScale(money.scale, RoundingMode.UNNECESSARY);
    }
    private Stored read(Connection c, Key key, boolean lock) throws SQLException {
        String sql = "SELECT balance FROM " + db.table("economy_accounts") + " WHERE uuid = ? AND world_group = ?"
                + (lock && db.dialect() == Dialect.MYSQL ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, key.id().toString());
            ps.setString(2, key.group());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String raw = rs.getString(1);
                return new Stored(exact(raw), raw);
            }
        }
    }
    /** All configured groups (starting balance without a row) plus any stored group no longer configured. */
    private Map<String, BigDecimal> readAll(Connection c, UUID id) throws SQLException {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        for (String group : groups.keys()) values.put(group, start);
        try (PreparedStatement ps = c.prepareStatement("SELECT world_group, balance FROM " + db.table("economy_accounts")
                + " WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) values.put(rs.getString(1), exact(rs.getString(2)));
            }
        }
        return values;
    }
    private Stored ensureRow(Connection c, Key key) throws SQLException {
        String insert = (db.dialect() == Dialect.SQLITE ? "INSERT INTO " : "INSERT IGNORE INTO ")
                + db.table("economy_accounts") + " (uuid, world_group, balance, updated_at) VALUES (?, ?, ?, ?)"
                + (db.dialect() == Dialect.SQLITE ? " ON CONFLICT(uuid, world_group) DO NOTHING" : "");
        UUID id = key.id();
        try (PreparedStatement ps = c.prepareStatement(insert)) {
            Database.bind(ps, id.toString(), key.group(), start.toPlainString(), System.currentTimeMillis());
            int inserted = ps.executeUpdate();
            if (inserted == 1) log(c, key, null, start, start, "account:opening");
            else if (inserted != 0) throw new SQLException("Unexpected account insertion count: " + inserted);
        }
        Stored stored = read(c, key, true);
        if (stored == null) throw new SQLException("Account creation did not produce a row: " + id);
        return stored;
    }
    private void update(Connection c, Key key, Stored before, BigDecimal after) throws SQLException {
        if (before.value().compareTo(after) == 0) return; // Row is already locked; MySQL may report zero changed rows.
        // CAS is exact for TEXT on SQLite and DECIMAL on MySQL; no REAL coercion or stale overwrite.
        try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("economy_accounts")
                + " SET balance = ?, updated_at = ? WHERE uuid = ? AND world_group = ? AND balance = ?")) {
            Database.bind(ps, db.dialect() == Dialect.MYSQL ? after : after.toPlainString(),
                    System.currentTimeMillis(), key.id().toString(), key.group(),
                    db.dialect() == Dialect.MYSQL ? before.value() : before.raw());
            if (ps.executeUpdate() != 1) throw new SQLException("Concurrent account modification: " + key);
        }
    }
    private void log(Connection c, Key key, UUID other, BigDecimal amount, BigDecimal after, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("economy_transactions")
                + " (id, account, counterparty, amount, balance_after, reason, created, world_group) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            Database.bind(ps, UUID.randomUUID().toString(), key.id().toString(), other == null ? null : other.toString(),
                    amount.toPlainString(), after.toPlainString(), reason, System.currentTimeMillis(), key.group());
            if (ps.executeUpdate() != 1) throw new SQLException("Ledger insertion failed: " + key);
        }
    }
    /** Leaderboard of the default group. */
    CompletableFuture<List<Entry>> leaderboard(java.util.Set<String> excludedNames, long inactiveSince) {
        return leaderboards(excludedNames, inactiveSince)
                .thenApply(boards -> boards.getOrDefault(WorldGroups.DEFAULT_KEY, List.of()));
    }

    /** One sorted, immutable leaderboard per stored group key, from a single scan. */
    CompletableFuture<Map<String, List<Entry>>> leaderboards(java.util.Set<String> excludedNames, long inactiveSince) {
        return ordered(() -> db.query(c -> {
            Map<String, List<Entry>> boards = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT a.uuid, u.name, a.balance, u.last_seen, a.world_group FROM "
                    + db.table("economy_accounts") + " a LEFT JOIN " + db.table("users") + " u ON a.uuid = u.uuid");
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    if (name == null) {
                        // An imported account of a player who has not joined since Atrium was installed: the
                        // server's user cache (usercache.json) usually still knows the name.
                        name = cachedName(id);
                    }
                    if (excludedNames != null && excludedNames.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    if (inactiveSince > 0) {
                        long lastSeen = rs.getLong(4);
                        if (rs.wasNull() || lastSeen < inactiveSince) {
                            continue;
                        }
                    }
                    boards.computeIfAbsent(rs.getString(5), group -> new ArrayList<>())
                            .add(new Entry(id, name, new BigDecimal(rs.getString(3))));
                }
            }
            Map<String, List<Entry>> sorted = new HashMap<>();
            boards.forEach((group, result) -> {
                result.sort(Comparator.comparing(Entry::balance).reversed().thenComparing(e -> e.uuid().toString()));
                sorted.put(group, List.copyOf(result));
            });
            return Map.copyOf(sorted);
        }));
    }

    /** Number of stored accounts per group key, for {@code /eco groups}. */
    CompletableFuture<Map<String, Long>> storedGroups() {
        return ordered(() -> db.query(c -> {
            Map<String, Long> counts = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT world_group, COUNT(*) FROM " + db.table("economy_accounts")
                    + " GROUP BY world_group"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) counts.put(rs.getString(1), rs.getLong(2));
            }
            return Map.copyOf(counts);
        }));
    }

    // ---- cheques ----

    /**
     * Withdraws the value and stores the note in one transaction. Either the player's money and the note
     * row both exist, or neither changes; a crash can never take the money without leaving a claimable note.
     */
    CompletableFuture<TransactionResult> createCheque(UUID owner, String id, BigDecimal value) {
        return createCheque(context(owner), id, value);
    }

    /** The cheque records the group its money was taken from. */
    CompletableFuture<TransactionResult> createCheque(Key owner, String id, BigDecimal value) {
        Objects.requireNonNull(owner);
        if (id == null || id.isBlank() || id.length() > 36) {
            return rejected(owner, Status.INVALID_AMOUNT);
        }
        BigDecimal amount;
        try {
            amount = money.amount(value);
        } catch (IllegalArgumentException | ArithmeticException e) {
            return rejected(owner, Status.INVALID_AMOUNT);
        }
        if (amount.signum() <= 0) {
            return rejected(owner, Status.INVALID_AMOUNT);
        }
        return ordered(() -> db.transaction(c -> {
            Stored row = ensureRow(c, owner);
            BigDecimal next = row.value().subtract(amount);
            Status status = within(next);
            if (status != Status.SUCCESS) throw new Rejected(new TransactionResult(status, amount, row.value()));
            update(c, owner, row, next);
            log(c, owner, null, next.subtract(row.value()), next, "cheque:create:" + id);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + db.table("economy_cheques")
                    + " (id, value, creator, created, world_group) VALUES (?, ?, ?, ?, ?)")) {
                Database.bind(ps, id, amount.toPlainString(), owner.id().toString(), System.currentTimeMillis(), owner.group());
                if (ps.executeUpdate() != 1) throw new SQLException("Cheque insertion failed: " + id);
            }
            return new Outcome(new TransactionResult(Status.SUCCESS, amount, next), Map.of(owner, next));
        }).thenApply(outcome -> { publish(outcome.balances()); return outcome.result(); }))
            .exceptionally(failure -> {
                Throwable cause = failure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                if (cause instanceof Rejected rejected) return rejected.result;
                logger.error("Creating cheque {} for {} failed", id, owner, failure);
                return new TransactionResult(Status.FAILED, amount, cache.getOrDefault(owner, start));
            });
    }

    /**
     * Atomically marks the note as redeemed by this player. Only one caller can win, so a copied note is
     * worthless: the loser sees {@link Claim#ALREADY_REDEEMED}. The stored value is returned, not the value
     * printed on the item.
     */
    CompletableFuture<ClaimResult> claimCheque(String id, UUID player) {
        return claimCheque(id, player, null);
    }

    /**
     * Claims a cheque for redemption into {@code group}. When {@code group} is not null and differs from the
     * group the cheque was written in, nothing is claimed and {@link Claim#WRONG_GROUP} is returned, so the
     * note stays valid. Pass {@code null} to allow redemption in any group.
     */
    CompletableFuture<ClaimResult> claimCheque(String id, UUID player, String group) {
        return ordered(() -> db.transaction(c -> {
            BigDecimal value = null;
            String redeemed = null;
            String written = null;
            String select = "SELECT value, redeemed_by, world_group FROM " + db.table("economy_cheques") + " WHERE id = ?"
                    + (db.dialect() == Dialect.MYSQL ? " FOR UPDATE" : "");
            try (PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new ClaimResult(Claim.MISSING, BigDecimal.ZERO, null);
                    }
                    value = new BigDecimal(rs.getString(1));
                    redeemed = rs.getString(2);
                    written = rs.getString(3);
                }
            }
            if (redeemed != null) {
                return new ClaimResult(Claim.ALREADY_REDEEMED, value, written);
            }
            if (group != null && !group.equals(written)) {
                return new ClaimResult(Claim.WRONG_GROUP, value, written);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE " + db.table("economy_cheques")
                    + " SET redeemed_by = ?, redeemed = ? WHERE id = ? AND redeemed_by IS NULL")) {
                Database.bind(ps, player.toString(), System.currentTimeMillis(), id);
                if (ps.executeUpdate() != 1) {
                    return new ClaimResult(Claim.ALREADY_REDEEMED, value, written);
                }
            }
            return new ClaimResult(Claim.OK, value, written);
        }).exceptionally(failure -> {
            logger.error("Claiming cheque {} for {} failed", id, player, failure);
            return new ClaimResult(Claim.FAILED, BigDecimal.ZERO, null);
        }));
    }

    /** Gives a claimed note back when the credit could not be written, so the money is not lost. */
    CompletableFuture<Boolean> unclaimCheque(String id, UUID player) {
        return ordered(() -> db.update("UPDATE {prefix}economy_cheques SET redeemed_by = NULL, redeemed = NULL"
                + " WHERE id = ? AND redeemed_by = ?", id, player.toString()).thenApply(rows -> rows == 1));
    }

    CompletableFuture<List<LogEntry>> history(UUID id) { return history(id, null); }

    /** Latest ten ledger rows of an account, in one group key or (null) in every group. */
    CompletableFuture<List<LogEntry>> history(UUID id, String group) {
        return ordered(() -> db.query(c -> {
            List<LogEntry> result = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT amount, balance_after, reason, created, world_group FROM "
                    + db.table("economy_transactions") + " WHERE account = ?" + (group == null ? "" : " AND world_group = ?")
                    + " ORDER BY created DESC, id DESC LIMIT 10")) {
                ps.setString(1, id.toString());
                if (group != null) ps.setString(2, group);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(new LogEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                            rs.getString(5)));
                }
            }
            return List.copyOf(result);
        }));
    }
    @Override public String format(BigDecimal amount) { return money.format(amount); }
    @Override public String currencyNameSingular() { return singular; }
    @Override public String currencyNamePlural() { return plural; }
}
