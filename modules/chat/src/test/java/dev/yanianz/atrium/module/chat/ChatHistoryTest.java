package dev.yanianz.atrium.module.chat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatHistoryTest {
    @Test
    void resizingOnReloadKeepsMessageIdsAndTrimsOnlyOldestEntries() {
        ChatHistory history = new ChatHistory(3);
        ChatHistory.Entry oldest = history.add(UUID.randomUUID(), "A", "one", null, 0);
        ChatHistory.Entry middle = history.add(UUID.randomUUID(), "B", "two", null, 1);
        ChatHistory.Entry newest = history.add(UUID.randomUUID(), "C", "three", null, 2);
        history.resize(2);
        assertTrue(history.get(oldest.id()).isEmpty());
        assertEquals(middle, history.get(middle.id()).orElseThrow());
        assertEquals(newest, history.get(newest.id()).orElseThrow());
        history.resize(5);
        history.add(UUID.randomUUID(), "D", "four", null, 3);
        assertEquals(3, history.size());
        assertEquals(middle, history.remove(middle.id()).orElseThrow());
    }

    @Test
    void keepsOnlyTheNewestEntries() {
        ChatHistory history = new ChatHistory(2);
        ChatHistory.Entry first = history.add(UUID.randomUUID(), "Alice", "one", null, 0);
        ChatHistory.Entry second = history.add(UUID.randomUUID(), "Bob", "two", null, 1);
        ChatHistory.Entry third = history.add(UUID.randomUUID(), "Carol", "three", null, 2);

        assertTrue(history.get(first.id()).isEmpty());
        assertEquals("two", history.get(second.id()).orElseThrow().plain());
        assertEquals("three", history.get(third.id()).orElseThrow().plain());
        assertEquals(2, history.size());
    }

    @Test
    void removeReturnsTheEntryOnce() {
        ChatHistory history = new ChatHistory(5);
        ChatHistory.Entry entry = history.add(UUID.randomUUID(), "Alice", "hello", null, 0);

        assertEquals(entry, history.remove(entry.id()).orElseThrow());
        assertTrue(history.remove(entry.id()).isEmpty());
        assertTrue(history.get(entry.id()).isEmpty());
    }

    @Test
    void unknownIdsAreEmpty() {
        ChatHistory history = new ChatHistory(1);
        assertTrue(history.get(UUID.randomUUID()).isEmpty());
        assertTrue(history.get(null).isEmpty());
        assertTrue(history.remove(null).isEmpty());
    }
}
