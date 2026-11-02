package dev.yanianz.atrium.module.skins;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class SkinsTest {
    @TempDir Path directory;

    static final String VALUE = Base64.getEncoder()
            .encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"x\"}}}".getBytes(StandardCharsets.UTF_8));

    private Database database() {
        return new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()));
    }

    @Test void acceptsOnlyAccountNames() {
        for (String ok : List.of("Notch", "jeb_", "abc", "A1234567890123_Z")) {
            assertTrue(SkinNames.valid(ok), ok);
        }
        for (String bad : List.of("ab", "seventeen_chars_x", "has space", "émile", "a-b", "../etc", "http://x",
                "%player%", "", "<red>x")) {
            assertFalse(SkinNames.valid(bad), bad);
        }
        assertFalse(SkinNames.valid(null));
        assertEquals("notch", SkinNames.key("NoTcH"));
    }

    @Test void cacheExpiry() {
        Duration ttl = Duration.ofHours(1);
        long now = 10_000_000L;
        assertTrue(SkinNames.fresh(now, now, ttl));
        assertTrue(SkinNames.fresh(now - ttl.toMillis() + 1, now, ttl));
        assertFalse(SkinNames.fresh(now - ttl.toMillis(), now, ttl), "expires exactly at the ttl");
        assertFalse(SkinNames.fresh(now - ttl.toMillis() * 5, now, ttl));
        assertFalse(SkinNames.fresh(0, now, ttl), "never fetched");
        assertFalse(SkinNames.fresh(now, now, Duration.ZERO));
        assertFalse(SkinNames.fresh(now, now, Duration.ofSeconds(-1)));
        assertTrue(SkinNames.fresh(now + 30_000, now, ttl), "small clock skew is tolerated");
        assertFalse(SkinNames.fresh(now + 3_600_000, now, ttl), "rows from the future are suspicious");
    }

    @Test void textureValuesMustBeBase64() {
        assertTrue(SkinTexture.wellFormed(VALUE));
        assertFalse(SkinTexture.wellFormed(null));
        assertFalse(SkinTexture.wellFormed(""));
        assertFalse(SkinTexture.wellFormed("not base64!"));
        assertFalse(SkinTexture.wellFormed("A".repeat(SkinTexture.MAX_VALUE_LENGTH + 4)));
        assertNull(new SkinTexture(VALUE, "").signature());
    }

    @Test void choiceWithoutTextureIsNone() {
        assertFalse(new SkinChoice("Notch", null, 5).custom());
        assertNull(new SkinChoice("Notch", null, 5).skinName());
        assertTrue(new SkinChoice("Notch", new SkinTexture(VALUE, "sig"), 5).custom());
    }

    @Test void cacheRoundTripIsCaseInsensitiveAndReplaces() throws Exception {
        try (Database database = database()) {
            database.migrate("skins", SkinStore.MIGRATIONS);
            SkinStore store = new SkinStore(database, message -> {});
            assertTrue(store.cached("Notch").join().isEmpty());

            store.putCache(new SkinStore.CacheEntry("Notch", new SkinTexture(VALUE, null), 1000L)).join();
            SkinStore.CacheEntry entry = store.cached("notch").join().orElseThrow();
            assertEquals("Notch", entry.name());
            assertEquals(VALUE, entry.texture().value());
            assertNull(entry.texture().signature());
            assertEquals(1000L, entry.fetchedAt());

            store.putCache(new SkinStore.CacheEntry("NOTCH", new SkinTexture(VALUE, "sig"), 2000L)).join();
            entry = store.cached("Notch").join().orElseThrow();
            assertEquals("sig", entry.texture().signature());
            assertEquals(2000L, entry.fetchedAt());

            assertEquals(1, store.purge(5000L).join());
            assertTrue(store.cached("Notch").join().isEmpty());
        }
    }

    @Test void malformedCacheRowIsSkippedWithAWarning() throws Exception {
        try (Database database = database()) {
            database.migrate("skins", SkinStore.MIGRATIONS);
            List<String> warnings = new ArrayList<>();
            SkinStore store = new SkinStore(database, warnings::add);
            database.update("INSERT INTO {prefix}skins_cache (name_lower, name, texture_value, texture_signature, "
                    + "fetched_at) VALUES (?, ?, ?, ?, ?)", "broken", "Broken", "%%%", "x", 1L).join();
            store.putCache(new SkinStore.CacheEntry("Fine", new SkinTexture(VALUE, "s"), 1L)).join();

            assertTrue(store.cached("broken").join().isEmpty());
            assertEquals(1, warnings.size());
            assertTrue(store.cached("fine").join().isPresent(), "other rows still load");
        }
    }

    @Test void choiceComponentRoundTripsAndClears() throws Exception {
        try (Database database = database()) {
            database.migrate("skins", SkinStore.MIGRATIONS);
            database.migrate("skins", SkinStore.MIGRATIONS);
            SkinStore store = new SkinStore(database, message -> {});
            UUID uuid = UUID.randomUUID();
            assertNull(store.component.loader().load(uuid).join());

            SkinChoice choice = new SkinChoice("Notch", new SkinTexture(VALUE, "sig"), 42L);
            store.component.saver().save(uuid, choice).join();
            assertEquals(choice, store.component.loader().load(uuid).join());

            store.component.saver().save(uuid, SkinChoice.NONE).join();
            assertNull(store.component.loader().load(uuid).join());
            assertEquals(SkinChoice.NONE, store.component.defaults().get());
        }
    }
}
