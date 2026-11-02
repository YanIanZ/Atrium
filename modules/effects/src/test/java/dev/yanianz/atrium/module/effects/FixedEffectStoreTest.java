package dev.yanianz.atrium.module.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class FixedEffectStoreTest {
    @TempDir Path directory;

    private Database database() {
        CoreConfig.Database config = new CoreConfig.Database();
        config.type = "SQLITE";
        config.tablePrefix = "atrium_";
        return new Database(config, directory, Executors.newVirtualThreadPerTaskExecutor(),
                LoggerFactory.getLogger("effects-test"));
    }

    @Test void savesLoadsUpdatesAndDeletesAnEffect() throws Exception {
        try (Database db = database()) {
            db.migrate("effects", FixedEffectStore.MIGRATIONS);
            FixedEffectStore store = new FixedEffectStore(db, LoggerFactory.getLogger("effects-test"));

            FixedEffect effect = new FixedEffect("spawn-ring", new StoredLocation("world", 10.5, 64, -3.25, 0, 0),
                    Shape.RING, Particle.DUST, Color.fromRGB(0xff8800), 2.5, 1.0, 24, 20.0, 3);
            store.upsert(effect).get();

            List<FixedEffect> loaded = store.loadAll().get();
            assertEquals(1, loaded.size());
            FixedEffect read = loaded.getFirst();
            assertEquals("spawn-ring", read.name());
            assertEquals(Shape.RING, read.shape());
            assertEquals(Particle.DUST, read.particle());
            assertEquals(0xff8800, read.colour().asRGB());
            assertEquals(10.5, read.location().x(), 1e-9);
            assertEquals(-3.25, read.location().z(), 1e-9);
            assertEquals(24, read.points());
            assertEquals(3, read.intervalTicks());

            FixedEffect moved = effect.moved(new StoredLocation("world", 0, 80, 0, 0, 0)).withRadius(4.0);
            store.upsert(moved).get();
            List<FixedEffect> afterMove = store.loadAll().get();
            assertEquals(1, afterMove.size(), "upsert keeps a single row per name");
            assertEquals(4.0, afterMove.getFirst().radius(), 1e-9);
            assertEquals(80.0, afterMove.getFirst().location().y(), 1e-9);

            assertTrue(store.delete(effect.key()).get());
            assertFalse(store.delete(effect.key()).get());
            assertTrue(store.loadAll().get().isEmpty());
        }
    }
}
