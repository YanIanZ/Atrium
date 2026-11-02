package dev.yanianz.atrium.module.chatbubbles;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.yanianz.atrium.api.chat.IgnoreService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Client-side bubbles. A fake TextDisplay is spawned for each viewer and mounted on the speaker with
 * {@code SET_PASSENGERS}; the client then moves it with the player, so there is no server entity and no
 * teleport lag. The speaker keeps their real passengers: every mount packet is built from the passenger
 * list read on the speaker's own thread plus the fake id, and an outgoing-packet listener appends the fake
 * id when the server (or another plugin) sends its own {@code SET_PASSENGERS} for the speaker.
 *
 * <p>This class is only loaded when the packetevents plugin is enabled; see {@link PacketBubbleFactory}.
 * Metadata ids follow the vanilla data layout: Entity contributes 8 ids, Display contributes its
 * transformation ids, then TextDisplay contributes text/line width/background/opacity/style flags.
 * Display has three interpolation ids since 1.21.2 and two before it, so the later ids are selected per
 * server version in {@link #metadata}.
 */
final class PacketBubbleRenderer implements BubbleRenderer {
    /** Viewers are re-checked once per second so joins and players walking into range get an active bubble. */
    private static final long REFRESH_TICKS = 20L;
    /** Negative ids cannot collide with the server's positive entity counter. */
    private static final int FIRST_ENTITY_ID = -1_000_000_000;
    private static final int BILLBOARD_CENTER = 3;
    private static final byte OPACITY_OPAQUE = (byte) -1;
    private static final int FLAG_SHADOW = 0x01;
    private static final int FLAG_SEE_THROUGH = 0x02;

    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(FIRST_ENTITY_ID);

    private final ModuleContext ctx;
    private final Supplier<ChatBubblesConfig> config;
    private final PlayerManager players;
    /** Version-specific TextDisplay metadata ids; see {@link BubbleMetaIds}. */
    private final BubbleMetaIds metaIds;
    private final Map<UUID, Bubble> bubbles = new ConcurrentHashMap<>();
    /** Speaker entity id -> bubble, used by the outgoing SET_PASSENGERS listener. */
    private final Map<Integer, Bubble> bySpeakerEntity = new ConcurrentHashMap<>();
    /** Locations written on each viewer's own thread; read by bubble refresh without touching the player. */
    private final Map<UUID, BubbleReceivers.Viewer> viewers = new ConcurrentHashMap<>();
    private volatile PacketListenerCommon mountListener;
    private volatile boolean running = true;

    private PacketBubbleRenderer(ModuleContext ctx, Supplier<ChatBubblesConfig> config, PlayerManager players,
            BubbleMetaIds metaIds) {
        this.ctx = ctx;
        this.config = config;
        this.players = players;
        this.metaIds = metaIds;
    }

    /** Called only when packetevents is enabled. Returns {@code null} when this server cannot use packets. */
    static PacketBubbleRenderer create(ModuleContext ctx, Supplier<ChatBubblesConfig> config) {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) {
            ctx.logger().warn("PacketEvents is enabled but its API is not ready; chat bubbles use follow mode");
            return null;
        }
        ServerVersion version = api.getServerManager().getVersion();
        if (version.isOlderThan(ServerVersion.V_1_19_4)) {
            ctx.logger().warn("Packet chat bubbles need Minecraft 1.19.4 or newer (server is {}); using follow mode",
                    version);
            return null;
        }
        BubbleMetaIds metaIds = BubbleMetaIds.of(version.isNewerThanOrEquals(ServerVersion.V_1_21_2));
        PacketBubbleRenderer renderer = new PacketBubbleRenderer(ctx, config, api.getPlayerManager(), metaIds);
        try {
            renderer.mountListener = api.getEventManager()
                    .registerListener(new MountListener(renderer));
        } catch (RuntimeException error) {
            ctx.logger().warn("Could not register the chat bubble passenger listener; using follow mode", error);
            return null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            renderer.seed(player);
        }
        return renderer;
    }

    private void seed(Player player) {
        try {
            ctx.scheduler().entity(player, () -> track(player), null);
        } catch (RuntimeException error) {
            ctx.logger().debug("Could not seed the chat bubble view of {}", player.getName(), error);
        }
    }

    /** Called on the viewer's own thread (join, move, respawn). */
    void track(Player player) {
        Location location = player.getLocation();
        viewers.put(player.getUniqueId(), new BubbleReceivers.Viewer(
                player.getUniqueId(), player.getWorld().getUID(),
                location.getX(), location.getY(), location.getZ()));
    }

    /** Called when a player leaves; also ends their own bubble through the module event. */
    void forget(UUID player) {
        viewers.remove(player);
    }

    @Override
    public void show(Player speaker, Component text, ChatBubblesConfig cfg, BubbleGroups.Style style) {
        if (!running) {
            return;
        }
        remove(speaker.getUniqueId());
        track(speaker);
        int entityId = nextEntityId();
        UUID entityUuid = UUID.nameUUIDFromBytes(
                ("atrium-chatbubble:" + entityId).getBytes(StandardCharsets.UTF_8));
        double translationY = BubbleAnchor.translationY(speaker.getEyeHeight(), cfg.effectiveHeightAboveEyes());
        long duration = style.showSeconds();
        Bubble bubble = new Bubble(speaker.getUniqueId(), speaker.getEntityId(), entityId, entityUuid,
                metadata(text, cfg, translationY, style.scale()), System.currentTimeMillis() + duration * 1000L);
        bubbles.put(bubble.speaker, bubble);
        bySpeakerEntity.put(bubble.speakerEntityId, bubble);
        refreshViewers(speaker, bubble, cfg);
        try {
            bubble.task = ctx.track(ctx.scheduler().entityTimer(speaker,
                    () -> tick(speaker, bubble), null, REFRESH_TICKS, REFRESH_TICKS));
        } catch (RuntimeException error) {
            ctx.logger().warn("Could not schedule the expiry of a chat bubble for {}", speaker.getName(), error);
            remove(speaker.getUniqueId());
        }
    }

    private static int nextEntityId() {
        int id = NEXT_ENTITY_ID.getAndDecrement();
        if (id == Integer.MIN_VALUE) {
            NEXT_ENTITY_ID.set(FIRST_ENTITY_ID);
            return NEXT_ENTITY_ID.getAndDecrement();
        }
        return id;
    }

    private List<EntityData<?>> metadata(Component text, ChatBubblesConfig cfg, double translationY, float scale) {
        int flags = 0;
        if (cfg.shadowed) {
            flags |= FLAG_SHADOW;
        }
        if (cfg.seeThrough) {
            flags |= FLAG_SEE_THROUGH;
        }
        float viewRange = (float) Math.clamp(cfg.viewRange / 64d, 0.1d, 4d);
        return List.of(
                new EntityData<>(metaIds.translation(), EntityDataTypes.VECTOR3F,
                        new Vector3f(0f, (float) translationY, 0f)),
                new EntityData<>(metaIds.scale(), EntityDataTypes.VECTOR3F,
                        new Vector3f(scale, scale, scale)),
                new EntityData<>(metaIds.billboard(), EntityDataTypes.BYTE, (byte) BILLBOARD_CENTER),
                new EntityData<>(metaIds.viewRange(), EntityDataTypes.FLOAT, viewRange),
                new EntityData<>(metaIds.text(), EntityDataTypes.ADV_COMPONENT, text),
                new EntityData<>(metaIds.lineWidth(), EntityDataTypes.INT,
                        Math.clamp(cfg.lineWidth, 1, 1000)),
                new EntityData<>(metaIds.background(), EntityDataTypes.INT,
                        BubbleColors.backgroundArgb(cfg.backgroundColor)),
                new EntityData<>(metaIds.opacity(), EntityDataTypes.BYTE, OPACITY_OPAQUE),
                new EntityData<>(metaIds.styleFlags(), EntityDataTypes.BYTE, (byte) flags));
    }

    /** Speaker thread. */
    private void tick(Player speaker, Bubble bubble) {
        if (!running || bubbles.get(bubble.speaker) != bubble || !speaker.isOnline() || speaker.isDead()
                || System.currentTimeMillis() >= bubble.deadline) {
            remove(bubble.speaker);
            return;
        }
        refreshViewers(speaker, bubble, config.get());
    }

    /** Speaker thread; all player access here is the speaker's, viewer positions come from their snapshots. */
    private void refreshViewers(Player speaker, Bubble bubble, ChatBubblesConfig cfg) {
        if (!running || bubbles.get(bubble.speaker) != bubble) {
            return;
        }
        Location origin = speaker.getLocation();
        UUID speakerWorld = speaker.getWorld().getUID();
        UUID speakerId = speaker.getUniqueId();
        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        IgnoreService ignore = ctx.services().get(IgnoreService.class).orElse(null);
        Set<UUID> desired = new HashSet<>();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            BubbleReceivers.Viewer snapshot = viewers.get(viewer.getUniqueId());
            if (snapshot == null) {
                // A player who joined too recently to be seeded is picked up by the next refresh.
                continue;
            }
            boolean visible = vanish == null || vanish.canSee(viewer, speaker);
            boolean ignoring = ignore != null && ignore.isIgnoring(viewer.getUniqueId(), speakerId);
            if (BubbleReceivers.accepts(snapshot, speakerId, speakerWorld,
                    origin.getX(), origin.getY(), origin.getZ(), cfg.viewRange, visible, ignoring,
                    cfg.hideFromSender)) {
                desired.add(viewer.getUniqueId());
            }
        }
        for (UUID viewerId : new ArrayList<>(bubble.receivers)) {
            if (!desired.contains(viewerId)) {
                destroy(viewerId, bubble);
                bubble.receivers.remove(viewerId);
            }
        }
        for (UUID viewerId : desired) {
            if (!running || bubbles.get(bubble.speaker) != bubble) {
                return;
            }
            if (!bubble.receivers.add(viewerId)) {
                continue;
            }
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer == null || !viewer.isOnline()) {
                bubble.receivers.remove(viewerId);
                continue;
            }
            try {
                spawnFor(viewer, bubble, speaker);
            } catch (RuntimeException error) {
                bubble.receivers.remove(viewerId);
                if (bubble.reported.add(viewerId)) {
                    ctx.logger().warn("Could not show a chat bubble of {} to {}", speaker.getName(),
                            viewer.getName(), error);
                }
            }
        }
    }

    private void spawnFor(Player viewer, Bubble bubble, Player speaker) {
        Location location = speaker.getLocation();
        players.sendPacket(viewer, new WrapperPlayServerSpawnEntity(bubble.entityId, bubble.entityUuid,
                EntityTypes.TEXT_DISPLAY,
                new com.github.retrooper.packetevents.protocol.world.Location(
                        location.getX(), location.getY(), location.getZ(), 0f, 0f),
                0f, 0, Vector3d.zero()));
        players.sendPacket(viewer, new WrapperPlayServerEntityMetadata(bubble.entityId, bubble.metadata));
        mount(viewer, bubble, speaker);
    }

    /** Includes the speaker's real passengers so the client never drops them from view. */
    private void mount(Player viewer, Bubble bubble, Player speaker) {
        List<Entity> passengers = speaker.getPassengers();
        int[] real = new int[passengers.size()];
        for (int i = 0; i < passengers.size(); i++) {
            real[i] = passengers.get(i).getEntityId();
        }
        players.sendPacket(viewer, new WrapperPlayServerSetPassengers(speaker.getEntityId(),
                BubblePassengers.compose(real, bubble.entityId)));
    }

    private void destroy(UUID viewerId, Bubble bubble) {
        Player viewer = Bukkit.getPlayer(viewerId);
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        try {
            players.sendPacket(viewer, new WrapperPlayServerDestroyEntities(bubble.entityId));
        } catch (RuntimeException error) {
            // The channel can close between the online check and the send; nothing to repair client-side.
            ctx.logger().debug("Could not remove a chat bubble from {}'s client", viewer.getName(), error);
        }
    }

    @Override
    public void remove(UUID speaker) {
        Bubble bubble = bubbles.remove(speaker);
        if (bubble == null) {
            return;
        }
        bySpeakerEntity.remove(bubble.speakerEntityId, bubble);
        bubble.task.cancel();
        for (UUID viewerId : List.copyOf(bubble.receivers)) {
            destroy(viewerId, bubble);
        }
        bubble.receivers.clear();
    }

    @Override
    public void shutdown() {
        running = false;
        PacketListenerCommon listener = mountListener;
        mountListener = null;
        if (listener != null) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(listener);
            } catch (RuntimeException error) {
                ctx.logger().warn("Could not unregister the chat bubble passenger listener", error);
            }
        }
        for (UUID speaker : List.copyOf(bubbles.keySet())) {
            remove(speaker);
        }
        bubbles.clear();
        bySpeakerEntity.clear();
        viewers.clear();
    }

    /**
     * Intercepts the server's own SET_PASSENGERS for a speaker and appends the fake bubble id, so a
     * passenger change by another plugin does not unmount the bubble for the viewers that have it.
     */
    private static final class MountListener extends PacketListenerAbstract {
        private final PacketBubbleRenderer renderer;

        private MountListener(PacketBubbleRenderer renderer) {
            super(PacketListenerPriority.HIGH);
            this.renderer = renderer;
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            if (!renderer.running || event.getPacketType() != PacketType.Play.Server.SET_PASSENGERS) {
                return;
            }
            if (event.getUser() == null) {
                return;
            }
            UUID viewerId = event.getUser().getUUID();
            if (viewerId == null) {
                return;
            }
            WrapperPlayServerSetPassengers wrapper = new WrapperPlayServerSetPassengers(event);
            Bubble bubble = renderer.bySpeakerEntity.get(wrapper.getEntityId());
            if (bubble == null || !bubble.receivers.contains(viewerId)) {
                return;
            }
            int[] passengers = wrapper.getPassengers();
            if (BubblePassengers.contains(passengers, bubble.entityId)) {
                return;
            }
            wrapper.setPassengers(BubblePassengers.compose(passengers, bubble.entityId));
            event.markForReEncode(true);
        }
    }

    private static final class Bubble {
        private final UUID speaker;
        private final int speakerEntityId;
        private final int entityId;
        private final UUID entityUuid;
        private final List<EntityData<?>> metadata;
        private final long deadline;
        private final Set<UUID> receivers = ConcurrentHashMap.newKeySet();
        /** Viewers whose spawn/send already failed once; suppresses repeated warnings. */
        private final Set<UUID> reported = ConcurrentHashMap.newKeySet();
        private volatile TaskHandle task = TaskHandle.NOOP;

        private Bubble(UUID speaker, int speakerEntityId, int entityId, UUID entityUuid,
                List<EntityData<?>> metadata, long deadline) {
            this.speaker = speaker;
            this.speakerEntityId = speakerEntityId;
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.metadata = metadata;
            this.deadline = deadline;
        }
    }
}
