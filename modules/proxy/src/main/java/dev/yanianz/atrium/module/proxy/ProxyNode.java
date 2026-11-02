package dev.yanianz.atrium.module.proxy;

import dev.yanianz.atrium.api.proxy.NetworkPlayer;
import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Runtime of the {@code proxy} module: the BungeeCord plugin channel, the authenticated envelope, the
 * server/player cache and the bounded outbound queue.
 *
 * <p>Threading: {@link PluginMessageListener} runs on the joining player's region thread; bytes are copied
 * immediately and the envelope is parsed and dispatched on the async scheduler. Subscribers therefore run
 * on an async thread and must schedule any world/entity access themselves. Outgoing channel writes always
 * happen on the carrier player's own thread.
 */
final class ProxyNode implements ProxyService, PluginMessageListener {
    static final String CHANNEL = "BungeeCord";
    static final String LEGACY_CHANNEL = "bungeecord:main";
    static final String SUBCHANNEL_PREFIX = "atrium:";
    private static final int MAX_FORWARD_BYTES = Short.MAX_VALUE;

    private final ModuleContext ctx;
    private final byte[] secret;
    private final long trustWindowMillis;
    private final long rejectLogMillis;
    private final OutboundQueue outbox;
    private final ReplayGuard replay;
    private final Map<String, List<Consumer<ProxyMessage>>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> playersByServer = new ConcurrentHashMap<>();
    /** Permission nodes already handed to the global scheduler, so repeated syncs do not queue them again. */
    private final Set<String> registeredPermissions = ConcurrentHashMap.newKeySet();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong rejectLogAt = new AtomicLong();

    private volatile String configuredName;
    private volatile String detectedName = "";
    private volatile Set<String> servers = Set.of();
    private volatile long lastSyncAt;
    private volatile boolean registered;
    private volatile boolean warnNameMismatch = true;

    ProxyNode(ModuleContext ctx, byte[] secret, ProxyConfig config) {
        this.ctx = ctx;
        this.secret = secret.clone();
        this.configuredName = config.serverName == null ? "" : config.serverName.strip();
        this.trustWindowMillis = Math.max(5, Math.min(300, config.trustWindowSeconds)) * 1000L;
        this.rejectLogMillis = Math.max(1, config.rejectLogSeconds) * 1000L;
        this.outbox = new OutboundQueue(Math.max(1, Math.min(4096, config.queueMaxMessages)),
                Math.max(5, Math.min(3600, config.queueTtlSeconds)) * 1000L);
        long configuredReplayTtl = Math.max(60, Math.min(1800, config.replayCacheSeconds)) * 1000L;
        this.replay = new ReplayGuard(Math.max(256, Math.min(65536, config.replayCacheSize)),
                ReplayGuard.effectiveTtlMillis(configuredReplayTtl, trustWindowMillis));
    }

    // ---- lifecycle ----

