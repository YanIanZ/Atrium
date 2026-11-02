package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MirrorOwnershipTest {
    @Test void prefixAloneIsNotOwnership() {
        MirrorOwnership ownership = new MirrorOwnership();
        // An admin's own DH/FH hologram named atrium_spawn: never adopted, overwritten or deleted.
        assertEquals(MirrorOwnership.Status.FOREIGN, ownership.status("atrium_spawn", true));
        assertEquals(MirrorOwnership.Status.FOREIGN, ownership.status("atrium_spawn", false));
        assertFalse(ownership.owns("atrium_spawn", false));
    }

    @Test void ownsOnlyNonPersistentNamesItCreated() {
        MirrorOwnership ownership = new MirrorOwnership();
        ownership.claim("atrium_spawn");
        assertEquals(MirrorOwnership.Status.OWNED, ownership.status("atrium_spawn", false));
        // Same name but saved to file (persistent): someone else's, even though Atrium once used the name.
        assertEquals(MirrorOwnership.Status.FOREIGN, ownership.status("atrium_spawn", true));
        assertEquals(MirrorOwnership.Status.FOREIGN, ownership.status("atrium_other", false));
    }

    @Test void releaseAndReleaseAllDropOwnership() {
        MirrorOwnership ownership = new MirrorOwnership();
        ownership.claim("atrium_a");
        ownership.claim("atrium_b");
        ownership.release("atrium_a");
        assertFalse(ownership.owns("atrium_a", false));
        assertTrue(ownership.owns("atrium_b", false));
        ownership.releaseAll();
        assertFalse(ownership.owns("atrium_b", false));
    }

    @Test void unprefixedNamesAreUnrelated() {
        MirrorOwnership ownership = new MirrorOwnership();
        ownership.claim("shop");
        assertEquals(MirrorOwnership.Status.UNRELATED, ownership.status("shop", false));
        assertEquals(MirrorOwnership.Status.UNRELATED, ownership.status(null, false));
    }

    @Test void warnsOncePerForeignName() {
        MirrorOwnership ownership = new MirrorOwnership();
        assertTrue(ownership.warnOnce("atrium_spawn"));
        assertFalse(ownership.warnOnce("atrium_spawn"));
        assertTrue(ownership.warnOnce("atrium_lobby"));
    }

    @Test void sharedRegistryIsPerEngineKind() {
        MirrorOwnership decent = MirrorOwnership.forKind(EngineKind.DECENTHOLOGRAMS);
        assertSame(decent, MirrorOwnership.forKind(EngineKind.DECENTHOLOGRAMS));
        assertNotSame(decent, MirrorOwnership.forKind(EngineKind.FANCYHOLOGRAMS));
    }
}
