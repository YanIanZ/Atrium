package dev.yanianz.atrium.module.armorstand;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PoseStoreTest {
    @TempDir Path directory;

    @Test void savesLoadsWithIdenticalRoundingAndEnforcesLimit() throws Exception {
        try (var database = new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()))) {
            database.migrate("armorstand", PoseStore.MIGRATIONS);
            var store = new PoseStore(database);
            UUID owner = UUID.randomUUID();
            EnumMap<Pose.Part, Pose.Angles> map = new EnumMap<>(Pose.Part.class);
            map.put(Pose.Part.HEAD, Pose.Angles.of(12.345678, -0.004, 359.996));
            map.put(Pose.Part.LEFT_ARM, Pose.Angles.of(-14.99, 0.5, 180));
            Pose pose = new Pose(map);

            assertEquals(PoseStore.SaveResult.CREATED, store.save(owner, "wave", pose, 2).join());
            assertEquals(Optional.of(pose), store.load(owner, "wave").join());
            assertEquals(new Pose.Angles(12.35, 0.0, 0.0), store.load(owner, "wave").join().orElseThrow().get(Pose.Part.HEAD));

            assertEquals(PoseStore.SaveResult.REPLACED, store.save(owner, "wave", Pose.DEFAULT, 2).join());
            assertEquals(PoseStore.SaveResult.CREATED, store.save(owner, "sit", pose, 2).join());
            assertEquals(PoseStore.SaveResult.LIMIT, store.save(owner, "third", pose, 2).join());
            assertEquals(PoseStore.SaveResult.REPLACED, store.save(owner, "sit", pose, 2).join());
            assertEquals(List.of("sit", "wave"), store.names(owner).join());
            assertEquals(Optional.of(Pose.DEFAULT), store.load(owner, "wave").join());

            assertTrue(store.delete(owner, "sit").join());
            assertFalse(store.delete(owner, "sit").join());
            assertEquals(Optional.empty(), store.load(UUID.randomUUID(), "wave").join());
        }
    }

    @Test void validatesNames() {
        assertTrue(PoseStore.validName("arm_up-2"));
        assertFalse(PoseStore.validName("Arm"));
        assertFalse(PoseStore.validName(""));
        assertFalse(PoseStore.validName("a".repeat(33)));
        assertFalse(PoseStore.validName("a b"));
    }
}
