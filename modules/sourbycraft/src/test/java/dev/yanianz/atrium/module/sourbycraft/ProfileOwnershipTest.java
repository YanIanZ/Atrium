package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.profile.SimpleProfileScopedDataRegistry;
import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Action;
import dev.yanianz.atrium.module.sourbycraft.ScopedStateBridgeTest.FakeData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/**
 * Profile switches as the official SourbyCraft API performs them: capture the profile being left, then
 * {@code apply} only when the target holds Atrium data (no {@code applyMissing}), commit later.
 */
class ProfileOwnershipTest {
    private static final long SETTLE = 15_000;
    private final UUID player = UUID.randomUUID();
    private final UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private final UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final FakeData homes = new FakeData("homes", 1);
    private final FakeData kits = new FakeData("kits", 1);
    private final AtomicReference<ProfileScopes.MissingState> missing = new AtomicReference<>(ProfileScopes.MissingState.EMPTY);
    private final ScopedStateBridge bridge;
    private final Map<UUID, UUID> persisted = new HashMap<>();
    private final AtomicLong clock = new AtomicLong(1_000_000);
    private ProfileOwnership ownership;

    /** SourbyCraft's per-profile adapter data. */
    private final Map<UUID, byte[]> snapshots = new HashMap<>();
    private UUID activeProfile;

    ProfileOwnershipTest() {
        SimpleProfileScopedDataRegistry registry = new SimpleProfileScopedDataRegistry();
        registry.register(homes);
        registry.register(kits);
        bridge = new ScopedStateBridge(registry, () -> List.of("homes", "kits"), missing::get, NOPLogger.NOP_LOGGER);
        ownership = newOwnership();
    }

    private ProfileOwnership newOwnership() {
        ProfileOwnership.OwnerStore store = new ProfileOwnership.OwnerStore() {
            @Override
            public @Nullable UUID load(UUID id) {
                return persisted.get(id);
            }

            @Override
            public void save(UUID id, @Nullable UUID owner) {
                persisted.put(id, owner);
            }
        };
        return new ProfileOwnership(bridge, store, missing::get, clock::get, SETTLE, NOPLogger.NOP_LOGGER);
    }

    /** The official switch: capture, apply only when the target has data, commit. */
    private void switchTo(UUID target) throws Exception {
        UUID uuid = player;
        snapshots.put(activeProfile, bridge.capture(uuid, ownership.beforeCapture(uuid)));
        byte[] stored = snapshots.get(target);
        if (stored != null) {
            ownership.restored(uuid, bridge.apply(uuid, stored));
        }
        clock.addAndGet(500);
        activeProfile = target; // commit
    }

    /** A lookup that starts now and sees the committed profile. */
    private Action observe() {
        return ownership.observe(player, activeProfile, clock.get());
    }

    private void startOn(UUID profile) {
        activeProfile = profile;
        assertEquals(Action.ADOPT, observe(), "first observation adopts existing data");
    }

    @Test
    void switchingIntoANewProfileClearsInsteadOfLeaking() throws Exception {
        startOn(a);
        homes.live.put(player, "home@A");
        kits.live.put(player, "starter@A");

        switchTo(b); // b is new: SourbyCraft never calls apply
        assertEquals("home@A", homes.live.get(player), "nothing happened yet: the official API gives no signal");
        assertEquals(Action.CLEAR, observe());
        assertNull(homes.live.get(player));
        assertNull(kits.live.get(player));
        assertEquals(b, ownership.owner(player));
        assertEquals(b, persisted.get(player));

        homes.live.put(player, "home@B");
        switchTo(a);
        assertEquals("home@A", homes.live.get(player));
        assertEquals("starter@A", kits.live.get(player));
        assertEquals(Action.CONFIRM, observe());
        assertFalse(ownership.pending(player));

        switchTo(b);
        assertEquals("home@B", homes.live.get(player));
        assertNull(kits.live.get(player), "A's kit usage is not inherited");
        assertEquals(Action.CONFIRM, observe());
    }

