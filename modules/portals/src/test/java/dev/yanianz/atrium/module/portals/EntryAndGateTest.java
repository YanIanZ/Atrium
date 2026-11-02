package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EntryAndGateTest {
    private final UUID player = UUID.randomUUID();

    private static Portal portal(String name, String permission) {
        return new Portal(name, Cuboid.of("w", 0, 0, 0, 1, 1, 1), PortalTarget.NONE, null, permission, null, 0L);
    }

    @Test void triggersOnlyWhenEnteringNotWhileInsideOrLeaving() {
        EntryTracker tracker = new EntryTracker();
        assertEquals(List.of("a"), tracker.move(player, List.of("a"), List.of("a")));
        assertEquals(List.of(), tracker.move(player, List.of("a"), List.of("a")), "walking inside");
        assertEquals(List.of(), tracker.move(player, List.of("a"), List.of()), "leaving");
        assertFalse(tracker.isInsideAny(player));
        assertEquals(List.of("a"), tracker.move(player, List.of("a"), List.of("a")), "re-entering");
    }

    @Test void passingThroughAThinPortalTriggersWithoutStayingInside() {
        EntryTracker tracker = new EntryTracker();
        assertEquals(List.of("thin"), tracker.move(player, List.of("thin"), List.of()));
        assertTrue(tracker.inside(player).isEmpty());
    }

    @Test void destinationInsideAnotherPortalDoesNotPingPong() {
        EntryTracker tracker = new EntryTracker();
        assertEquals(List.of("a"), tracker.move(player, List.of("a"), List.of("a")));
        // Portal a sends the player into portal b; the teleport marks b as already inside.
        tracker.set(player, List.of("b"));
        assertEquals(List.of(), tracker.move(player, List.of("b"), List.of("b")), "moving inside b must not trigger");
        assertEquals(List.of(), tracker.move(player, List.of("b"), List.of()), "leaving b");
        assertEquals(List.of("b"), tracker.move(player, List.of("b"), List.of("b")), "deliberate re-entry");
    }

    @Test void gateUsesFirstAllowedPortalAndStartsCooldownOnce() {
        AtomicInteger acquired = new AtomicInteger();
        Portal locked = portal("a", "perm.a");
        Portal open = portal("b", null);
        PortalGate.Decision d = PortalGate.decide(List.of(locked, open), p -> p.permission() == null,
                () -> {
                    acquired.incrementAndGet();
                    return Duration.ZERO;
                });
        assertEquals(PortalGate.Outcome.USE, d.outcome());
        assertSame(open, d.portal());
        assertEquals(1, acquired.get());
    }

    @Test void deniedPortalDoesNotStartCooldown() {
        AtomicInteger acquired = new AtomicInteger();
        PortalGate.Decision d = PortalGate.decide(List.of(portal("a", "perm.a")), p -> false,
                () -> {
                    acquired.incrementAndGet();
                    return Duration.ZERO;
                });
        assertEquals(PortalGate.Outcome.DENIED, d.outcome());
        assertEquals(0, acquired.get());
        assertEquals(PortalGate.Outcome.NOTHING, PortalGate.decide(List.of(), p -> true, () -> Duration.ZERO).outcome());
    }

    @Test void cooldownBlocksRapidReuseBetweenTwoPortals() {
        // Fake atomic cooldown: 3 s, with a controllable clock.
        long[] now = {0};
        long[] expires = {Long.MIN_VALUE};
        java.util.function.Supplier<Duration> acquire = () -> {
            if (expires[0] > now[0]) {
                return Duration.ofMillis(expires[0] - now[0]);
            }
            expires[0] = now[0] + 3000;
            return Duration.ZERO;
        };
        EntryTracker tracker = new EntryTracker();
        Portal a = portal("a", null);
        Portal b = portal("b", null);
        List<PortalGate.Outcome> outcomes = new ArrayList<>();
        // Enter a, get sent next to b, step into b 1 s later, then again after the cooldown.
        tracker.move(player, List.of("a"), List.of("a"));
        outcomes.add(PortalGate.decide(List.of(a), p -> true, acquire).outcome());
        tracker.set(player, List.of());
        now[0] = 1000;
        assertEquals(List.of("b"), tracker.move(player, List.of("b"), List.of("b")));
        PortalGate.Decision blocked = PortalGate.decide(List.of(b), p -> true, acquire);
        outcomes.add(blocked.outcome());
        assertEquals(Duration.ofSeconds(2), blocked.remaining());
        tracker.move(player, List.of("b"), List.of());
        now[0] = 3500;
        tracker.move(player, List.of("b"), List.of("b"));
        outcomes.add(PortalGate.decide(List.of(b), p -> true, acquire).outcome());
        assertEquals(List.of(PortalGate.Outcome.USE, PortalGate.Outcome.COOLDOWN, PortalGate.Outcome.USE), outcomes);
    }
}
