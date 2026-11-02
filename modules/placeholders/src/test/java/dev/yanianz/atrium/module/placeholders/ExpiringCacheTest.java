package dev.yanianz.atrium.module.placeholders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExpiringCacheTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void servesValuesUntilTheyExpire() {
        ExpiringCache<String> cache = new ExpiringCache<>();
        cache.put(PLAYER, "value", 2_000L);

        assertEquals("value", cache.get(PLAYER, 1_000L));
        assertEquals("value", cache.get(PLAYER, 1_999L));
        assertNull(cache.get(PLAYER, 2_000L));
        assertEquals(0, cache.size(), "an expired entry is removed on read");
    }

    @Test
    void aMissingPlayerHasNoValue() {
        ExpiringCache<String> cache = new ExpiringCache<>();
        assertNull(cache.get(PLAYER, 0L));
    }

    @Test
    void laterWritesReplaceTheValueAndDeadline() {
        ExpiringCache<String> cache = new ExpiringCache<>();
        cache.put(PLAYER, "old", 1_000L);
        cache.put(PLAYER, "new", 5_000L);

        assertEquals("new", cache.get(PLAYER, 2_000L));
        assertEquals(1, cache.size());
    }

    @Test
    void removeAndClearDropEntries() {
        ExpiringCache<String> cache = new ExpiringCache<>();
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        cache.put(PLAYER, "a", 10_000L);
        cache.put(other, "b", 10_000L);

        cache.remove(PLAYER);
        assertNull(cache.get(PLAYER, 0L));
        assertEquals("b", cache.get(other, 0L));

        cache.clear();
        assertEquals(0, cache.size());
    }
}
