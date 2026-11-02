package dev.yanianz.atrium.module.mail;

import dev.yanianz.atrium.api.user.PlayerRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step confirmation for {@code /mailall}: the first invocation fixes the message and the exact recipient
 * list, the second one sends it. Keeping the list means the blast goes to the players the operator was shown,
 * even if someone joins in between. Pure logic with an injected clock so it can be tested without a server.
 */
final class MailAllConfirmation {
    /** An armed broadcast. */
    record Pending(String message, List<PlayerRef> recipients, long expires) {
        Pending {
            recipients = List.copyOf(recipients);
        }

        boolean valid(long now) {
            return now < expires;
        }
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** Fixes the message and recipients; a repeated invocation replaces the previous pending blast. */
    void arm(String key, String message, List<PlayerRef> recipients, long now, long windowMillis) {
        long window = Math.max(1, windowMillis);
        pending.put(key, new Pending(message, recipients, now + window));
    }

    /** Reads and removes a pending blast; empty when nothing was armed or the window passed. */
    Optional<Pending> take(String key, long now) {
        Pending found = pending.remove(key);
        if (found == null || !found.valid(now)) {
            return Optional.empty();
        }
        return Optional.of(found);
    }

    void cancel(String key) {
        pending.remove(key);
    }

    /** Drops the pending blast of a player who left; the next login has to start again. */
    void forget(String key) {
        pending.remove(key);
    }

    int size() {
        return pending.size();
    }

    void clear() {
        pending.clear();
    }
}
