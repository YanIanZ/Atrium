package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SectionPagesTest {
    @Test
    void vanillaSectionsKeepTheirSinglePageLayout() {
        assertEquals(1, SectionPages.pages(41));
        assertEquals(6, SectionPages.rows(41));
        assertEquals(1, SectionPages.pages(27));
        assertEquals(4, SectionPages.rows(27));
        assertEquals(41, SectionPages.count(0, 41));
    }

    @Test
    void sixRowEnderChestIsPagedWithTheNavigationRow() {
        assertEquals(2, SectionPages.pages(54));
        assertEquals(6, SectionPages.rows(54));
        assertEquals(0, SectionPages.first(0, 54));
        assertEquals(45, SectionPages.count(0, 54));
        assertEquals(45, SectionPages.first(1, 54));
        assertEquals(9, SectionPages.count(1, 54));
    }

    @Test
    void pagesAreClamped() {
        assertEquals(45, SectionPages.first(7, 54));
        assertEquals(0, SectionPages.first(-1, 54));
        assertEquals(1, SectionPages.pages(0));
        assertEquals(2, SectionPages.rows(0));
    }

    @Test
    void largerStorageFromAForkIsPaged() {
        assertEquals(2, SectionPages.pages(59));
        assertEquals(14, SectionPages.count(1, 59));
    }
}
