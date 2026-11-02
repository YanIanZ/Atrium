package dev.yanianz.atrium.module.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.meta.MetaService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MetaCacheTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void valuesAreCachedPerPlayerAndInvalidated() {
        FakeProvider provider = new FakeProvider();
        CachedMetaService service = new CachedMetaService(provider, LoggerFactory.getLogger("test"));

        assertEquals("A", service.meta(PLAYER).prefix());
        assertEquals("A", service.meta(PLAYER).prefix());
        assertEquals(1, provider.loads.get(), "the second read must come from the cache");

        service.invalidate(PLAYER);
        assertEquals("A", service.meta(PLAYER).prefix());
        assertEquals(2, provider.loads.get(), "an invalidation must force a fresh load");
    }

    @Test
    void refreshReplacesTheSnapshot() {
        FakeProvider provider = new FakeProvider();
        CachedMetaService service = new CachedMetaService(provider, LoggerFactory.getLogger("test"));

        assertEquals("A", service.meta(PLAYER).prefix());
        provider.prefix = "B";
        service.refresh(PLAYER);
        assertEquals("B", service.meta(PLAYER).prefix());
    }

    @Test
    void anOffThreadEmptyAnswerIsNotCached() {
        FakeProvider provider = new FakeProvider();
        provider.available = false;
        CachedMetaService service = new CachedMetaService(provider, LoggerFactory.getLogger("test"));

        assertTrue(service.meta(PLAYER).prefix().isEmpty());
        assertTrue(service.meta(PLAYER).prefix().isEmpty());
        assertEquals(2, provider.loads.get(), "a null (off-thread) answer must never be cached");

        provider.available = true;
        service.refresh(PLAYER);
        assertEquals("A", service.meta(PLAYER).prefix(), "the next read on the right thread fills the cache");
        assertEquals(3, provider.loads.get());

        provider.available = false;
        service.refresh(PLAYER);
        assertEquals("A", service.meta(PLAYER).prefix(), "a null refresh keeps the previous snapshot");
    }

    @Test
    void prefixSuffixAndMetaValuesAreAvailable() {
        FakeProvider provider = new FakeProvider();
        CachedMetaService service = new CachedMetaService(provider, LoggerFactory.getLogger("test"));

        assertEquals("A", service.value(PLAYER, "prefix").orElseThrow());
        assertEquals("S", service.value(PLAYER, "suffix").orElseThrow());
        assertEquals("group", service.value(PLAYER, "primary_group").orElseThrow());
        assertTrue(service.value(PLAYER, "missing").isEmpty());
        assertTrue(service.value(null, "prefix").isEmpty());
    }

    private static final class FakeProvider implements MetaProvider {
        final AtomicInteger loads = new AtomicInteger();
        String prefix = "A";
        /** {@code false} mimics Vault off its own region thread: load() returns null. */
        boolean available = true;

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public MetaSnapshot load(UUID player) {
            loads.incrementAndGet();
            if (!available) {
                return null;
            }
            return new MetaSnapshot(new MetaService.Meta(prefix, "S", "group"), Map.of("key", "value"));
        }

        @Override
        public Optional<String> value(UUID player, String key) {
            return Optional.empty();
        }
    }
}
