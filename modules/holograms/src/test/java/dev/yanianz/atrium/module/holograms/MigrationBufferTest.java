package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationBufferTest {
    private static Hologram hologram(String name, String text) {
        return new Hologram(name, new StoredLocation("world", 1, 2, 3, 0, 0), HologramSettings.builtInDefaults(),
                List.of(List.of(HoloLine.of(text))), List.of(), 1L);
    }

    @Test void editDuringMigrationIsReplayedFromTheSnapshot() {
        Hologram v1 = hologram("Spawn", "one");
        Hologram v2 = hologram("Spawn", "two");
        Hologram v3 = hologram("Spawn", "three");
        MigrationBuffer buffer = new MigrationBuffer();
        buffer.record(v1, v2);
        buffer.record(v2, v3);
        List<MigrationBuffer.Change> changes = buffer.drain(List.of(v1));
        assertEquals(List.of(new MigrationBuffer.Change(v1, v3)), changes);
        assertTrue(buffer.isEmpty());
    }

    @Test void createAndDeleteDuringMigration() {
        Hologram kept = hologram("Kept", "x");
        Hologram gone = hologram("Gone", "x");
        Hologram added = hologram("Added", "x");
        MigrationBuffer buffer = new MigrationBuffer();
        buffer.record(null, added);
        buffer.record(gone, null);
        List<MigrationBuffer.Change> changes = buffer.drain(List.of(kept, gone));
        assertEquals(List.of(new MigrationBuffer.Change(null, added), new MigrationBuffer.Change(gone, null)), changes);
    }

    @Test void changesAlreadyInTheSnapshotOrUndoneProduceNothing() {
        Hologram v1 = hologram("Spawn", "one");
        Hologram v2 = hologram("Spawn", "two");
        Hologram temp = hologram("Temp", "x");
        MigrationBuffer buffer = new MigrationBuffer();
        buffer.record(v1, v2); // stored before the snapshot was read: the new engine already has v2
        buffer.record(null, temp);
        buffer.record(temp, null); // created and deleted during the migration
        assertEquals(List.of(), buffer.drain(List.of(v2)));
    }

    @Test void keysAreCaseInsensitive() {
        Hologram v1 = hologram("Spawn", "one");
        Hologram v2 = hologram("SPAWN", "two");
        MigrationBuffer buffer = new MigrationBuffer();
        buffer.record(v1, v2);
        assertEquals(List.of(new MigrationBuffer.Change(v1, v2)), buffer.drain(List.of(v1)));
    }
}