    @Test
    void anEmptyProfileStillGetsDataSoItRestoresAsEmpty() throws Exception {
        startOn(a);
        switchTo(b);
        observe();
        switchTo(a); // b captured with no homes: an explicit empty envelope
        homes.live.put(player, "home@A");
        switchTo(b);
        assertNull(homes.live.get(player), "b restores as empty instead of keeping A's homes");
        assertEquals(Action.CONFIRM, observe());
    }

    @Test
    void staleObservationAfterARestoreWaitsInsteadOfClearing() throws Exception {
        startOn(a);
        switchTo(b);
        observe();
        homes.live.put(player, "home@B");
        switchTo(a);
        switchTo(b);
        // A lookup started after the restore but answered before the commit: still reports a.
        assertEquals(Action.WAIT, ownership.observe(player, a, clock.get()));
        assertEquals("home@B", homes.live.get(player));
        assertEquals(Action.CONFIRM, observe());
        assertEquals("home@B", homes.live.get(player));
    }

    @Test
    void captureNeverWritesAnotherProfilesDataIntoTheProfileBeingLeft() throws Exception {
        startOn(a);
        homes.live.put(player, "home@A");
        switchTo(b);
        observe(); // b known, data cleared
        // b -> new profile c before anything else happened.
        UUID c = UUID.randomUUID();
        switchTo(c);
        assertEquals(Action.CLEAR, observe());
        assertNull(homes.live.get(player));
        switchTo(b);
        assertNull(homes.live.get(player), "b's snapshot holds b's (empty) data, not A's");
    }

    @Test
    void lazyReconcileBeforeACommandUsesTheLatestObservation() throws Exception {
        startOn(a);
        homes.live.put(player, "home@A");
        switchTo(b);
        ownership.observe(player, b, clock.get());
        // the observation was delivered; a later command reconciles again without effect
        assertEquals(Action.NONE, ownership.reconcile(player));
        assertNull(homes.live.get(player));
    }

    @Test
    void ownerSurvivesARestartAndClearsDataOfTheWrongProfile() throws Exception {
        startOn(a);
        homes.live.put(player, "home@A");
        switchTo(b);
        // crash before any observation: the database still holds A's homes, owner A
        assertEquals(a, persisted.get(player));
        ownership = newOwnership();
        assertEquals(Action.CLEAR, observe(), "after the restart b is active, the stored owner is a");
        assertNull(homes.live.get(player));
    }

    @Test
    void keepAdoptsTheLiveDataForAProfileWithoutData() throws Exception {
        missing.set(ProfileScopes.MissingState.KEEP);
        startOn(a);
        homes.live.put(player, "home@A");
        switchTo(b);
        assertEquals(Action.ADOPT, observe());
        assertEquals("home@A", homes.live.get(player));
        assertEquals(b, ownership.owner(player));
    }

    @Test
    void ownerIsPersistedOnlyAfterTheDataWasStored() throws Exception {
        CompletableFuture<Void> slow = new CompletableFuture<>();
        FakeData slowHomes = new FakeData("homes", 1) {
            @Override
            public CompletableFuture<Void> apply(UUID p, byte[] data) {
                super.apply(p, data);
                return slow;
            }
        };
        SimpleProfileScopedDataRegistry registry = new SimpleProfileScopedDataRegistry();
        registry.register(slowHomes);
        ScopedStateBridge slowBridge = new ScopedStateBridge(registry, () -> List.of("homes"), missing::get,
                NOPLogger.NOP_LOGGER);
        ProfileOwnership.OwnerStore store = new ProfileOwnership.OwnerStore() {
            @Override
            public @Nullable UUID load(UUID id) {
                return persisted.get(id);
            }

            @Override
            public void save(UUID id, @Nullable UUID owner) {
                persisted.put(id, owner);
            }
        };
        ProfileOwnership slowOwnership = new ProfileOwnership(slowBridge, store, missing::get, clock::get, SETTLE,
                NOPLogger.NOP_LOGGER);
        slowOwnership.observe(player, a, clock.get());
        assertEquals(a, persisted.get(player));
        assertEquals(Action.CLEAR, slowOwnership.observe(player, b, clock.get()));
        assertEquals(a, persisted.get(player), "not yet: the cleared data is not stored");
        slow.complete(null);
        assertEquals(b, persisted.get(player));
        assertTrue(slowOwnership.latestObservation(player) > 0);
    }
}
