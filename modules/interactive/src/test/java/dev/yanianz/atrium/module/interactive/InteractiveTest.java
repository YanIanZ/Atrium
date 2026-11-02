package dev.yanianz.atrium.module.interactive;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.module.interactive.InteractiveCommand.Click;
import dev.yanianz.atrium.module.interactive.InteractiveCommand.TargetKind;
import dev.yanianz.atrium.module.interactive.InteractiveCommand.Trigger;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class InteractiveTest {
    @TempDir Path directory;

    @Test void triggersMatchClicks() {
        assertTrue(Trigger.LEFT.matches(Click.LEFT));
        assertFalse(Trigger.LEFT.matches(Click.RIGHT));
        assertTrue(Trigger.BOTH.matches(Click.LEFT));
        assertTrue(Trigger.BOTH.matches(Click.RIGHT));
        assertFalse(Trigger.BOTH.matches(Click.PHYSICAL));
        assertTrue(Trigger.PHYSICAL.matches(Click.PHYSICAL));
        assertFalse(Trigger.RIGHT.matches(Click.PHYSICAL));
    }

    @Test void blockKeyRoundTripsWorldsWithSemicolons() {
        BlockKey key = new BlockKey("odd;world", -5, 64, 12);
        assertEquals(Optional.of(key), BlockKey.decode(key.encode()));
        assertEquals(Optional.empty(), BlockKey.decode("world;1;2"));
        assertEquals(Optional.empty(), BlockKey.decode("world;a;2;3"));
        assertEquals(Optional.empty(), BlockKey.decode(";1;2;3"));
    }

    @Test void commandEditsAreImmutable() {
        InteractiveCommand base = InteractiveCommand.create("Shop", true);
        InteractiveCommand edited = base.withAction("msg: a").withAction("msg: b").withoutAction(1)
                .withPermission("atrium.shop").withCooldown(Duration.ofSeconds(5)).withTrigger(Trigger.BOTH).withCancelEvent(false);
        assertEquals(List.of(), base.actions());
        assertEquals(List.of("msg: b"), edited.actions());
        assertEquals("shop", edited.key());
        assertEquals(Trigger.BOTH, edited.trigger());
        assertThrows(IndexOutOfBoundsException.class, () -> base.withoutAction(1));
        assertTrue(InteractiveCommand.validName("shop_1-a"));
        assertFalse(InteractiveCommand.validName("bad name"));
        assertFalse(InteractiveCommand.validName("x".repeat(33)));
        assertEquals(Duration.ZERO, new InteractiveCommand("a", null, Duration.ofSeconds(-1), true, Trigger.LEFT, List.of()).cooldown());
    }

    @Test void registryIndexesBindingsAndRemovesThemWithTheCommand() {
        InteractiveRegistry registry = new InteractiveRegistry();
        registry.put(InteractiveCommand.create("Shop", true));
        registry.put(InteractiveCommand.create("Npc", true));
        BlockKey block = new BlockKey("world", 1, 2, 3);
        UUID entity = UUID.randomUUID();
        registry.bind(TargetKind.BLOCK, block.encode(), "Shop");
        registry.bind(TargetKind.ENTITY, entity.toString(), "npc");
        assertEquals("Shop", registry.atBlock(block).orElseThrow().name());
        assertEquals("Npc", registry.forEntity(entity).orElseThrow().name());
        assertEquals(Optional.of("shop"), registry.boundTo(TargetKind.BLOCK, block.encode()));
        assertEquals(List.of(block), registry.blocksOf("SHOP"));
        assertEquals(List.of(entity), registry.entitiesOf("npc"));
        assertEquals(List.of("Npc", "Shop"), List.copyOf(registry.names()));
        registry.unbind(TargetKind.ENTITY, entity.toString());
        assertTrue(registry.forEntity(entity).isEmpty());
        assertTrue(registry.remove("shop"));
        assertTrue(registry.atBlock(block).isEmpty());
        assertTrue(registry.boundTo(TargetKind.BLOCK, block.encode()).isEmpty());
        assertFalse(registry.remove("shop"));
    }

    @Test void storeRoundTrip() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("interactive", InteractiveStore.MIGRATIONS);
            InteractiveStore store = new InteractiveStore(database);
            InteractiveCommand shop = InteractiveCommand.create("Shop", true).withAction("[perm:x] msg: <green>hi {player}")
                    .withAction("console: say {player}").withCooldown(Duration.ofSeconds(30)).withTrigger(Trigger.BOTH);
            assertTrue(store.insert(shop).join());
            assertFalse(store.insert(InteractiveCommand.create("shop", false)).join(), "names are case-insensitive");
            BlockKey block = new BlockKey("world", 1, 2, 3);
            assertTrue(store.bind(TargetKind.BLOCK, block.encode(), "Shop").join());
            assertFalse(store.bind(TargetKind.BLOCK, block.encode(), "Shop").join(), "a target is bound only once");
            UUID entity = UUID.randomUUID();
            assertTrue(store.bind(TargetKind.ENTITY, entity.toString(), "Shop").join());
            store.upsert(shop.withPermission("atrium.shop").withCancelEvent(false)).join();

            InteractiveStore.Snapshot snapshot = store.loadAll().join();
            assertEquals(List.of(shop.withPermission("atrium.shop").withCancelEvent(false)), snapshot.commands());
            assertEquals(2, snapshot.targets().size());

            assertTrue(store.unbind(TargetKind.ENTITY, entity.toString()).join());
            assertFalse(store.unbind(TargetKind.ENTITY, entity.toString()).join());
            assertTrue(store.delete("SHOP").join());
            InteractiveStore.Snapshot empty = store.loadAll().join();
            assertTrue(empty.commands().isEmpty());
            assertTrue(empty.targets().isEmpty(), "targets are deleted with their command");
        }
    }

    @Test void emptyActionListRoundTrips() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            database.migrate("interactive", InteractiveStore.MIGRATIONS);
            InteractiveStore store = new InteractiveStore(database);
            store.insert(InteractiveCommand.create("empty", true)).join();
            assertEquals(List.of(), store.loadAll().join().commands().getFirst().actions());
        }
    }
}
