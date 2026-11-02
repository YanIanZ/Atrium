package dev.yanianz.atrium.module.mail;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/mail.yml */
@ConfigSerializable
public class MailConfig {
    @Comment("Maximum stored messages per mailbox (1-9999). New mail is refused when the mailbox is full.")
    public int mailboxLimit = 50;

    @Comment("Maximum characters in one mail body (1-8192). Longer messages are rejected.")
    public int maxBodyLength = 500;

    @Comment("Messages shown per page by /mail read (1-50).")
    public int pageSize = 6;

    @Comment("Seconds a player must wait between sending mail. 0 disables the limit.\n"
            + "Console senders and players with atrium.mail.bypass.cooldown are exempt.")
    public int sendCooldownSeconds = 3;

    @Comment("Days a normal /mail send message is kept. 0 = never expires.")
    public int defaultExpiryDays = 0;

    @Comment("Longest duration accepted by /mail sendtemp, in days (1-3650). Longer values are clamped.")
    public int maxTempDays = 365;

    @Comment("Name shown as the sender of console and /mail sendall mail.")
    public String consoleSenderName = "Console";

    @Comment("Delete expired mail from the database every this many minutes (1-1440).")
    public int purgeIntervalMinutes = 30;

    @Comment("Also deliver /mail sendall to known offline players. When false only online players receive it.")
    public boolean sendAllIncludesOffline = true;

    @Comment("Maximum recipients resolved by one /mail sendall (1-10000).")
    public int sendAllMaxRecipients = 500;

    @Comment("Absolute date format (java.time DateTimeFormatter pattern, e.g. yyyy-MM-dd HH:mm).")
    public String dateFormat = "yyyy-MM-dd HH:mm";

    @Comment("Time zone of absolute dates (e.g. Asia/Jakarta, UTC); empty = the server's time zone.")
    public String timeZone = "";
}
