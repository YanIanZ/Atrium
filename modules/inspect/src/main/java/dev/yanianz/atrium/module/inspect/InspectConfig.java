package dev.yanianz.atrium.module.inspect;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/inspect.yml */
@ConfigSerializable
public class InspectConfig {
    @Comment("Maximum distance in blocks for /blockinfo and /blocknbt (1-256).")
    public int maxBlockDistance = 50;

    @Comment("Maximum distance in blocks for /entityinfo and /entitynbt (1-128).")
    public int maxEntityDistance = 30;

    @Comment("Entries per page in /lastonline, /ops, /afkcheck, /checkaccount and /permreg (5-50).")
    public int pageSize = 10;

    @Comment("Maximum number of data components printed inline by /iteminfo (1-100).")
    public int maxInlineComponents = 12;

    @Comment("Maximum number of lines printed by /blocknbt, /entitynbt and /itemnbt (5-200).")
    public int maxDumpLines = 40;

    @Comment("Maximum characters of one copied/printed dump (256-32768).")
    public int maxDumpLength = 8192;

    @Comment("Format of absolute dates (java.time DateTimeFormatter pattern, e.g. yyyy-MM-dd HH:mm).")
    public String dateFormat = "yyyy-MM-dd HH:mm";

    @Comment("Time zone of absolute dates (e.g. Asia/Jakarta, UTC); empty = the server's time zone.")
    public String timeZone = "";

    int blockDistance() {
        return Math.max(1, Math.min(256, maxBlockDistance));
    }

    int entityDistance() {
        return Math.max(1, Math.min(128, maxEntityDistance));
    }

    int entriesPerPage() {
        return Math.max(5, Math.min(50, pageSize));
    }

    int inlineComponents() {
        return Math.max(1, Math.min(100, maxInlineComponents));
    }

    int dumpLines() {
        return Math.max(5, Math.min(200, maxDumpLines));
    }

    int dumpLength() {
        return Math.max(256, Math.min(32768, maxDumpLength));
    }
}
