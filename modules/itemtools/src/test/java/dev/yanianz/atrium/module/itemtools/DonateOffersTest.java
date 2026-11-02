package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DonateOffersTest {
    private static final UUID DONOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RECEIVER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void firstOfferIsCreatedAndCanBeReadBack() {
        DonateOffers<String> offers = new DonateOffers<>(10);
        assertEquals(DonateOffers.Status.CREATED,
                offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 3, 100, 200));
        List<DonateOffers.Offer<String>> pending = offers.pending(RECEIVER, 100);
        assertEquals(1, pending.size());
        assertEquals("stone", pending.getFirst().item());
        assertEquals(3, pending.getFirst().amount());
    }

    @Test
    void identicalRepeatRefreshesButDifferentItemIsRefused() {
        DonateOffers<String> offers = new DonateOffers<>(10);
        offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 3, 100, 200);

        assertEquals(DonateOffers.Status.REFRESHED,
                offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 3, 150, 300));
        assertEquals(300, offers.pending(RECEIVER, 150).getFirst().expiresAt());

        assertEquals(DonateOffers.Status.PENDING,
                offers.offer(DONOR, RECEIVER, "dirt", "fp-dirt", 3, 160, 400));
        assertEquals(DonateOffers.Status.PENDING,
                offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 4, 160, 400));
        assertEquals("stone", offers.pending(RECEIVER, 160).getFirst().item(),
                "a different item must not silently replace what the receiver was told about");
    }

    @Test
    void expiredOffersDisappearLazily() {
        DonateOffers<String> offers = new DonateOffers<>(10);
        offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 1, 100, 110);
        assertTrue(offers.pending(RECEIVER, 109).size() == 1);
        assertTrue(offers.pending(RECEIVER, 110).isEmpty());
        assertTrue(offers.remove(RECEIVER, DONOR, 200).isEmpty());
    }

    @Test
    void receiverCannotHaveMoreThanTheConfiguredNumberOfOffers() {
        DonateOffers<String> offers = new DonateOffers<>(1);
        assertEquals(DonateOffers.Status.CREATED,
                offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 1, 100, 200));
        assertEquals(DonateOffers.Status.FULL,
                offers.offer(OTHER, RECEIVER, "dirt", "fp-dirt", 1, 100, 200));
        assertEquals(1, offers.pendingCount(RECEIVER, 100));
    }

    @Test
    void removeTakesExactlyOneOffer() {
        DonateOffers<String> offers = new DonateOffers<>(10);
        offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 1, 100, 200);
        offers.offer(OTHER, RECEIVER, "dirt", "fp-dirt", 2, 100, 200);

        assertEquals("dirt", offers.remove(RECEIVER, OTHER, 100).orElseThrow().item());
        assertEquals(1, offers.pendingCount(RECEIVER, 100));
        assertEquals("stone", offers.remove(RECEIVER, DONOR, 100).orElseThrow().item());
        assertTrue(offers.pending(RECEIVER, 100).isEmpty());
    }

    @Test
    void quitRemovesOffersInBothDirections() {
        DonateOffers<String> offers = new DonateOffers<>(10);
        offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 1, 100, 200);
        offers.offer(OTHER, RECEIVER, "dirt", "fp-dirt", 1, 100, 200);

        List<DonateOffers.Offer<String>> removed = offers.removePlayer(RECEIVER);
        assertEquals(2, removed.size());
        assertTrue(offers.pending(RECEIVER, 100).isEmpty());

        offers.offer(OTHER, RECEIVER, "dirt", "fp-dirt", 1, 100, 200);
        assertEquals(1, offers.removePlayer(OTHER).size());
        assertTrue(offers.pending(RECEIVER, 100).isEmpty());
    }

    @Test
    void removeExpiredReportsTheCountAndKeepsLiveOffers() {
        DonateOffers<String> offers = new DonateOffers<>(10);
        offers.offer(DONOR, RECEIVER, "stone", "fp-stone", 1, 100, 110);
        offers.offer(OTHER, RECEIVER, "dirt", "fp-dirt", 1, 100, 500);

        assertEquals(1, offers.removeExpired(200));
        assertFalse(offers.pending(RECEIVER, 200).isEmpty());
        assertEquals(0, offers.removeExpired(200));
    }
}
