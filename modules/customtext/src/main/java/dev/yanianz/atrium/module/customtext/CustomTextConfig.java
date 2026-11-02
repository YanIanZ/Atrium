package dev.yanianz.atrium.module.customtext;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import java.util.ArrayList;
import java.util.List;

/** plugins/Atrium/modules/customtext.yml (the text files live in plugins/Atrium/modules/customtext/). */
@ConfigSerializable
public class CustomTextConfig {
    @Comment("Send the texts marked 'join: true' when a player joins.")
    public boolean joinTexts = true;

    @Comment("Ticks to wait after the join before those texts are sent (clamped to 0-1200).\n"
            + "They are sent on the player's own thread, after the join screen has closed.")
    public int joinDelayTicks = 20;

    @Comment("Lines per page for texts that use the <AutoPage> directive (1-200).")
    public int autoPageLines = 10;

    @Comment("Broadcast for players who join the server for the first time.")
    public FirstJoin firstJoin = new FirstJoin();

    int autoPageLines() {
        return Math.max(1, Math.min(200, autoPageLines));
    }

    @ConfigSerializable
    public static class FirstJoin {
        @Comment("Broadcast a message when somebody joins for the first time. Off by default.")
        public boolean enabled = false;

        @Comment("Lines sent to everyone. [totalUsers] is the number of accounts Atrium knows, [online] the\n"
                + "number of players online. <player>, <online> and <total> also work. Theme tags are allowed.")
        public List<String> message = new ArrayList<>(List.of(
                "<prefix><success><player></highlight> joined for the first time! "
                        + "<muted>You are number <highlight>[totalUsers]</highlight> and <highlight>[online]</highlight> "
                        + "player(s) are online."));
    }
}
