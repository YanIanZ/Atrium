package dev.yanianz.atrium.module.customtext;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/customtext.yml (the text files live in plugins/Atrium/modules/customtext/). */
@ConfigSerializable
public class CustomTextConfig {
    @Comment("Send the texts marked 'join: true' when a player joins.")
    public boolean joinTexts = true;

    @Comment("Ticks to wait after the join before those texts are sent (clamped to 0-1200).\n"
            + "They are sent on the player's own thread, after the join screen has closed.")
    public int joinDelayTicks = 20;
}
