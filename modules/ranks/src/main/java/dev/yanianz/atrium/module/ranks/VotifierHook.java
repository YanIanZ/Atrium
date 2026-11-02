package dev.yanianz.atrium.module.ranks;

import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * Optional Votifier/NuVotifier bridge.
 *
 * <p>This is the only class that knows the Votifier API, and it reaches it only through reflection. A server
 * without Votifier therefore never loads a foreign class, and a Votifier update that changes the event class
 * is reported as "hook unavailable" instead of breaking Atrium. Only the public vote event contract
 * ({@code getVote().getUsername()}) is used.
 */
final class VotifierHook {
    private static final String[] PLUGINS = {"NuVotifier", "Votifier"};
    private static final String EVENT_CLASS = "com.vexsoftware.votifier.model.VotifierEvent";

    private final Plugin plugin;
    private final Consumer<String> onVote;
    private final Listener listener = new Listener() {};
    private Class<? extends Event> eventType;
    private Method getVote;

    VotifierHook(Plugin plugin, Consumer<String> onVote) {
        this.plugin = plugin;
        this.onVote = onVote;
    }

    static boolean installed() {
        for (String name : PLUGINS) {
            if (Bukkit.getPluginManager().isPluginEnabled(name)) {
                return true;
            }
        }
        return false;
    }

    /** @return true when the vote event is registered */
    boolean register() {
        if (!installed()) {
            return false;
        }
        try {
            Class<?> raw = Class.forName(EVENT_CLASS, false, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> type = (Class<? extends Event>) raw;
            getVote = raw.getMethod("getVote");
            eventType = type;
            Bukkit.getPluginManager().registerEvent(type, listener, EventPriority.MONITOR,
                    (ignored, event) -> handle(event), plugin, true);
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().warning("Votifier is installed but its vote event is unavailable: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    private void handle(Event event) {
        if (eventType == null || !eventType.isInstance(event)) {
            return;
        }
        try {
            Object vote = getVote.invoke(event);
            if (vote == null) {
                return;
            }
            Object username = vote.getClass().getMethod("getUsername").invoke(vote);
            if (username instanceof String name && VoteMath.validName(name)) {
                onVote.accept(name.strip());
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("Could not read a Votifier vote: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
        }
    }

    void unregister() {
        if (eventType != null) {
            org.bukkit.event.HandlerList.unregisterAll(listener);
            eventType = null;
        }
    }
}
