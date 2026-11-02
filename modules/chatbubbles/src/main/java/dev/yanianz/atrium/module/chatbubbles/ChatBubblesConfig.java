package dev.yanianz.atrium.module.chatbubbles;

import java.util.LinkedHashMap;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/chatbubbles.yml */
@ConfigSerializable
public class ChatBubblesConfig {
    @Comment("Show a floating text display above a player after their chat message.")
    public boolean enabled = true;

    @Comment("""
            How the bubble is rendered:
              auto    - packets when the packetevents plugin is enabled, otherwise follow
              packets - a client-side TextDisplay mounted on the speaker (zero lag, needs packetevents)
              follow  - a server TextDisplay moved every tick with velocity prediction
            """)
    public String mode = "auto";

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

    @Comment("Height of the bubble above the player's eyes, in blocks.")
    public double heightAboveEyes = 0.55;

    @Comment("Deprecated: older versions measured the height from the feet (default 2.15). When this key is\n"
            + "still present it is converted (minus the standing eye height 1.62) and height-above-eyes is ignored.\n"
            + "Remove it to use height-above-eyes.")
    public Double yOffset = null;

    /** Standing eye height used to convert the deprecated feet-relative y-offset. */
    static final double LEGACY_EYE_HEIGHT = 1.62;

    /** Effective height above the eyes, honouring a legacy feet-relative {@code y-offset}. */
    public double effectiveHeightAboveEyes() {
        if (yOffset != null && Double.isFinite(yOffset)) {
            return Math.max(0.1, yOffset - LEGACY_EYE_HEIGHT);
        }
        return heightAboveEyes;
    }

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

    @Comment("""
            Per-group bubble settings, matched by permission. A player gets the group with the highest
            priority whose permission they hold. Empty means duration-seconds and the default size for
            everyone. Example:
            groups:
              vip:
                permission: group.vip
                priority: 10
                show-time-seconds: 8
                scale: 1.2
            """)
    public Map<String, BubbleGroup> groups = new LinkedHashMap<>();

    /** One permission-based bubble group. A negative number means "use the flat setting". */
    @ConfigSerializable
    public static class BubbleGroup {
        @Comment("Permission node that grants this group. Blank makes it match everyone.")
        public String permission = "";

        @Comment("Highest priority wins when a player matches several groups.")
        public int priority = 0;

        @Comment("How long this group's bubbles stay visible, in seconds, 1-60 (-1 uses duration-seconds).")
        public int showTimeSeconds = -1;

        @Comment("Text scale of this group's bubbles, 0.1-4.0 (-1 keeps the default size).")
        public double scale = -1d;
    }
}
