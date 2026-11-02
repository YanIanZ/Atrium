package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class NpcStoreTest {
    @TempDir
    Path directory;

    private static NpcDef sample(String name) {
        return new NpcDef(0, name, "<gold>" + name, "world", 1.5, 64, -3.25, 90f, 12f,
                "Notch", Map.of("HAND", "AAAA", "HEAD", "BBBB"), "sneaking", true, 12.5, "red", 1.25, true,
                List.of("console: say left"), List.of("warp spawn", "msg: <green>hi"), "atrium.npc.vip", 30_000,
                new BigDecimal("12.50"), NpcType.CITIZENS);
    }

    @Test
    void roundTripsEverythingAndUpdatesInPlace() throws Exception {
        try (var db = new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()))) {
            db.migrate("npcs", NpcStore.MIGRATIONS);
            var store = new NpcStore(db, message -> System.err.println(message));
            NpcDef saved = store.save(sample("Steve")).join();
            assertTrue(saved.id() > 0);
            assertEquals(List.of(saved), store.loadAll().join());

            NpcDef changed = saved.withDisplayName("<red>Changed").withLook(false, 3).withCost(new BigDecimal("0"));
            store.save(changed).join();
            assertEquals(List.of(changed), store.loadAll().join());

            assertEquals(1, store.loadAll().join().size());
            assertTrue(store.delete("steve").join());
            assertFalse(store.delete("steve").join());
            assertTrue(store.loadAll().join().isEmpty());
        }
    }

    @Test
    void engineMigrationUpdatesAutoRows() throws Exception {
        try (var db = new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()))) {
            db.migrate("npcs", NpcStore.MIGRATIONS);
            var store = new NpcStore(db, message -> System.err.println(message));
            store.save(sample("A").withEngine(NpcType.NATIVE)).join();
            store.save(sample("B").withEngine(NpcType.AUTO)).join();
            store.save(sample("C").withEngine(NpcType.CITIZENS)).join();

            store.migrateEngine(NpcType.NATIVE, NpcType.CITIZENS, NpcType.NATIVE).join();
            Map<String, NpcType> types = new java.util.HashMap<>();
            for (NpcDef def : store.loadAll().join()) {
                types.put(def.name(), def.engine());
            }
            assertEquals(NpcType.CITIZENS, types.get("A"));
            assertEquals(NpcType.CITIZENS, types.get("B"));
            assertEquals(NpcType.CITIZENS, types.get("C"));
        }
    }

    @Test
    void skinCacheRoundTripsAndKeepsStaleRowsUsable() throws Exception {
        try (var db = new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()))) {
            db.migrate("npcs", NpcStore.MIGRATIONS);
            var store = new NpcStore(db, message -> System.err.println(message));
            UUID id = UUID.randomUUID();
            var row = new NpcStore.CachedSkin("Notch", id, "value", "signature", 42L);
            store.putCachedSkin(row).join();
            assertEquals(java.util.Optional.of(row), store.cachedSkin("notch").join());

            var withoutId = new NpcStore.CachedSkin("Alex", null, null, null, 7L);
            store.putCachedSkin(withoutId).join();
            assertEquals(java.util.Optional.of(withoutId), store.cachedSkin("ALEX").join());
        }
    }
}
