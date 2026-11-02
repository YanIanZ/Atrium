package dev.yanianz.atrium.module.chat;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.chat.SignedMessage;
import org.jspecify.annotations.Nullable;

/**
 * Bounded recent-message history. The delete button uses it to find the signed message (and, when the
 * server cannot delete a signed message, to remove only Atrium's own record).
 */
final class ChatHistory {
    record Entry(UUID id, UUID sender, String senderName, String plain, @Nullable SignedMessage signed, long at) {}

    private final int capacity;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    ChatHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized Entry add(UUID sender, String senderName, String plain, @Nullable SignedMessage signed, long now) {
        Entry entry = new Entry(UUID.randomUUID(), sender, senderName == null ? "?" : senderName,
                plain == null ? "" : plain, signed, now);
        entries.put(entry.id(), entry);
        Iterator<UUID> iterator = entries.keySet().iterator();
        while (entries.size() > capacity && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
        return entry;
    }

    synchronized Optional<Entry> get(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    synchronized Optional<Entry> remove(UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.remove(id));
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized void clear() {
        entries.clear();
    }
}
