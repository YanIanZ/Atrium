package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Cross-server chat transport on top of the optional {@link ProxyService}.
 *
 * <p>Only plain data crosses the network: the payload codec in {@link CrossServerChat} validates every
 * field again on receipt. Subscriptions run on an async thread (the proxy module's contract), so the
 * callbacks in {@link ChatModule} use only thread-safe reads and never touch a world.
 */
final class ChatRelay {
    static final String TOPIC_GLOBAL = "chat:global";
    static final String TOPIC_STAFF = "chat:staff";
    static final String TOPIC_PRIVATE = "chat:msg";

    private static final long REJECT_LOG_MILLIS = 5000L;

    private final ModuleContext ctx;
    private final ProxyService proxy;
    private final ChatModule module;
    private final List<ProxyService.Subscription> subscriptions = new ArrayList<>();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong lastRejectLog = new AtomicLong();
    private volatile boolean closed;

    ChatRelay(ModuleContext ctx, ProxyService proxy, ChatModule module) {
        this.ctx = ctx;
        this.proxy = proxy;
        this.module = module;
    }

    void start() {
        subscribe(TOPIC_GLOBAL, message -> module.onRemoteChat(message, CrossServerChat.Kind.GLOBAL));
        subscribe(TOPIC_STAFF, message -> module.onRemoteChat(message, CrossServerChat.Kind.STAFF));
        subscribe(TOPIC_PRIVATE, message -> module.onRemoteChat(message, CrossServerChat.Kind.PRIVATE));
    }

    private void subscribe(String topic, Consumer<ProxyMessage> consumer) {
        try {
            subscriptions.add(proxy.subscribe(topic, consumer));
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot subscribe to cross-server chat topic {}", topic, e);
        }
    }

    /** {@code true} when the proxy answered with a real server name; otherwise nothing is published. */
    boolean available() {
        if (closed) {
            return false;
        }
        String name = proxy.serverName();
        return name != null && !name.isBlank() && !"unknown".equalsIgnoreCase(name);
    }

    boolean publishGlobal(UUID sender, String senderName, String text) {
        return publish(new CrossServerChat.Payload(CrossServerChat.Kind.GLOBAL, sender, senderName, "",
                CrossServerChat.outgoingText(text)));
    }

    boolean publishStaff(UUID sender, String senderName, String text) {
        return publish(new CrossServerChat.Payload(CrossServerChat.Kind.STAFF, sender, senderName, "",
                CrossServerChat.outgoingText(text)));
    }

    boolean publishPrivate(UUID sender, String senderName, String targetName, String text) {
        return publish(new CrossServerChat.Payload(CrossServerChat.Kind.PRIVATE, sender, senderName, targetName,
                CrossServerChat.outgoingText(text)));
    }

    private boolean publish(CrossServerChat.Payload payload) {
        if (!available()) {
            return false;
        }
        if (payload.text().isBlank()) {
            return false;
        }
        try {
            byte[] data = CrossServerChat.encode(payload);
            proxy.publish(topic(payload.kind()), data);
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            ctx.logger().warn("Cannot publish a cross-server {} message: {}", payload.kind(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            ctx.logger().warn("Proxy rejected a cross-server {} message ({})", payload.kind(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** Decodes and validates one received payload; invalid data is counted and logged at most every 5 s. */
    CrossServerChat.Payload decode(ProxyMessage message, CrossServerChat.Kind expected) {
        if (message == null || expected == null) {
            return null;
        }
        try {
            CrossServerChat.Payload payload = CrossServerChat.decode(message.payload());
            if (payload.kind() != expected) {
                reject("payload kind " + payload.kind() + " arrived on the " + expected + " topic", null);
                return null;
            }
            return payload;
        } catch (CrossServerChat.InvalidPayloadException e) {
            reject("invalid " + expected + " payload (" + e.reason() + "): " + e.getMessage(), null);
            return null;
        } catch (RuntimeException e) {
            reject("cannot decode " + expected + " payload", e);
            return null;
        }
    }

    private void reject(String reason, RuntimeException error) {
        rejected.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastRejectLog.get();
        if (now - last < REJECT_LOG_MILLIS || !lastRejectLog.compareAndSet(last, now)) {
            return;
        }
        if (error == null) {
            ctx.logger().warn("Rejected cross-server chat message from {}: {}", proxy.serverName(), reason);
        } else {
            ctx.logger().warn("Rejected cross-server chat message ({})", reason, error);
        }
    }

    long rejected() {
        return rejected.get();
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (ProxyService.Subscription subscription : subscriptions) {
            try {
                subscription.close();
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot close a cross-server chat subscription", e);
            }
        }
        subscriptions.clear();
    }

    private static String topic(CrossServerChat.Kind kind) {
        return switch (kind) {
            case GLOBAL -> TOPIC_GLOBAL;
            case STAFF -> TOPIC_STAFF;
            case PRIVATE -> TOPIC_PRIVATE;
        };
    }
}
