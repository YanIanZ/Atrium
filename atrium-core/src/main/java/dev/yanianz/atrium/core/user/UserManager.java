package dev.yanianz.atrium.core.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.api.user.UserService;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.storage.Migration;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;

/**
 * Loads player data before login completes and keeps it in memory while the player is online.
 *
 * <ul>
 *   <li>Data is loaded in {@link AsyncPlayerPreLoginEvent}. If loading fails or times out the login is
 *       refused, so a player never plays on default data that would later overwrite the real data.</li>
 *   <li>Changes are saved in the background ({@code userSaveIntervalSeconds}), on quit, on eviction from
 *       the offline cache and on shutdown. A failed save keeps the component dirty and retries.</li>
 * </ul>
 */
public final class UserManager implements UserService, Listener {
    public static final List<Migration> MIGRATIONS = List.of(Migration.of(1, "users",
            "CREATE TABLE {prefix}users (uuid CHAR(36) NOT NULL PRIMARY KEY, name VARCHAR(16) NOT NULL, "
                    + "name_lower VARCHAR(16) NOT NULL, first_join BIGINT NOT NULL, last_seen BIGINT NOT NULL, locale VARCHAR(16))",
            "CREATE INDEX {prefix}users_name_lower ON {prefix}users (name_lower)"),
            Migration.of(2, "users last address",
                    "ALTER TABLE {prefix}users ADD COLUMN last_ip VARCHAR(45)",
                    "CREATE INDEX {prefix}users_last_ip ON {prefix}users (last_ip)"));
    private static final java.util.regex.Pattern PLAYER_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final Database database;
    private final Scheduler scheduler;
    private final Messages messages;
    private final Logger logger;
    private final IntSupplier loadTimeoutSeconds;
    private final Map<UUID, CoreUser> online = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Optional<CoreUser>>> loading = new ConcurrentHashMap<>();
    private final Cache<UUID, CoreUser> offline;
    private final List<UserComponentType<?>> types = new CopyOnWriteArrayList<>();
    /** name_lower -> display name, and name_lower -> uuid, for suggestions and name lookups. */
    private final ConcurrentSkipListMap<String, String> names = new ConcurrentSkipListMap<>();
    private final Map<String, UUID> uuidsByName = new ConcurrentHashMap<>();
    private TaskHandle flushTask = TaskHandle.NOOP;

    private record Pending(CoreUser user, Instant since) {}

