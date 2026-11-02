package dev.yanianz.atrium.module.localeeditor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.localeeditor.KeyPath.InvalidKeyException;
import dev.yanianz.atrium.module.localeeditor.KeyPath.Reason;
import org.junit.jupiter.api.Test;

/** Message keys and file names are the only place where an argument becomes a path. */
class KeyPathTest {
    @Test
    void parsesNestedKeys() {
        assertEquals(java.util.List.of("reason", "death"), KeyPath.parse("reason.death"));
        assertEquals(java.util.List.of("a", "b", "c"), KeyPath.parse(" a.b.c "));
        assertEquals("reason.death", KeyPath.join(KeyPath.parse("reason.death")));
    }

    @Test
    void refusesEmptyAndBrokenKeys() {
        assertEquals(Reason.EMPTY, assertThrows(InvalidKeyException.class, () -> KeyPath.parse("")).reason());
        assertEquals(Reason.EMPTY, assertThrows(InvalidKeyException.class, () -> KeyPath.parse("   ")).reason());
        assertEquals(Reason.EMPTY_SEGMENT, assertThrows(InvalidKeyException.class, () -> KeyPath.parse("a..b")).reason());
        assertEquals(Reason.EMPTY_SEGMENT, assertThrows(InvalidKeyException.class, () -> KeyPath.parse("a.")).reason());
    }

    @Test
    void refusesPathTraversalAndSeparators() {
        assertEquals(Reason.ILLEGAL_CHARACTER,
                assertThrows(InvalidKeyException.class, () -> KeyPath.parse("a/b")).reason());
        assertThrows(InvalidKeyException.class, () -> KeyPath.parse("../secret"));
        assertEquals(Reason.ILLEGAL_CHARACTER,
                assertThrows(InvalidKeyException.class, () -> KeyPath.parse("a b")).reason());
    }

    @Test
    void refusesAbsurdLengths() {
        assertEquals(Reason.TOO_LONG,
                assertThrows(InvalidKeyException.class, () -> KeyPath.parse("a.b.c.d.e.f.g.h.i")).reason());
        String longSegment = "x".repeat(KeyPath.MAX_SEGMENT_LENGTH + 1);
        assertEquals(Reason.TOO_LONG,
                assertThrows(InvalidKeyException.class, () -> KeyPath.parse(longSegment)).reason());
    }

    @Test
    void normalisesLocalesAndRefusesUnsafeOnes() {
        assertEquals("en_US", KeyPath.locale("en_us"));
        assertEquals("id_ID", KeyPath.locale("id-ID"));
        assertThrows(InvalidKeyException.class, () -> KeyPath.locale(""));
        assertThrows(InvalidKeyException.class, () -> KeyPath.locale("../../etc"));
        assertThrows(InvalidKeyException.class, () -> KeyPath.locale("en/US"));
    }

    @Test
    void namesAreSingleSafeSegments() {
        assertTrue(KeyPath.validName("en_US"));
        assertTrue(KeyPath.validName("inventories"));
        assertTrue(!KeyPath.validName("a/b"));
        assertTrue(!KeyPath.validName(".."));
        assertTrue(!KeyPath.validName(null));
    }
}
