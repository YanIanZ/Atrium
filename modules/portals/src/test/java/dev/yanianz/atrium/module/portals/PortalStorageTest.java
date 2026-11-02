package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.api.portals.PortalUseEvent.TargetType;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PortalStorageTest {
    @TempDir Path directory;

    @Test void registryPersistsCreateUpdateDeleteAndRejectsOverlapAndDuplicates() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("portals", PortalStorage.MIGRATIONS);
            var storage = new PortalStorage(database, LoggerFactory.getLogger(getClass()));
            var registry = new PortalRegistry(storage);
            assertEquals(0, registry.load().join());
            Portal hub = new Portal("Hub", Cuboid.of("world", 0, 64, 0, 3, 67, 0), PortalTarget.NONE, null, null,
                    UUID.randomUUID(), 1L);
            assertEquals(PortalRegistry.Outcome.CREATED, registry.create(hub, false).join().outcome());
            assertEquals(PortalRegistry.Outcome.EXISTS, registry.create(hub, false).join().outcome());
            Portal overlapping = new Portal("other", Cuboid.of("world", 3, 60, 0, 9, 64, 9), PortalTarget.NONE, null, null, null, 2L);
            assertEquals(PortalRegistry.Outcome.OVERLAP, registry.create(overlapping, false).join().outcome());
            assertEquals(PortalRegistry.Outcome.CREATED, registry.create(overlapping, true).join().outcome());
            assertEquals(2, registry.index().inWorld("world").size());

            registry.update("hub", p -> p.withTarget(new PortalTarget(TargetType.WARP, "spawn"))).join();
            registry.update("HUB", p -> p.withPermission("x.y")).join();
            registry.update("hub", p -> p.withAction("msg: hi")).join();
            registry.update("hub", p -> p.withCost(12.5)).join();
            assertEquals(PortalRegistry.Outcome.NOT_FOUND, registry.update("nope", p -> p).join().outcome());

            var reloaded = new PortalRegistry(storage);
            assertEquals(2, reloaded.load().join());
            Portal back = reloaded.get("hub").orElseThrow();
            assertEquals(hub.area(), back.area());
            assertEquals(new PortalTarget(TargetType.WARP, "spawn"), back.target());
            assertEquals("x.y", back.permission());
            assertEquals("msg: hi", back.action());
            assertEquals(12.5, back.cost());
            assertEquals(hub.creator(), back.creator());
            assertEquals(1, reloaded.index().containing("world", 1, 65, 0.5).size());

            assertEquals(PortalRegistry.Outcome.DELETED, reloaded.delete("hub").join().outcome());
            assertEquals(PortalRegistry.Outcome.NOT_FOUND, reloaded.delete("hub").join().outcome());
            assertTrue(reloaded.index().containing("world", 1, 65, 0.5).isEmpty());
            assertEquals(1, new PortalRegistry(storage).load().join());
        }
    }
}