    void enable() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerOutgoingPluginChannel(ctx.plugin(), CHANNEL);
        messenger.registerIncomingPluginChannel(ctx.plugin(), CHANNEL, this);
        registered = true;
        refreshLocalPlayers();
        registerPermission(serverName());
    }

    void disable() {
        registered = false;
        subscribers.clear();
        if (Bukkit.getMessenger().isIncomingChannelRegistered(ctx.plugin(), CHANNEL)) {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(ctx.plugin(), CHANNEL, this);
        }
        if (Bukkit.getMessenger().isOutgoingChannelRegistered(ctx.plugin(), CHANNEL)) {
            Bukkit.getMessenger().unregisterOutgoingPluginChannel(ctx.plugin(), CHANNEL);
        }
    }

    // ---- ProxyService ----

    @Override
    public String serverName() {
        String configured = configuredName;
        if (!configured.isBlank()) {
            return configured;
        }
        String detected = detectedName;
        return detected.isBlank() ? "unknown" : detected;
    }

    @Override
    public boolean connect(Player player, String server) {
        if (player == null || !validServerName(server) || !player.isOnline()) {
            return false;
        }
        byte[] message = utf("Connect", server);
        ctx.scheduler().entity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            sendNow(player, message);
        }, null);
        return true;
    }

    @Override
    public void publish(String topic, byte[] payload) {
        ProxyEnvelope envelope = ProxyEnvelope.create(serverName(), topic, payload);
        byte[] encoded = envelope.encode(secret);
        byte[] forward = forward(encoded, envelope.topic());
        Player carrier = firstOnline();
        if (carrier == null) {
            outbox.offer(forward, System.currentTimeMillis());
            return;
        }
        sendOn(carrier, forward, true);
    }

    @Override
    public Subscription subscribe(String topic, Consumer<ProxyMessage> consumer) {
        String key = ProxyEnvelope.requireTopic(topic);
        if (consumer == null) {
            throw new IllegalArgumentException("consumer is required");
        }
        List<Consumer<ProxyMessage>> list = subscribers.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(consumer);
        return () -> {
            list.remove(consumer);
            if (list.isEmpty()) {
                subscribers.remove(key, list);
            }
        };
    }

    @Override
    public Collection<NetworkPlayer> networkPlayers() {
        List<NetworkPlayer> out = new ArrayList<>();
        playersByServer.forEach((server, names) -> {
            for (String name : names) {
                Player local = Bukkit.getPlayerExact(name);
                UUID uuid = local == null ? null : local.getUniqueId();
                out.add(new NetworkPlayer(name, uuid, server));
            }
        });
        out.sort(Comparator.comparing(NetworkPlayer::server, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(NetworkPlayer::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    @Override
    public Collection<String> servers() {
        Set<String> snapshot = servers;
        List<String> out = new ArrayList<>(snapshot.size() + 1);
        out.addAll(snapshot);
        String own = serverName();
        if (!"unknown".equals(own) && out.stream().noneMatch(s -> s.equalsIgnoreCase(own))) {
            out.add(own);
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(out);
    }

    // ---- cache updates from the rest of the plugin ----

    void playerJoined(Player player) {
        String server = serverName();
        if (!"unknown".equals(server)) {
            playersByServer.computeIfAbsent(server, k -> ConcurrentHashMap.newKeySet()).add(player.getName());
        }
    }

    void playerQuit(Player player) {
        String name = player.getName();
        playersByServer.values().forEach(names -> names.remove(name));
    }

    /** Asks the proxy for the server list and the player list of every known server. */
    void sync() {
        if (!registered || firstOnline() == null) {
            return;
        }
        if (configuredName.isBlank() && detectedName.isBlank()) {
            sendRequest(utf("GetServer"));
        }
        sendRequest(utf("GetServers"));
        Set<String> snapshot = servers;
        if (snapshot.isEmpty()) {
            String own = serverName();
            if (!"unknown".equals(own)) {
                sendRequest(utf("PlayerList", own));
            }
        } else {
            for (String server : snapshot) {
                sendRequest(utf("PlayerList", server));
            }
        }
    }

    void flushQueue(Player player) {
        List<byte[]> pending = outbox.drain(System.currentTimeMillis());
        if (pending.isEmpty()) {
            return;
        }
        ctx.scheduler().entityLater(player, () -> {
            if (!player.isOnline()) {
                pending.forEach(data -> outbox.offer(data, System.currentTimeMillis()));
                return;
            }
            for (byte[] data : pending) {
                sendNow(player, data);
            }
        }, () -> pending.forEach(data -> outbox.offer(data, System.currentTimeMillis())), 1L);
    }

    Status status() {
        long now = System.currentTimeMillis();
        return new Status(serverName(), lastSyncAt, outbox.size(now), outbox.dropped(), rejected.get(),
                servers.size(), networkPlayers().size());
    }

    // ---- incoming plugin messages ----

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel) && !LEGACY_CHANNEL.equals(channel)) {
            return;
        }
        if (!registered) {
            return;
        }
        // The array may be reused by the server; copy before leaving this thread.
        byte[] data = message == null ? new byte[0] : message.clone();
        ctx.scheduler().async(() -> handle(data));
    }

    private void handle(byte[] data) {
        if (!registered) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            String sub = in.readUTF();
            switch (sub) {
                case "GetServer" -> handleServerName(in);
                case "GetServers" -> handleServers(in);
                case "PlayerList" -> handlePlayerList(in);
                case "Forward" -> handleForward(in);
                default -> {
                    // Other subchannels are not ours (proxy chat, ServerIP, ...).
                }
            }
        } catch (ProxyEnvelope.InvalidEnvelopeException e) {
            reject(e.reason(), e.getMessage());
        } catch (IOException e) {
            reject(ProxyEnvelope.Reason.MALFORMED, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            ctx.logger().warn("Unexpected error while handling a proxy message", e);
        }
    }

    private void handleServerName(DataInputStream in) throws IOException {
        String name = in.readUTF();
        if (!validServerName(name)) {
            reject(ProxyEnvelope.Reason.MALFORMED, "invalid server name");
            return;
        }
        detectedName = name;
        registerPermission(name);
        if (!configuredName.isBlank() && !configuredName.equalsIgnoreCase(name) && warnNameMismatch) {
            warnNameMismatch = false;
            ctx.logger().warn("proxy.yml calls this server '{}' but the proxy calls it '{}'; using the configured name",
                    configuredName, name);
        }
        playersByServer.computeIfAbsent(serverName(), k -> ConcurrentHashMap.newKeySet());
        refreshLocalPlayers();
    }

    private void handleServers(DataInputStream in) throws IOException {
        String csv = in.readUTF();
        Set<String> parsed = ConcurrentHashMap.newKeySet();
        for (String part : csv.split(",")) {
            String name = part.strip();
            if (validServerName(name)) {
                parsed.add(name);
                registerPermission(name);
            }
        }
        String own = serverName();
        if (!"unknown".equals(own)) {
            parsed.add(own);
        }
        servers = Set.copyOf(parsed);
        playersByServer.keySet().removeIf(name -> !parsed.contains(name));
        lastSyncAt = System.currentTimeMillis();
        // PlayerList is per server; ask for each one so the snapshot stays fresh.
        for (String server : parsed) {
            sendRequest(utf("PlayerList", server));
        }
    }

    private void handlePlayerList(DataInputStream in) throws IOException {
        String server = in.readUTF();
        String names = in.readUTF();
        if (!validServerName(server)) {
            reject(ProxyEnvelope.Reason.MALFORMED, "invalid server name");
            return;
        }
        Set<String> set = ConcurrentHashMap.newKeySet();
        for (String part : names.split(",")) {
            String name = sanitizePlayerName(part.strip());
            if (name != null) {
                set.add(name);
            }
        }
        playersByServer.put(server, set);
        lastSyncAt = System.currentTimeMillis();
    }

    private void handleForward(DataInputStream in) throws IOException, ProxyEnvelope.InvalidEnvelopeException {
        String sub = in.readUTF();
        if (!sub.startsWith(SUBCHANNEL_PREFIX)) {
            // Another plugin's payload on Forward/ALL; not addressed to us.
            return;
        }
        String topic = sub.substring(SUBCHANNEL_PREFIX.length());
        int length = in.readUnsignedShort();
        if (length == 0 || length > MAX_FORWARD_BYTES) {
            reject(ProxyEnvelope.Reason.TOO_LARGE, "forward length " + length);
            return;
        }
        if (length > in.available()) {
            reject(ProxyEnvelope.Reason.MALFORMED, "forward length exceeds message");
            return;
        }
        byte[] encoded = new byte[length];
        in.readFully(encoded);
        ProxyEnvelope envelope = ProxyEnvelope.decode(encoded, secret, System.currentTimeMillis(), trustWindowMillis);
        if (!envelope.topic().equals(topic)) {
            reject(ProxyEnvelope.Reason.MALFORMED, "topic does not match the subchannel");
            return;
        }
        String own = serverName();
        if (!"unknown".equals(own) && envelope.origin().equalsIgnoreCase(own)) {
            return; // our own Forward/ALL echo
        }
        if (!replay.accept(envelope.nonce(), envelope.timestamp(), System.currentTimeMillis())) {
            reject(ProxyEnvelope.Reason.REPLAY, "duplicate or too old nonce");
            return;
        }
        deliver(new ProxyMessage(envelope.origin(), envelope.topic(), envelope.timestamp(), envelope.payload()));
    }

    private void deliver(ProxyMessage message) {
        List<Consumer<ProxyMessage>> list = subscribers.get(message.topic());
        if (list == null || list.isEmpty()) {
            return;
        }
        for (Consumer<ProxyMessage> consumer : list) {
            try {
                consumer.accept(message);
            } catch (RuntimeException e) {
                ctx.logger().warn("Proxy subscriber of topic '{}' failed", message.topic(), e);
            }
        }
    }

    // ---- outgoing helpers ----

    private void sendRequest(byte[] data) {
        Player carrier = firstOnline();
        if (carrier == null) {
            return;
        }
        sendOn(carrier, data, false);
    }

    private void sendOn(Player carrier, byte[] data, boolean requeueOnRetire) {
        ctx.scheduler().entity(carrier, () -> sendNow(carrier, data), () -> {
            if (requeueOnRetire) {
                outbox.offer(data, System.currentTimeMillis());
            }
        });
    }

    private void sendNow(Player carrier, byte[] data) {
        try {
            carrier.sendPluginMessage(ctx.plugin(), CHANNEL, data);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not send a proxy message through {}: {}", carrier.getName(), e.toString());
        }
    }

    private Player firstOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    private void refreshLocalPlayers() {
        String own = serverName();
        if ("unknown".equals(own)) {
            return;
        }
        Set<String> local = playersByServer.computeIfAbsent(own, k -> ConcurrentHashMap.newKeySet());
        for (Player player : Bukkit.getOnlinePlayers()) {
            local.add(player.getName());
        }
    }

    /**
     * Registers {@code atrium.server.<name>} on the global thread. {@code handle} runs on the async
     * scheduler: {@code PluginManager.addPermission} recalculates every permissible, so calling it from a
     * non-global thread can corrupt permissions or throw a concurrent modification exception. The node
     * set makes repeated syncs cheap and keeps the call idempotent.
     */
    private void registerPermission(String server) {
        if (!validServerName(server) || "unknown".equalsIgnoreCase(server)) {
            // "unknown" is the sentinel used until the proxy reports the name; it is not a real server.
            return;
        }
        String permission = "atrium.server." + server;
        String key = permission.toLowerCase(Locale.ROOT);
        if (!registeredPermissions.add(key)) {
            return;
        }
        try {
            ctx.scheduler().global(() -> ctx.permissions().register(permission, PermissionDefault.OP,
                    "Use /server " + server));
        } catch (RuntimeException e) {
            // The server is already stopping; a later enable registers the node again.
            registeredPermissions.remove(key);
            ctx.logger().debug("Cannot schedule registration of permission {}", permission, e);
        }
    }

    private void reject(ProxyEnvelope.Reason reason, String detail) {
        long total = rejected.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = rejectLogAt.get();
        if (now - last >= rejectLogMillis && rejectLogAt.compareAndSet(last, now)) {
            // Deliberately no payload, origin or secret: a rejected message is untrusted.
            ctx.logger().warn("Rejected a proxy message ({}: {}). Total rejected: {}", reason, detail, total);
        }
    }

    static byte[] forward(byte[] envelope, String topic) {
        if (envelope.length > MAX_FORWARD_BYTES) {
            throw new IllegalArgumentException("proxy payload is " + envelope.length
                    + " bytes after signing, the BungeeCord Forward limit is " + MAX_FORWARD_BYTES);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(envelope.length + 32);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeUTF("Forward");
            out.writeUTF("ALL");
            out.writeUTF(SUBCHANNEL_PREFIX + topic);
            out.writeShort(envelope.length);
            out.write(envelope);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build a proxy envelope", e);
        }
        return buffer.toByteArray();
    }

    static byte[] utf(String... parts) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            for (String part : parts) {
                out.writeUTF(part);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build a proxy message", e);
        }
        return buffer.toByteArray();
    }

    static boolean validServerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static String sanitizePlayerName(String name) {
        if (name.isEmpty() || name.length() > 32) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == '\u00a7' || c == ',' || c == ';') {
                return null;
            }
        }
        return name;
    }

    record Status(String server, long lastSyncAt, int queued, long dropped, long rejected, int servers, int players) {
        String lastSyncText() {
            if (lastSyncAt <= 0) {
                return "-";
            }
            long seconds = Math.max(0, (System.currentTimeMillis() - lastSyncAt) / 1000L);
            return String.format(Locale.ROOT, "%ds ago", seconds);
        }
    }
}
