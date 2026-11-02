package dev.yanianz.atrium.module.seen;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/seen.yml */
@ConfigSerializable
public class SeenConfig {
    @Comment("Format of absolute dates (java.time DateTimeFormatter pattern, e.g. yyyy-MM-dd HH:mm or dd/MM/yyyy HH:mm).")
    public String dateFormat = "yyyy-MM-dd HH:mm";

    @Comment("Time zone of absolute dates (e.g. Asia/Jakarta, UTC); empty = the server's time zone.")
    public String timeZone = "";

    @Comment("Also register /list as the grouped player list (replaces the vanilla /list). Needs a restart.")
    public boolean replaceListCommand = true;

    @Comment("Groups of /playerlist, in order. A player is listed in the first group whose permission they have.\n"
            + "Players matching no group are listed under the message seen.list-other.")
    public List<ListGroup> groups = new ArrayList<>(List.of(
            new ListGroup("<red>Staff", "atrium.playerlist.group.staff"),
            new ListGroup("<gray>Players", "")));
}
