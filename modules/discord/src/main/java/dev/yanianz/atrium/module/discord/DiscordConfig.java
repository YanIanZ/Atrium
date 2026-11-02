package dev.yanianz.atrium.module.discord;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/discord.yml */
@ConfigSerializable
public class DiscordConfig {
    @Comment("Webhook URL for public chat. Leave empty to disable. Only https://discord.com/api/webhooks/...,\n"
            + "https://discordapp.com/api/webhooks/... and https://canary.discord.com/api/webhooks/... are accepted.\n"
            + "The token is never written to the log; status output shows webhook#<last 4 digits of the id>.")
    public String chatWebhook = "";

    @Comment("Webhook URL for staff chat. Fed by the StaffChatEvent published by the chat module, so the same\n"
            + "filter, mute and shadow-mute rules apply. Leave empty to disable.")
    public String staffWebhook = "";

    @Comment("Webhook URL for player joins. Leave empty to disable.")
    public String joinsWebhook = "";

    @Comment("Webhook URL for player deaths. Leave empty to disable.")
    public String deathsWebhook = "";

    @Comment("Webhook URL for punishments and revokes. Leave empty to disable.")
    public String punishmentsWebhook = "";

    @Comment("Webhook name shown by Discord. {player} and {uuid} are replaced; player names are always truncated\n"
            + "to Discord's 80 character limit. Leave empty to keep the webhook's own name.")
    public String username = "{player}";

    @Comment("Avatar URL template. {uuid} and {player} are replaced. Only https:// URLs are sent.\n"
            + "Leave empty to keep the webhook's default avatar. No avatar is sent for console events.")
    public String avatarUrl = "https://mc-heads.net/avatar/{uuid}";

    @Comment("Chat template. {message} is the plain text of the final chat message (already formatted by the\n"
            + "chat module); {player} is the sender name. Both values are escaped before insertion.")
    public String chatFormat = "{message}";

    @Comment("Staff chat template. {message} and {player} are escaped before insertion; {server} is the origin\n"
            + "server for a relayed message and empty for a local one.")
    public String staffFormat = "**[STAFF]** {player}: {message}";

    @Comment("Join template. {player} is escaped before insertion.")
    public String joinFormat = "**{player}** joined the server.";
    @Comment("Quit template. {player} is escaped before insertion.")
    public String quitFormat = "**{player}** left the server.";

    @Comment("Death template. {message} is the plain death message (or \"<player> died\" when the server hides it);\n"
            + "{player} is the dead player's name.")
    public String deathFormat = "{message}";

    @Comment("Punishment template. Fields: {type} {target} {actor} {reason} {duration}; all escaped before insertion.")
    public String punishmentFormat = "**{type}** {target} by {actor}: {reason} ({duration})";

    @Comment("Revoke template. Fields: {type} {target} {actor} {id} {count}; all escaped before insertion.")
    public String revokeFormat = "**Revoked {type}** of {target} by {actor} ({count} record(s))";

    @Comment("Content of the message sent by /discord test <channel>.")
    public String testMessage = "Test message from Atrium.";

    @Comment("Relay quit messages on the joins webhook (silent quits of vanished players are always skipped).")
    public boolean relayQuits = true;

    @Comment("Also relay staff chat that arrived from another server. Keep false when every server has the same\n"
            + "staff webhook, otherwise one message appears once per server.")
    public boolean relayRemoteStaff = false;

    @Comment("Relay punishments marked as silent. Keep false so silent staff actions do not leak into a public\n"
            + "Discord channel.")
    public boolean relaySilentPunishments = false;

    @Comment("Maximum number of messages waiting for one webhook (1-1000). When full, the oldest message is dropped.")
    public int queueCapacity = 100;

    @Comment("Retries for 5xx responses and connection errors before a message is dropped (0-10).")
    public int maxRetries = 3;

    @Comment("Retries for a 429 response before the message is dropped (1-20).")
    public int maxRateLimitRetries = 5;

    @Comment("First retry delay in milliseconds; doubles on every retry (100-60000).")
    public long baseBackoffMillis = 1000;

    @Comment("Upper bound for retry backoff in milliseconds (must be at least baseBackoffMillis).")
    public long maxBackoffMillis = 30000;

    @Comment("Longest single wait requested by Discord rate-limit headers, in milliseconds (1000-900000).\n"
            + "This prevents a broken header from parking the queue forever.")
    public long maxRateLimitWaitMillis = 300000;

    @Comment("HTTP request timeout in seconds (1-60). Requests run on the I/O executor, never on a game thread.")
    public int requestTimeoutSeconds = 10;
}
