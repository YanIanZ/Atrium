package dev.yanianz.atrium.module.scoreboard;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.score.ScoreFormat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisplayScoreboard;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.ObjectiveMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective.RenderType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore.Action;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.slf4j.Logger;

/**
 * Sidebar through PacketEvents, for servers without a scoreboard API (Folia). The objective and its
 * scores exist only on the client: no Bukkit scoreboard is created or replaced.
 *
 * <p>Each line is a score holder with a custom display name (1.20.3+), so lines may be longer than 16
 * characters and duplicate lines stay distinct. The number format is blank on the objective and on
 * every score. Updates send only {@link LineDiff} operations, all from the player's thread.
 *
 * <p>This class is only loaded when the packetevents plugin is enabled; see
 * {@link PacketBackendFactory}.
 */
final class PacketSidebarBackend implements SidebarBackend {
    static final String OBJECTIVE = "atrium_sidebar";
    /** Vanilla display slot id of the sidebar. */
    private static final int SIDEBAR_SLOT = 1;

    private final Logger logger;
    /** On Folia there is no scoreboard manager to consult for a foreign scoreboard. */
    private final boolean folia;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** Players whose client holds our objective; a hide clears it even when the state was dropped. */
    private final Set<UUID> created = ConcurrentHashMap.newKeySet();
    private final Set<UUID> reported = ConcurrentHashMap.newKeySet();

    private PacketSidebarBackend(Logger logger, boolean folia) {
        this.logger = logger;
        this.folia = folia;
    }

    /** Called only when packetevents is enabled; returns {@code null} when this server cannot be served. */
    static SidebarBackend create(Logger logger, boolean folia) {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) {
            logger.warn("PacketEvents is enabled but its API is not ready; the packet sidebar backend is unavailable");
            return null;
        }
        ServerVersion version = api.getServerManager().getVersion();
        if (!version.isNewerThanOrEquals(ServerVersion.V_1_20_3)) {
            logger.warn("Packet sidebars need Minecraft 1.20.3 or newer (this server is {}); "
                    + "update the server or use backend: bukkit", version);
            return null;
        }
        return new PacketSidebarBackend(logger, folia);
    }

    @Override
    public Kind kind() {
        return Kind.PACKETS;
    }

    @Override
    public boolean canRender(Player player) {
        if (folia) {
            return true;
        }
        try {
            // Same rule as the Bukkit backend: never cover a private scoreboard given by another plugin.
            return player.getScoreboard() == Bukkit.getScoreboardManager().getMainScoreboard();
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    @Override
    public int update(Player player, Component title, List<Component> lines) {
        UUID id = player.getUniqueId();
        try {
            PlayerManager players = PacketEvents.getAPI().getPlayerManager();
            State state = states.get(id);
            if (state == null) {
                states.put(id, new State(title, List.copyOf(lines)));
                // Clear a leftover objective before creating ours. A previous session, a failed send or a
                // reload can leave one behind on the client, and a duplicate CREATE is a protocol error.
                players.sendPacket(player, objective(Component.empty(), ObjectiveMode.REMOVE));
                players.sendPacket(player, objective(title, ObjectiveMode.CREATE));
                created.add(id);
                players.sendPacket(player, new WrapperPlayServerDisplayScoreboard(SIDEBAR_SLOT, OBJECTIVE));
                for (int i = 0; i < lines.size(); i++) {
                    players.sendPacket(player, score(i, lines.get(i), lines.size() - i));
                }
                reported.remove(id);
                return lines.size() + 1;
            }
            int changed = 0;
            if (!title.equals(state.title)) {
                players.sendPacket(player, objective(title, ObjectiveMode.UPDATE));
                state.title = title;
                changed++;
            }
            for (LineDiff.Change change : LineDiff.diff(state.lines, lines)) {
                if (change.kind() == LineDiff.Kind.REMOVE) {
                    // 1.20.3+ has no remove action on the score packet; scores are cleared with RESET_SCORE.
                    players.sendPacket(player,
                            new WrapperPlayServerResetScore(LineDiff.entry(change.index()), OBJECTIVE));
                } else {
                    players.sendPacket(player, score(change.index(), lines.get(change.index()), change.score()));
                }
                changed++;
            }
            state.lines = List.copyOf(lines);
            reported.remove(id);
            return changed;
        } catch (RuntimeException e) {
            // Drop the state so the next cycle builds the objective again from scratch instead of guessing
            // what the client received.
            states.remove(id);
            if (reported.add(id)) {
                logger.warn("Sending the sidebar of {} through PacketEvents failed; it will be retried",
                        player.getName(), e);
            }
            return 0;
        }
    }

    @Override
    public void hide(Player player) {
        UUID id = player.getUniqueId();
        states.remove(id);
        // The state can be gone while the client still holds the objective (a send failed after CREATE,
        // or the state was dropped). Track what the client was actually told, not what we remember.
        if (!SidebarVisibility.needsRemoval(created.remove(id), player.isOnline())) {
            return;
        }
        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, objective(Component.empty(),
                    ObjectiveMode.REMOVE));
        } catch (RuntimeException e) {
            logger.warn("Removing the sidebar of {} through PacketEvents failed", player.getName(), e);
        }
    }

    @Override
    public void forget(Player player) {
        states.remove(player.getUniqueId());
        created.remove(player.getUniqueId());
        reported.remove(player.getUniqueId());
    }

    private static WrapperPlayServerScoreboardObjective objective(Component title, ObjectiveMode mode) {
        return new WrapperPlayServerScoreboardObjective(OBJECTIVE, mode, title, RenderType.INTEGER,
                ScoreFormat.blankScore());
    }

    private static WrapperPlayServerUpdateScore score(int index, Component text, int value) {
        return new WrapperPlayServerUpdateScore(LineDiff.entry(index), Action.CREATE_OR_UPDATE_ITEM, OBJECTIVE,
                value, text, ScoreFormat.blankScore());
    }

    /** Client-side state of one player; touched only on that player's thread. */
    private static final class State {
        private Component title;
        private List<Component> lines;

        private State(Component title, List<Component> lines) {
            this.title = title;
            this.lines = lines;
        }
    }
}
