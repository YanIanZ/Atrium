package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path directory;

    private static Hologram sample(String name) {
        HologramSettings settings = HologramSettings.builtInDefaults().with(Setting.SCALE, "1.75").with(Setting.PAGE_INTERVAL, "100");
        List<List<HoloLine>> pages = List.of(
                List.of(HoloLine.of("<gold>Title"), HoloLine.of("#item:diamond").withOffset(0.5f, -0.25f, 0f)),
                List.of(),
                List.of(HoloLine.of("Page %player_name%")));
        return new Hologram(name, new StoredLocation("world", 1.5, 64, -3.25, 90f, 0f), settings, pages,
                List.of(ClickAction.parse("right [console] say {player}"), ClickAction.parse("left [prev]")), 1234L);
    }

    @Test void roundTripsIncludingEmptyPagesOffsetsAndActions() throws Exception {
        try (var db = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            db.migrate("holograms", HologramStorage.MIGRATIONS);
            var storage = new HologramStorage(db, LoggerFactory.getLogger(getClass()));
            Hologram hologram = sample("Spawn");
            storage.save(hologram).join();
            List<Hologram> loaded = storage.loadAll(HologramSettings::builtInDefaults).join();
            assertEquals(List.of(hologram), loaded);

            Hologram edited = hologram.withPages(List.of(List.of(HoloLine.of("only"))));
            storage.save(edited).join();
            assertEquals(List.of(edited), storage.loadAll(HologramSettings::builtInDefaults).join());

            storage.delete("spawn").join();
            assertTrue(storage.loadAll(HologramSettings::builtInDefaults).join().isEmpty());
        }
    }

    @Test void concurrentEditsOfOneHologramAreNotLost() throws Exception {
        try (var db = new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()))) {
            db.migrate("holograms", HologramStorage.MIGRATIONS);
            var storage = new HologramStorage(db, LoggerFactory.getLogger(getClass()));
            List<String> events = new ArrayList<>();
            var manager = new HologramManager(storage, (old, next) -> {
                synchronized (events) {
                    events.add(old == null ? "create" : next == null ? "delete" : "update");
                }
            });
            manager.edit("Board", current -> HologramManager.Change.ok(sample("Board").withPages(List.of(List.of())))).join();
            List<CompletableFuture<HologramManager.Outcome>> edits = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String line = "line " + i;
                edits.add(CompletableFuture.supplyAsync(() -> manager.edit("board", current -> {
                    List<List<HoloLine>> pages = current.mutablePages();
                    pages.getLast().add(HoloLine.of(line));
                    return HologramManager.Change.ok(current.withPages(pages));
                })).thenCompose(f -> f));
            }
            edits.forEach(CompletableFuture::join);
            Hologram result = manager.get("BOARD").orElseThrow();
            assertEquals(40, result.lineCount());
            assertEquals(40, storage.loadAll(HologramSettings::builtInDefaults).join().getFirst().lineCount());
            assertEquals(41, events.size());
            assertEquals(HologramManager.Outcome.EXISTS,
                    manager.edit("board", c -> c != null ? HologramManager.Change.fail(HologramManager.Outcome.EXISTS)
                            : HologramManager.Change.ok(sample("board"))).join());
            assertEquals(HologramManager.Outcome.UNCHANGED, manager.edit("board", HologramManager.Change::ok).join());
            assertEquals(HologramManager.Outcome.OK, manager.edit("board", c -> HologramManager.Change.delete()).join());
            assertTrue(manager.get("board").isEmpty());
        }
    }
}
