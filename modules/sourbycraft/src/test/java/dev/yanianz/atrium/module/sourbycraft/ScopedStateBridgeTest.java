package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.profile.ProfileScopedData;
import dev.yanianz.atrium.core.profile.SimpleProfileScopedDataRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class ScopedStateBridgeTest {
    /** In-memory provider: "live" state per player, like a loaded user component. */
    static class FakeData implements ProfileScopedData {
        final String id;
        final int version;
        final Map<UUID, String> live = new HashMap<>();
        int applies;

        FakeData(String id, int version) {
            this.id = id;
            this.version = version;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int version() {
            return version;
        }

        @Override
        public byte[] capture(UUID player) {
            String value = live.get(player);
            return value == null ? null : value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public CompletableFuture<Void> apply(UUID player, byte[] data) {
            applies++;
            if (data == null) {
                live.remove(player);
            } else {
                live.put(player, new String(data, StandardCharsets.UTF_8));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private final UUID player = UUID.randomUUID();
    private final SimpleProfileScopedDataRegistry registry = new SimpleProfileScopedDataRegistry();
    private final FakeData homes = new FakeData("homes", 1);
    private final FakeData back = new FakeData("back", 1);
    private final FakeData kits = new FakeData("kits", 1);
    private final AtomicReference<List<String>> scopes = new AtomicReference<>(List.of("homes", "back", "kits"));
    private final AtomicReference<ProfileScopes.MissingState> missing = new AtomicReference<>(ProfileScopes.MissingState.EMPTY);
    private final ScopedStateBridge bridge = new ScopedStateBridge(registry, scopes::get, missing::get, NOPLogger.NOP_LOGGER);

    ScopedStateBridgeTest() {
        registry.register(homes);
        registry.register(back);
        registry.register(kits);
    }

    @Test
    void switchingProfilesNeverMixesData() throws Exception {
        homes.live.put(player, "home@islandA");
        kits.live.put(player, "starter used");
        byte[] profileA = bridge.capture(player, null);
        assertNotNull(profileA);

        // Switch to a brand-new profile B: nothing stored -> everything starts empty.
        bridge.applyMissing(player);
        assertNull(homes.live.get(player));
        assertNull(kits.live.get(player));
        homes.live.put(player, "home@islandB");
        back.live.put(player, "back@B");
        byte[] profileB = bridge.capture(player, null);

        // Back to A: A's data, and B's /back history is not inherited.
        bridge.apply(player, profileA);
        assertEquals("home@islandA", homes.live.get(player));
        assertEquals("starter used", kits.live.get(player));
        assertNull(back.live.get(player));

        bridge.apply(player, profileB);
        assertEquals("home@islandB", homes.live.get(player));
        assertEquals("back@B", back.live.get(player));
        assertNull(kits.live.get(player));
    }

    @Test
    void captureRecordsTheOwnerAndApplyReturnsIt() throws Exception {
        UUID profile = UUID.randomUUID();
        homes.live.put(player, "h");
        byte[] stored = bridge.capture(player, profile);
        homes.live.clear();
        ScopedStateBridge.Restored restored = bridge.apply(player, stored);
        assertEquals(profile, restored.owner());
        assertTrue(restored.stored().isDone());
        assertEquals("h", homes.live.get(player));
        assertNull(bridge.apply(player, bridge.capture(player, null)).owner(), "untagged data has no owner");
    }

    @Test
    void clearEmptiesEvenWithKeep() throws Exception {
        missing.set(ProfileScopes.MissingState.KEEP);
        homes.live.put(player, "h");
        back.live.put(player, "b");
        bridge.clear(player).join();
        assertNull(homes.live.get(player));
        assertNull(back.live.get(player));
    }

    @Test
    void captureIsNeverEmptyEvenWithoutData() throws Exception {
        byte[] bytes = bridge.capture(player, null);
        assertTrue(bytes.length > 0, "SourbyCraft skips an adapter whose capture is empty and never applies it");
        assertEquals(3, ProfileEnvelope.decode(bytes).size());
    }

    @Test
    void onlyConfiguredScopesAreStoredAndRestored() throws Exception {
        scopes.set(List.of("homes"));
        homes.live.put(player, "h");
        kits.live.put(player, "k");
        byte[] stored = bridge.capture(player, null);
        assertEquals(List.of("homes"), List.copyOf(ProfileEnvelope.decode(stored).keySet()));
        kits.live.put(player, "account-wide");
        bridge.apply(player, stored);
        assertEquals("account-wide", kits.live.get(player), "unscoped data is never touched");
    }

    @Test
    void keepLeavesLiveDataForMissingProfiles() throws Exception {
        missing.set(ProfileScopes.MissingState.KEEP);
        homes.live.put(player, "existing");
        bridge.applyMissing(player);
        assertEquals("existing", homes.live.get(player));
    }

    @Test
    void newerStoredVersionIsRefusedBeforeAnythingChanges() throws Exception {
        FakeData newer = new FakeData("homes", 2);
        SimpleProfileScopedDataRegistry future = new SimpleProfileScopedDataRegistry();
        future.register(newer);
        future.register(kits);
        newer.live.put(player, "v2 homes");
        byte[] fromNewerAtrium = new ScopedStateBridge(future, scopes::get, missing::get, NOPLogger.NOP_LOGGER).capture(player, null);

        homes.live.put(player, "current");
        kits.live.put(player, "current kits");
        int appliesBefore = kits.applies;
        assertThrows(IllegalStateException.class, () -> bridge.apply(player, fromNewerAtrium));
        assertEquals("current", homes.live.get(player));
        assertEquals(appliesBefore, kits.applies, "no partial restore");
    }

    @Test
    void corruptDataFailsWithoutChanges() {
        homes.live.put(player, "current");
        assertThrows(Exception.class, () -> bridge.apply(player, new byte[] {1, 2, 3}));
        assertEquals("current", homes.live.get(player));
    }

    @Test
    void oversizedStateFailsCapture() {
        homes.live.put(player, "x".repeat(ProfileEnvelope.MAX_BYTES + 1));
        assertThrows(ProfileEnvelope.TooLargeException.class, () -> bridge.capture(player, null));
    }

    @Test
    void failingProviderStillRestoresOthersThenReports() throws Exception {
        FakeData broken = new FakeData("back", 1) {
            @Override
            public CompletableFuture<Void> apply(UUID p, byte[] data) {
                throw new IllegalStateException("not loaded");
            }
        };
        SimpleProfileScopedDataRegistry reg = new SimpleProfileScopedDataRegistry();
        reg.register(homes);
        reg.register(broken);
        ScopedStateBridge partial = new ScopedStateBridge(reg, scopes::get, missing::get, NOPLogger.NOP_LOGGER);
        homes.live.put(player, "A");
        byte[] stored = partial.capture(player, null);
        homes.live.put(player, "B");
        assertThrows(IllegalStateException.class, () -> partial.apply(player, stored));
        assertEquals("A", homes.live.get(player));
    }

    @Test
    void unregisteredScopeIsSkipped() throws Exception {
        registry.unregister(kits);
        byte[] stored = bridge.capture(player, null);
        assertEquals(2, ProfileEnvelope.decode(stored).size());
        assertArrayEquals(stored, bridge.capture(player, null));
    }
}
