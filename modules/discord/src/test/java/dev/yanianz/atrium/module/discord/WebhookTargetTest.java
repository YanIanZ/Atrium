package dev.yanianz.atrium.module.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WebhookTargetTest {
    private static final String ID = "123456789012345678";
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String PATH = "/api/webhooks/" + ID + "/" + TOKEN;

    @Test
    void acceptsEveryAllowedDiscordHost() {
        for (String host : new String[]{"discord.com", "discordapp.com", "canary.discord.com"}) {
            WebhookTarget target = WebhookTarget.parse("https://" + host + PATH);
            assertEquals("https://" + host + PATH, target.uri().toASCIIString());
            assertEquals(ID, target.id());
        }
    }

    @Test
    void canonicalisesHostCaseAndTrailingSlash() {
        WebhookTarget target = WebhookTarget.parse("https://DISCORD.COM" + PATH + "/");
        assertEquals("https://discord.com" + PATH, target.uri().toASCIIString());
    }

    @Test
    void maskUsesOnlyTheLastFourCharactersOfTheId() {
        WebhookTarget target = WebhookTarget.parse("https://discord.com" + PATH);
        assertEquals("webhook#5678", target.masked());
        assertFalse(target.masked().contains(TOKEN));
        assertFalse(target.toString().contains(TOKEN));
        assertEquals("webhook#12", WebhookTarget.mask("12"));
    }

    @Test
    void rejectsUnsafeOrMalformedUrls() {
        String[] invalid = {
            "",
            "   ",
            "http://discord.com" + PATH,
            "ftp://discord.com" + PATH,
            "https://evil.example" + PATH,
            "https://discord.com.evil.example" + PATH,
            "https://notdiscord.com" + PATH,
            "https://discord.com/api/webhooks",
            "https://discord.com/api/webhooks/",
            "https://discord.com/api/webhooks/" + ID,
            "https://discord.com/api/webhooks/" + ID + "/" + TOKEN + "/extra",
            "https://discord.com/api/hooks/" + ID + "/" + TOKEN,
            "https://discord.com:8443" + PATH,
            "https://user:pass@discord.com" + PATH,
            "https://discord.com" + PATH + "?wait=true",
            "https://discord.com" + PATH + "#fragment",
            "https://discord.com/api/webhooks/not-a-number/" + TOKEN,
            "https://discord.com/api/webhooks/" + ID + "/bad token",
            "https://discord.com/api/webhooks/" + ID + "/..",
            "https://discord.com/api/webhooks/" + ID + "/.",
            "not a url"
        };
        for (String url : invalid) {
            assertThrows(WebhookTarget.InvalidWebhookException.class, () -> WebhookTarget.parse(url), url);
        }
    }

    @Test
    void exceptionNeverEchoesTheUrlOrToken() {
        WebhookTarget.InvalidWebhookException error = assertThrows(WebhookTarget.InvalidWebhookException.class,
                () -> WebhookTarget.parse("http://discord.com" + PATH));
        assertFalse(error.getMessage().contains(TOKEN));
        assertFalse(error.getMessage().contains("://"));
        assertFalse(error.getMessage().contains("discord.com"));
    }

    @Test
    void acceptsDefaultHttpsPort() {
        WebhookTarget target = WebhookTarget.parse("https://discord.com:443" + PATH);
        assertEquals("https://discord.com" + PATH, target.uri().toASCIIString());
    }

    @Test
    void channelKeysAreStable() {
        assertTrue(DiscordChannel.byKey("CHAT").isPresent());
        assertEquals(DiscordChannel.PUNISHMENTS, DiscordChannel.byKey("punishments").orElseThrow());
        assertEquals(DiscordChannel.STAFF, DiscordChannel.byKey("staff").orElseThrow());
        assertTrue(DiscordChannel.keys().containsAll(
                java.util.List.of("chat", "staff", "joins", "deaths", "punishments")));
    }
}
