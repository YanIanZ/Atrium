package dev.yanianz.atrium.module.messaging;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pure parsing for /bbroadcast and the server links. */
class BroadcastTest {
    @Test void serverFlagSelectsServers() {
        BroadcastFlags.Result result = BroadcastFlags.parse("-s:lobby,skyblock hello all", true);
        assertTrue(result.ok());
        assertEquals(List.of("lobby", "skyblock"), result.servers());
        assertEquals("hello all", result.text());
    }

    @Test void serverFlagIsDeduplicatedCaseInsensitively() {
        BroadcastFlags.Result result = BroadcastFlags.parse("-s:Lobby,lobby,survival hi", true);
        assertEquals(List.of("Lobby", "survival"), result.servers());
    }

    @Test void serverFlagNeedsItsPermission() {
        BroadcastFlags.Result result = BroadcastFlags.parse("-s:lobby hi", false);
        assertEquals(BroadcastFlags.Problem.SERVERS_NOT_ALLOWED, result.problem());
    }

    @Test void rejectsInvalidServerNamesAndBlankMessages() {
        assertEquals(BroadcastFlags.Problem.INVALID_SERVER, BroadcastFlags.parse("-s:lo/bby hi", true).problem());
        assertEquals(BroadcastFlags.Problem.BLANK, BroadcastFlags.parse("-s:lobby", true).problem());
        assertEquals(BroadcastFlags.Problem.BLANK, BroadcastFlags.parse("   ", true).problem());
        assertEquals(BroadcastFlags.Problem.NONE, BroadcastFlags.parse("hi there", false).problem());
    }

    @Test void payloadRoundTripsAndFiltersTargets() throws Exception {
        BroadcastPayload payload = new BroadcastPayload("lobby", "Admin", "hello", List.of("survival"));
        byte[] encoded = payload.encode();
        BroadcastPayload decoded = BroadcastPayload.decode(encoded).orElseThrow();
        assertEquals("lobby", decoded.server());
        assertEquals("Admin", decoded.sender());
        assertEquals("hello", decoded.message());
        assertFalse(decoded.targets("lobby"));
        assertTrue(decoded.targets("SURVIVAL"));
        assertTrue(new BroadcastPayload("lobby", "Admin", "hi", List.of()).targets("anything"));
        assertTrue(BroadcastPayload.decode(new byte[0]).isEmpty());
        assertTrue(BroadcastPayload.decode("garbage".getBytes()).isEmpty());
    }

    @Test void tagCommandsAreNormalised() {
        assertEquals("say hi", ConsoleClickTags.command(" /say hi "));
        assertEquals("say hi", ConsoleClickTags.command("/say\thi"));
        assertNull(ConsoleClickTags.command("   "));
        assertNull(ConsoleClickTags.command(null));
        assertNull(ConsoleClickTags.command("/" + "x".repeat(300)));
    }

    @Test void serverLinkUrlsAreChecked() {
        assertTrue(ServerLinksStore.Link.parseUrl("https://example.com").isPresent());
        assertTrue(ServerLinksStore.Link.parseUrl("example.com/store").isPresent());
        assertTrue(ServerLinksStore.Link.parseUrl("http://example.com").isPresent());
        assertTrue(ServerLinksStore.Link.parseUrl("ftp://example.com").isEmpty());
        assertTrue(ServerLinksStore.Link.parseUrl("javascript:alert(1)").isEmpty());
        assertTrue(ServerLinksStore.Link.parseUrl("https://").isEmpty());
        assertTrue(ServerLinksStore.Link.parseUrl(null).isEmpty());
        assertFalse(new ServerLinksStore.Link("", "https://example.com", "").valid());
        assertFalse(new ServerLinksStore.Link("x".repeat(49), "https://example.com", "").valid());
        assertTrue(new ServerLinksStore.Link("Website", "example.com", "website").valid());
    }
}
