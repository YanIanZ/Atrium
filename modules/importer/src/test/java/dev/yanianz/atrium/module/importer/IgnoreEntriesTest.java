package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Ignore-list entries of both sources: UUIDs, legacy names, CMI's {@code ;} list and JSON arrays. */
class IgnoreEntriesTest {
    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Test
    void parsesTheCmiSeparatorForm() {
        assertEquals(List.of(ID, "Notch"), IgnoreEntries.parse(ID + ";Notch"));
        assertEquals(List.of(ID, "Notch"), IgnoreEntries.parse(ID + " , Notch "));
        assertEquals(List.of(), IgnoreEntries.parse(""));
        assertEquals(List.of(), IgnoreEntries.parse(null));
        assertEquals(List.of(), IgnoreEntries.parse(" ; , "));
    }

    @Test
    void parsesANewerCmiJsonArray() {
        assertEquals(List.of(ID, "Notch"), IgnoreEntries.parse("[\"" + ID + "\", \"Notch\"]"));
        // A malformed array falls back to the plain separator split instead of losing the data.
        assertTrue(IgnoreEntries.parse("[broken").contains("[broken"));
    }

    @Test
    void dedupeIgnoresCaseAndOrder() {
        List<String> entries = IgnoreEntries.dedupe(List.of("Notch", "notch", ID, ID.toUpperCase(), " ", "Herobrine"));
        assertEquals(3, entries.size());
        assertEquals("Notch", entries.get(0));
        assertEquals(ID, entries.get(1));
        assertEquals("Herobrine", entries.get(2));
    }

    @Test
    void distinguishesUuidsFromNames() {
        assertEquals(UUID.fromString(ID), IgnoreEntries.uuid(ID));
        assertEquals(UUID.fromString(ID), IgnoreEntries.uuid(ID.toUpperCase()));
        assertNull(IgnoreEntries.uuid("Notch"));
        assertNull(IgnoreEntries.uuid(""));
        assertNull(IgnoreEntries.uuid(null));
    }
}
