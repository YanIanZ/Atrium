package dev.yanianz.atrium.api.chat;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Fired when a staff chat message is about to be delivered, including messages relayed from another
 * server behind a proxy.
 *
 * <p><b>Threading:</b> this event is always fired asynchronously ({@link #isAsynchronous()} is
 * {@code true}). Listeners must not touch blocks or entities; schedule work on the target player's own
 * thread with the scheduler. The event is safe to cancel from any thread.
 *
 * <p>Cancelling it stops local delivery, the cross-server relay and the Discord relay. The message
 * carried by the event is plain text: it was already filtered, emoji-expanded and stripped of colour
 * codes, so listeners never need to parse it as MiniMessage.
 */
public final class StaffChatEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable UUID senderId;
    private final String senderName;
    private final String message;
    private final String originServer;
    private final boolean remote;
    private boolean cancelled;

    /**
     * @param senderId     UUID of the sending player, or {@code null} for the console
     * @param senderName   plain display name used in Discord and chat templates
     * @param message      final plain message text
     * @param originServer server the message came from, empty for a local message
     * @param remote       {@code true} when the message was relayed from another server
     */
    public StaffChatEvent(@Nullable UUID senderId, String senderName, String message, @Nullable String originServer,
            boolean remote) {
        super(true);
        this.senderId = senderId;
        this.senderName = Objects.requireNonNull(senderName, "senderName");
        this.message = Objects.requireNonNull(message, "message");
        this.originServer = originServer == null ? "" : originServer;
        this.remote = remote;
    }

    /** UUID of the sender, or {@code null} when the console sent the message. */
    public @Nullable UUID senderId() {
        return senderId;
    }

    /** Plain name of the sender. */
    public String senderName() {
        return senderName;
    }

    /** Final plain text of the message. Never contains colour codes or MiniMessage tags. */
    public String message() {
        return message;
    }

    /** Authenticated server name reported by the proxy, or an empty string for a local message. */
    public String originServer() {
        return originServer;
    }

    /** {@code true} when the message arrived from another server and is not being re-sent. */
    public boolean remote() {
        return remote;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
