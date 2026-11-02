package dev.yanianz.atrium.module.chatbubbles;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/chatbubbles.yml */
@ConfigSerializable
public class ChatBubblesConfig {
    @Comment("Show a floating text display above a player after their chat message.")
    public boolean enabled = true;

    @Comment("How long a bubble stays visible, in seconds (1-60).")
    public int durationSeconds = 6;

    @Comment("Maximum number of characters taken from the chat message (1-1024).")
    public int maxLength = 120;

    @Comment("Approximate characters per line before the bubble wraps (1-200).")
    public int charsPerLine = 28;

    @Comment("Maximum number of wrapped lines (1-6).")
    public int maxLines = 3;

    @Comment("Client-side line width in pixels passed to the TextDisplay (1-1000).")
    public int lineWidth = 200;

    @Comment("Height of the bubble above the player's feet, in blocks.")
    public double yOffset = 2.15;

    @Comment("Hide the bubble from the player who wrote the message.")
    public boolean hideFromSender = false;

    @Comment("Let the text show through blocks.")
    public boolean seeThrough = false;

    @Comment("Draw a text shadow.")
    public boolean shadowed = true;

    @Comment("Text colour: a named colour (white, yellow, ...) or #rrggbb.")
    public String textColor = "white";

    @Comment("Background colour as #aarrggbb; use #00000000 for no background.")
    public String backgroundColor = "#40000000";

    @Comment("Maximum distance at which viewers receive the display (1-128 blocks).")
    public float viewRange = 48f;
}
