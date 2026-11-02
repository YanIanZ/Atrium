package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.teleport.RequestBook.Request;
import dev.yanianz.atrium.module.teleport.RequestBook.Type;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestBookTest {
    private final MutableClock clock = new MutableClock();
    private final RequestBook book = new RequestBook(clock, () -> Duration.ofSeconds(60));
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @Test
    void requestExpiresAfterLifetime() {
        book.add(alice, bob, Type.TPA);
        clock.advance(Duration.ofSeconds(59));
        assertTrue(book.hasPending(alice, bob, Type.TPA));
        clock.advance(Duration.ofSeconds(1));
        assertFalse(book.hasPending(alice, bob, Type.TPA));
        assertEquals(Optional.empty(), book.take(bob, Optional.empty()));
    }

    @Test
    void expiredRequestsAreReportedOnce() {
        book.add(alice, bob, Type.TPA);
        clock.advance(Duration.ofSeconds(10));
        book.add(carol, bob, Type.TPAHERE);
        clock.advance(Duration.ofSeconds(55));
        List<Request> expired = book.expire();
        assertEquals(1, expired.size());
        assertEquals(alice, expired.getFirst().sender());
        assertEquals(List.of(), book.expire());
        assertEquals(1, book.size());
    }

    @Test
    void acceptingByNameNeverFallsBackToAnotherSender() {
        book.add(carol, bob, Type.TPA);
        // Bob types /tpaccept alice but only Carol asked.
        assertEquals(Optional.empty(), book.take(bob, Optional.of(alice)));
        assertEquals(1, book.size());
    }

    @Test
    void acceptWithoutNameTakesNewestAndLeavesOthers() {
        book.add(alice, bob, Type.TPA);
        clock.advance(Duration.ofSeconds(1));
        book.add(carol, bob, Type.TPAHERE);
        Request taken = book.take(bob, Optional.empty()).orElseThrow();
        assertEquals(carol, taken.sender());
        assertEquals(Type.TPAHERE, taken.type());
        assertEquals(1, book.size());
        assertEquals(alice, book.take(bob, Optional.empty()).orElseThrow().sender());
    }

    @Test
    void consumingOneRequestKeepsTheSendersOtherRequests() {
        book.add(alice, bob, Type.TPA);
        book.add(alice, carol, Type.TPA);
        book.take(bob, Optional.of(alice)).orElseThrow();
        assertTrue(book.hasPending(alice, carol, Type.TPA));
    }

    @Test
    void newRequestReplacesOldOneForSamePair() {
        book.add(alice, bob, Type.TPA);
        book.add(alice, bob, Type.TPAHERE);
        assertEquals(1, book.size());
        assertFalse(book.hasPending(alice, bob, Type.TPA));
        assertTrue(book.hasPending(alice, bob, Type.TPAHERE));
    }

    @Test
    void travellerAndDestination() {
        Request tpa = book.add(alice, bob, Type.TPA);
        assertEquals(alice, tpa.traveller());
        assertEquals(bob, tpa.destination());
        Request here = book.add(carol, bob, Type.TPAHERE);
        assertEquals(bob, here.traveller());
        assertEquals(carol, here.destination());
    }

    @Test
    void cancelAndQuit() {
        book.add(alice, bob, Type.TPA);
        book.add(alice, carol, Type.TPA);
        assertEquals(bob, book.cancel(alice, Optional.of(bob)).orElseThrow().receiver());
        assertEquals(Optional.empty(), book.cancel(bob, Optional.empty()));
        book.add(bob, carol, Type.TPA);
        assertEquals(1, book.removeAll(alice).size());
        assertEquals(1, book.size());
        assertEquals(List.of(), book.outgoing(alice));
        assertEquals(1, book.incoming(carol).size());
    }
}
