package dev.yanianz.atrium.module.discord;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** A configured relay destination. The staff channel is fed by {@code StaffChatEvent}. */
public enum DiscordChannel {
    CHAT("chat"),
    STAFF("staff"),
    JOINS("joins"),
    DEATHS("deaths"),
    PUNISHMENTS("punishments");

    private final String key;

    DiscordChannel(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    public String webhook(DiscordConfig config) {
        return switch (this) {
            case CHAT -> config.chatWebhook;
            case STAFF -> config.staffWebhook;
            case JOINS -> config.joinsWebhook;
            case DEATHS -> config.deathsWebhook;
            case PUNISHMENTS -> config.punishmentsWebhook;
        };
    }

    public static List<String> keys() {
        return List.of(CHAT.key, STAFF.key, JOINS.key, DEATHS.key, PUNISHMENTS.key);
    }

    public static Optional<DiscordChannel> byKey(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        for (DiscordChannel channel : values()) {
            if (channel.key.equals(normalized)) {
                return Optional.of(channel);
            }
        }
        return Optional.empty();
    }
}
