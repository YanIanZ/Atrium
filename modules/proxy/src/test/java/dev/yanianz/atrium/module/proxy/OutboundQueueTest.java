package dev.yanianz.atrium.module.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutboundQueueTest {
    private static final long NOW = 1_700_000_000_000L;

    @Test
    void queueDropsTheOldestWhenFull() {
        OutboundQueue queue = new OutboundQueue(3, 60_000);
        for (int i = 0; i < 5; i++) {
            queue.offer(new byte[] {(byte) i}, NOW + i);
        }

        assertEquals(3, queue.size(NOW + 10));
        assertEquals(2, queue.dropped());
        List<byte[]> drained = queue.drain(NOW + 10);
        // The two oldest messages were dropped; the newest three stay in order.
        assertEquals(3, drained.size());
        assertArrayEquals(new byte[] {2}, drained.get(0));
        assertArrayEquals(new byte[] {3}, drained.get(1));
        assertArrayEquals(new byte[] {4}, drained.get(2));
    }

    @Test
    void expiredMessagesAreNotDrained() {
        OutboundQueue queue = new OutboundQueue(4, 1_000);
        queue.offer(new byte[] {1}, NOW);
        queue.offer(new byte[] {2}, NOW + 500);

        assertEquals(1, queue.size(NOW + 1_000));
        assertTrue(queue.drain(NOW + 1_500).isEmpty());
        assertEquals(2, queue.expired());
    }

    @Test
    void drainRemovesEverything() {
        OutboundQueue queue = new OutboundQueue(4, 60_000);
        queue.offer(new byte[] {1}, NOW);
        assertEquals(1, queue.drain(NOW + 1).size());
        assertTrue(queue.drain(NOW + 1).isEmpty());
        assertEquals(0, queue.size(NOW + 1));
    }

    @Test
    void storedDataIsDefensivelyCopied() {
        OutboundQueue queue = new OutboundQueue(4, 60_000);
        byte[] data = {1, 2, 3};
        queue.offer(data, NOW);
        data[0] = 9;
        assertArrayEquals(new byte[] {1, 2, 3}, queue.drain(NOW + 1).getFirst());
    }
}
