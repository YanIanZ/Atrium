package dev.yanianz.atrium.module.chat;

import dev.yanianz.atrium.api.chat.IgnoreService;
import java.util.Set;
import java.util.UUID;

/**
 * {@link IgnoreService} over the chat module's {@code chat:user} component.
 *
 * <p>The source is a plain function so the lookup itself can be tested without a server. Every call reads
 * the player's current immutable component value, which is safe from any thread; a player whose data is not
 * loaded yet is treated as ignoring nobody.
 */
final class ChatIgnoreService implements IgnoreService {
    @FunctionalInterface
    interface IgnoreSource {
        /** Current ignore set of one player; {@code null} means unknown. */
        Set<UUID> ignored(UUID who);
    }

    private final IgnoreSource source;

    ChatIgnoreService(IgnoreSource source) {
        this.source = source;
    }

    @Override
    public boolean isIgnoring(UUID who, UUID ignored) {
        if (who == null || ignored == null || who.equals(ignored)) {
            return false;
        }
        Set<UUID> current = source.ignored(who);
        return current != null && current.contains(ignored);
    }

    @Override
    public Set<UUID> ignored(UUID who) {
        if (who == null) {
            return Set.of();
        }
        Set<UUID> current = source.ignored(who);
        return current == null || current.isEmpty() ? Set.of() : Set.copyOf(current);
    }
}