    public UserManager(Database database, Scheduler scheduler, Messages messages, Logger logger,
            int offlineCacheMinutes, IntSupplier loadTimeoutSeconds) {
        this.database = database;
        this.scheduler = scheduler;
        this.messages = messages;
        this.logger = logger;
        this.loadTimeoutSeconds = loadTimeoutSeconds;
        this.offline = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(Math.max(1, offlineCacheMinutes)))
                .executor(scheduler.io())
                .removalListener((UUID uuid, CoreUser user, com.github.benmanes.caffeine.cache.RemovalCause cause) -> {
                    if (user != null && cause.wasEvicted() && !online.containsKey(uuid)) {
                        save(user);
                    }
                })
                .build();
    }

    public void start(int saveIntervalSeconds) {
        database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM " + database.table("users"));
                    ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    remember(rs.getString(2), UUID.fromString(rs.getString(1)));
                }
            }
            return null;
        }).exceptionally(t -> {
            logger.warn("Cannot load the player name index", t);
            return null;
        });
        Duration interval = Duration.ofSeconds(Math.max(5, saveIntervalSeconds));
        flushTask = scheduler.asyncTimer(this::periodicFlush, interval, interval);
    }

    private void remember(String name, UUID uuid) {
        String lower = name.toLowerCase(Locale.ROOT);
        names.put(lower, name);
        if (uuid != null) {
            uuidsByName.put(lower, uuid);
        }
    }

    // ---- events ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        String name = event.getName();
        String address = normaliseAddress(event.getAddress());
        try {
            CoreUser user = loadOrCreate(uuid, name, address).get(loadTimeoutSeconds.getAsInt(), TimeUnit.SECONDS);
            pending.put(uuid, new Pending(user, Instant.now()));
        } catch (Exception e) {
            logger.error("Could not load data of {} ({}); refusing login", name, uuid, e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.render(Locale.ROOT.toString(), "core.data-load-failed"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Pending entry = pending.remove(player.getUniqueId());
        CoreUser user = entry != null ? entry.user() : offline.getIfPresent(player.getUniqueId());
        if (user == null) {
            // Pre-login did not run for this player (should not happen); never let them play unloaded.
            logger.error("No loaded data for {} at join; kicking", player.getName());
            player.kick(messages.render(player, "core.data-load-failed"));
            return;
        }
        user.locale(player.locale());
        online.put(player.getUniqueId(), user);
        offline.invalidate(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        CoreUser user = online.remove(uuid);
        if (user == null) {
            return;
        }
        user.lastSeen(Instant.now());
        user.locale(event.getPlayer().locale());
        offline.put(uuid, user);
        save(user);
    }

    // ---- loading ----

    static String normaliseAddress(java.net.InetAddress address) {
        if (address == null) {
            return null;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= raw[i] == 0;
            }
            if (mapped && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff) {
                return (raw[12] & 0xff) + "." + (raw[13] & 0xff) + "." + (raw[14] & 0xff) + "." + (raw[15] & 0xff);
            }
        }
        return address.getHostAddress().toLowerCase(Locale.ROOT).replaceFirst("%.*$", "");
    }

    private CompletableFuture<CoreUser> loadOrCreate(UUID uuid, String name, String address) {
        CoreUser known = findLoaded(uuid);
        if (known != null) {
            known.name(name);
            known.lastAddress(address);
            remember(name, uuid);
            return saveRow(known).thenApply(v -> known);
        }
        return loadFromStorage(uuid).thenCompose(found -> {
            if (found.isPresent()) {
                CoreUser user = found.get();
                user.name(name);
                user.lastAddress(address);
                remember(name, uuid);
                return saveRow(user).thenApply(v -> user);
            }
            Instant now = Instant.now();
            CoreUser created = new CoreUser(uuid, name, now, now, Locale.US);
            created.lastAddress(address);
            remember(name, uuid);
            return saveRow(created)
                    .thenCompose(v -> loadComponents(created))
                    .thenApply(v -> created);
        });
    }

    private CoreUser findLoaded(UUID uuid) {
        CoreUser user = online.get(uuid);
        if (user != null) {
            return user;
        }
        Pending entry = pending.get(uuid);
        if (entry != null) {
            return entry.user();
        }
        return offline.getIfPresent(uuid);
    }

    private CompletableFuture<Optional<CoreUser>> loadFromStorage(UUID uuid) {
        // Not computeIfAbsent: a query that completes on the calling thread would run the removal below inside
        // the mapping function ("IllegalStateException: Recursive update", seen with imports on the I/O thread).
        CompletableFuture<Optional<CoreUser>> created = new CompletableFuture<>();
        CompletableFuture<Optional<CoreUser>> running = loading.putIfAbsent(uuid, created);
        if (running != null) {
            return running;
        }
        UUID id = uuid;
        database.query(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT name, first_join, last_seen, locale, last_ip FROM "
                            + database.table("users") + " WHERE uuid = ?")) {
                        ps.setString(1, id.toString());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                return Optional.<CoreUser>empty();
                            }
                            String locale = rs.getString(4);
                            CoreUser user = new CoreUser(id, rs.getString(1),
                                    Instant.ofEpochMilli(rs.getLong(2)), Instant.ofEpochMilli(rs.getLong(3)),
                                    locale == null ? Locale.US : Locale.forLanguageTag(locale.replace('_', '-')));
                            user.lastAddress(rs.getString(5));
                            return Optional.of(user);
                        }
                    }
                })
                .thenCompose(found -> found.isEmpty()
                        ? CompletableFuture.completedFuture(found)
                        : loadComponents(found.get()).thenApply(v -> found))
                .whenComplete((result, error) -> {
                    loading.remove(id, created);
                    if (error != null) {
                        created.completeExceptionally(error);
                    } else {
                        created.complete(result);
                    }
                });
        return created;
    }

    private CompletableFuture<Void> loadComponents(CoreUser user) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (UserComponentType<?> type : types) {
            futures.add(loadComponent(user, type));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    private <T> CompletableFuture<Void> loadComponent(CoreUser user, UserComponentType<T> type) {
        return type.loader().load(user.uuid()).thenAccept(value ->
                user.components.putIfAbsent(type, value != null ? value : type.defaults().get()));
    }

    // ---- saving ----

    private CompletableFuture<Void> saveRow(CoreUser user) {
        String sql = database.dialect().upsert(database.table("users"),
                "uuid, name, name_lower, first_join, last_seen, locale, last_ip", "?, ?, ?, ?, ?, ?, ?", "uuid",
                "name, name_lower, last_seen, locale, last_ip");
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, user.uuid().toString(), user.name(), user.name().toLowerCase(Locale.ROOT),
                        user.firstJoin().toEpochMilli(), user.lastSeen().toEpochMilli(), user.locale().toString(),
                        user.lastAddressOrNull());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Saves the user row and every dirty component. A failed component stays dirty for the next flush. */
    public CompletableFuture<Void> save(CoreUser user) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        futures.add(saveRow(user).exceptionally(t -> {
            logger.warn("Saving user row of {} failed", user.name(), t);
            return null;
        }));
        for (UserComponentType<?> type : List.copyOf(user.dirty)) {
            futures.add(saveComponent(user, type));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<Void> saveComponent(CoreUser user, UserComponentType<T> type) {
        if (!user.dirty.remove(type)) {
            return CompletableFuture.completedFuture(null);
        }
        T value = (T) user.components.get(type);
        if (value == null) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> saved;
        try {
            saved = type.saver().save(user.uuid(), value);
        } catch (RuntimeException e) {
            saved = CompletableFuture.failedFuture(e);
        }
        return saved.exceptionally(t -> {
            user.dirty.add(type);
            logger.warn("Saving {} of {} failed; will retry", type.id(), user.name(), t);
            return null;
        });
    }

    private void periodicFlush() {
        for (CoreUser user : online.values()) {
            if (!user.dirty.isEmpty()) {
                save(user);
            }
        }
        for (CoreUser user : offline.asMap().values()) {
            if (!user.dirty.isEmpty()) {
                save(user);
            }
        }
        Instant cutoff = Instant.now().minusSeconds(60);
        pending.entrySet().removeIf(e -> {
            if (e.getValue().since().isBefore(cutoff)) {
                offline.put(e.getKey(), e.getValue().user());
                return true;
            }
            return false;
        });
    }

    /** Saves everything and waits (plugin disable). */
    public void shutdown() {
        flushTask.cancel();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (CoreUser user : online.values()) {
            user.lastSeen(Instant.now());
            futures.add(save(user));
        }
        for (CoreUser user : offline.asMap().values()) {
            futures.add(save(user));
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.error("Not all player data was saved during shutdown", e);
        }
    }

    // ---- UserService ----

    @Override
    public AtriumUser user(Player player) {
        CoreUser user = online.get(player.getUniqueId());
        if (user == null) {
            throw new IllegalStateException("User not loaded: " + player.getName());
        }
        return user;
    }

    @Override
    public Optional<AtriumUser> cached(UUID uuid) {
        return Optional.ofNullable(findLoaded(uuid));
    }

    @Override
    public CompletableFuture<Optional<AtriumUser>> load(UUID uuid) {
        CoreUser known = findLoaded(uuid);
        if (known != null) {
            return CompletableFuture.completedFuture(Optional.of(known));
        }
        return loadFromStorage(uuid).thenApply(found -> {
            found.ifPresent(user -> {
                CoreUser raced = findLoaded(uuid);
                if (raced == null) {
                    offline.put(uuid, user);
                }
            });
            CoreUser current = findLoaded(uuid);
            return Optional.ofNullable(current != null ? current : found.orElse(null));
        });
    }

    @Override
    public CompletableFuture<Optional<AtriumUser>> loadByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        UUID known = uuidsByName.get(lower);
        if (known != null) {
            return load(known);
        }
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + database.table("users")
                    + " WHERE name_lower = ? ORDER BY last_seen DESC")) {
                ps.setString(1, lower);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.<UUID>empty();
                }
            }
        }).thenCompose(uuid -> uuid.isEmpty()
                ? CompletableFuture.completedFuture(Optional.<AtriumUser>empty())
                : load(uuid.get()));
    }

    @Override
    public Collection<AtriumUser> online() {
        return List.copyOf(online.values());
    }

    @Override
    public <T> void registerComponent(UserComponentType<T> type) {
        types.add(type);
        List<CoreUser> loaded = new ArrayList<>(online.values());
        loaded.addAll(offline.asMap().values());
        pending.values().forEach(p -> loaded.add(p.user()));
        for (CoreUser user : loaded) {
            loadComponent(user, type).exceptionally(t -> {
                logger.error("Loading {} for {} failed", type.id(), user.name(), t);
                return null;
            });
        }
    }

    public void unregisterComponent(UserComponentType<?> type) {
        types.remove(type);
    }

    // ---- export / import by component id (profile snapshots) ----

    private record CodecEntry<T>(UserComponentType<T> type, ComponentCodec<T> codec) {}

    private final Map<String, CodecEntry<?>> codecs = new ConcurrentHashMap<>();

    /**
     * Registers how a component is converted to bytes, which enables {@link #exportComponent} and
     * {@link #importComponent} for its id. One codec per component id.
     */
    public <T> void registerCodec(UserComponentType<T> type, ComponentCodec<T> codec) {
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(codec, "codec");
        CodecEntry<?> previous = codecs.putIfAbsent(type.id(), new CodecEntry<>(type, codec));
        if (previous != null && (previous.type() != type || previous.codec() != codec)) {
            throw new IllegalStateException("A codec is already registered for component " + type.id());
        }
    }

    /** Removes the codec registered for this component (only when it is this codec). */
    public <T> void unregisterCodec(UserComponentType<T> type, ComponentCodec<T> codec) {
        codecs.computeIfPresent(type.id(), (id, entry) -> entry.type() == type && entry.codec() == codec ? null : entry);
    }

    /** Ids of the components that can be exported and imported. */
    public java.util.Set<String> exportableComponents() {
        return java.util.Set.copyOf(codecs.keySet());
    }

    /** The registered component type with this id, if any. */
    public Optional<UserComponentType<?>> componentType(String id) {
        for (UserComponentType<?> type : types) {
            if (type.id().equals(id)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }

    /**
     * The in-memory value of a loaded user's component as bytes; empty when the value equals the component's
     * default (nothing to keep). Synchronous and storage-free. Fails when the user, the component value or
     * the codec is missing, so a caller never mistakes "not loaded" for "empty".
     */
    public Optional<byte[]> exportComponent(UUID uuid, String componentId) throws Exception {
        CodecEntry<?> entry = codecs.get(componentId);
        if (entry == null) {
            throw new IllegalStateException("No codec registered for component " + componentId);
        }
        CoreUser user = findLoaded(uuid);
        if (user == null) {
            throw new IllegalStateException("User " + uuid + " is not loaded");
        }
        return export(user, entry);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<byte[]> export(CoreUser user, CodecEntry<T> entry) throws Exception {
        Object raw = user.components.get(entry.type());
        if (raw == null) {
            throw new IllegalStateException("Component " + entry.type().id() + " is not loaded for " + user.uuid());
        }
        T value = (T) raw;
        if (value.equals(entry.type().defaults().get())) {
            return Optional.empty();
        }
        return Optional.of(entry.codec().encode(value));
    }

    /**
     * Replaces a loaded user's component with the decoded bytes ({@code null} = the component's default)
     * in memory right away, then stores it. The returned future completes when the value is stored, or
     * exceptionally (the value stays dirty and is retried by the next flush). Decoding errors are thrown
     * before anything changes.
     */
    public CompletableFuture<Void> importComponent(UUID uuid, String componentId, byte @org.jspecify.annotations.Nullable [] data)
            throws Exception {
        CodecEntry<?> entry = codecs.get(componentId);
        if (entry == null) {
            throw new IllegalStateException("No codec registered for component " + componentId);
        }
        CoreUser user = findLoaded(uuid);
        if (user == null) {
            throw new IllegalStateException("User " + uuid + " is not loaded");
        }
        return replace(user, entry, data);
    }

    private <T> CompletableFuture<Void> replace(CoreUser user, CodecEntry<T> entry, byte @org.jspecify.annotations.Nullable [] data)
            throws Exception {
        T value = data == null ? entry.type().defaults().get() : entry.codec().decode(data);
        if (value == null) {
            throw new IllegalArgumentException("Codec of " + entry.type().id() + " decoded null");
        }
        user.components.put(entry.type(), value);
        user.dirty.add(entry.type());
        return saveComponentStrict(user, entry.type());
    }

    @Override
    public CompletableFuture<Void> saveNow(UUID uuid) {
        CoreUser user = findLoaded(uuid);
        if (user == null) {
            return CompletableFuture.completedFuture(null);
        }
        List<CompletableFuture<?>> futures = new ArrayList<>();
        futures.add(saveRow(user));
        for (UserComponentType<?> type : List.copyOf(user.dirty)) {
            futures.add(saveComponentStrict(user, type));
        }
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<Void> saveComponentStrict(CoreUser user, UserComponentType<T> type) {
        if (!user.dirty.remove(type)) {
            return CompletableFuture.completedFuture(null);
        }
        T value = (T) user.components.get(type);
        CompletableFuture<Void> saved;
        try {
            saved = value == null ? CompletableFuture.completedFuture(null) : type.saver().save(user.uuid(), value);
        } catch (RuntimeException e) {
            saved = CompletableFuture.failedFuture(e);
        }
        return saved.whenComplete((v, t) -> {
            if (t != null) {
                user.dirty.add(type);
                logger.warn("Saving {} of {} failed; will retry", type.id(), user.name(), t);
            }
        });
    }

    @Override
    public CompletableFuture<Optional<PlayerRef>> resolveAccount(String name, boolean includeNeverJoined) {
        Player online = org.bukkit.Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new PlayerRef(online.getUniqueId(), online.getName())));
        }
        return loadByName(name).thenCompose(user -> {
            if (user.isPresent()) {
                return CompletableFuture.completedFuture(user.map(u -> new PlayerRef(u.uuid(), u.name())));
            }
            if (!includeNeverJoined || !PLAYER_NAME.matcher(name).matches()) {
                return CompletableFuture.completedFuture(Optional.<PlayerRef>empty());
            }
            // Blocking profile lookup (Mojang in online mode, offline UUID otherwise): run on the I/O executor.
            return scheduler.supplyIo(() -> {
                var profile = org.bukkit.Bukkit.createProfile(name);
                if (!profile.complete(false) || profile.getId() == null) {
                    return Optional.<PlayerRef>empty();
                }
                return Optional.of(new PlayerRef(profile.getId(), profile.getName() != null ? profile.getName() : name));
            });
        });
    }

    @Override
    public CompletableFuture<List<PlayerRef>> accountsByAddress(String address) {
        return database.query(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name FROM " + database.table("users")
                    + " WHERE last_ip = ? ORDER BY last_seen DESC")) {
                ps.setString(1, address);
                try (ResultSet rs = ps.executeQuery()) {
                    List<PlayerRef> refs = new ArrayList<>();
                    while (rs.next()) {
                        refs.add(new PlayerRef(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                    return refs;
                }
            }
        });
    }

    @Override
    public Collection<PlayerRef> knownAccounts(int limit) {
        List<PlayerRef> refs = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : uuidsByName.entrySet()) {
            if (refs.size() >= limit) {
                break;
            }
            refs.add(new PlayerRef(entry.getValue(), names.getOrDefault(entry.getKey(), entry.getKey())));
        }
        return refs;
    }

    @Override
    public Collection<String> knownNames(String prefix, int limit) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return names.subMap(lower, true, lower + Character.MAX_VALUE, true).values().stream().limit(limit).toList();
    }
}
