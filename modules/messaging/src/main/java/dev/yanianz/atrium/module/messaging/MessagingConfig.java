package dev.yanianz.atrium.module.messaging;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/messaging.yml */
@ConfigSerializable
public class MessagingConfig {
    @Comment("Longest MiniMessage accepted by /actionbarmsg and /ctellraw (1-8192 characters).")
    public int maxAdminMessageLength = 1024;

    @Comment("Seconds an action bar stays visible when no -t:<duration> flag is given (1-3600).")
    public int actionBarDefaultSeconds = 5;

    @Comment("Longest duration accepted by /actionbarmsg -t:<duration>, in seconds (1-3600).\n"
            + "Longer values are clamped to this limit.")
    public int actionBarMaxSeconds = 300;

    @Comment("Ticks between action bar re-sends while a duration is running (1-200).\n"
            + "20 ticks = once per second. Nothing is scheduled when no message is active.")
    public int actionBarIntervalTicks = 20;

    @Comment("Seconds a player must wait between /helpop requests (0-86400). 0 disables the limit.\n"
            + "Players with atrium.helpop.bypass.cooldown are exempt.")
    public int helpOpCooldownSeconds = 30;

    @Comment("Longest /helpop message (1-2048 characters).")
    public int helpOpMaxLength = 256;

    @Comment("Show staff a clickable /tp button on local helpop requests when they have atrium.tp.")
    public boolean helpOpClickableTeleport = true;

    @Comment("Longest reason stored by /alert add (1-2048 characters).")
    public int alertMaxReasonLength = 256;

    @Comment("Days an alert is kept. 0 = until removed.")
    public int alertExpiryDays = 0;

    @Comment("Alerts shown per page by /alert list (1-50).")
    public int alertListPageSize = 10;

    @Comment("Delete expired alerts from the database every this many minutes (1-1440).")
    public int alertPurgeIntervalMinutes = 60;

    @Comment("Absolute date format used by /alert list (java.time DateTimeFormatter pattern).")
    public String dateFormat = "yyyy-MM-dd HH:mm";

    @Comment("Time zone of absolute dates (e.g. Asia/Jakarta, UTC); empty = the server's time zone.")
    public String timeZone = "";

    @Comment("Name shown for console senders in helpop and alert records.")
    public String consoleName = "Console";

    int clampAdminLength() {
        return Math.max(1, Math.min(8192, maxAdminMessageLength));
    }

    int clampActionBarDefault() {
        return Math.max(1, Math.min(3600, actionBarDefaultSeconds));
    }

    int clampActionBarMax() {
        return Math.max(1, Math.min(3600, actionBarMaxSeconds));
    }

    int clampActionBarInterval() {
        return Math.max(1, Math.min(200, actionBarIntervalTicks));
    }

    int clampHelpOpCooldown() {
        return Math.max(0, Math.min(86_400, helpOpCooldownSeconds));
    }

    int clampHelpOpLength() {
        return Math.max(1, Math.min(2048, helpOpMaxLength));
    }

    int clampAlertReasonLength() {
        return Math.max(1, Math.min(2048, alertMaxReasonLength));
    }

    int clampAlertPageSize() {
        return Math.max(1, Math.min(50, alertListPageSize));
    }

    int clampAlertPurgeMinutes() {
        return Math.max(1, Math.min(1440, alertPurgeIntervalMinutes));
    }

    int alertExpiryDaysClamped() {
        return Math.max(0, Math.min(36_500, alertExpiryDays));
    }
}
