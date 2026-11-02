package dev.yanianz.atrium.module.signs;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/signs.yml */
@ConfigSerializable
public class SignsConfig {
    @Comment("Enable sign colour/formatting, /signedit and the copy/paste commands.")
    public boolean enabled = true;

    @Comment("Apply formatting to text typed while placing or editing a sign.")
    public boolean applyOnPlace = true;

    @Comment("Master switch for colour tags (players still need atrium.signs.color).")
    public boolean allowColors = true;

    @Comment("Master switch for decoration tags (players still need atrium.signs.format).")
    public boolean allowDecorations = true;

    @Comment("Maximum plain-text length of one sign line; vanilla accepts at most 384 (1-384).")
    public int maxLineLength = 384;

    @Comment("Maximum distance in blocks when looking for a sign to edit, copy or paste (1-32).")
    public int reach = 8;

    @Comment("Also block sign text changes for muted and shadow-muted players.")
    public boolean blockMuted = true;

    @Comment("Enable dynamic signs: /dsign makes a sign scroll admin-written lines per player. The world block\n"
            + "is never changed; each viewer gets the lines as a packet.")
    public boolean dynamicSignsEnabled = true;

    @Comment("Ticks between two updates of the dynamic signs (minimum 5). Each update only sends to players\n"
            + "in range of a sign, on their own thread.")
    public int dynamicSignsIntervalTicks = 20;

    @Comment("How many dynamic signs may exist. A larger number is refused (1-2000).")
    public int dynamicSignsMax = 200;

    @Comment("Default distance in blocks a player sees a dynamic sign from (1-64).")
    public int dynamicSignsDefaultRange = 16;

    @Comment("Default scroll interval, e.g. 1s or 1500ms. The minimum is dynamic-signs-min-interval-millis.")
    public String dynamicSignsDefaultInterval = "1s";

    @Comment("Shortest accepted scroll interval in milliseconds (100-60000).")
    public long dynamicSignsMinIntervalMillis = 500;

    long dynamicSignsIntervalTicks() {
        return Math.max(5, Math.min(1200, dynamicSignsIntervalTicks));
    }

    long dynamicSignsMinIntervalMillis() {
        return Math.max(100L, Math.min(60_000L, dynamicSignsMinIntervalMillis));
    }

    int dynamicSignsMax() {
        return Math.max(1, Math.min(2000, dynamicSignsMax));
    }

    int dynamicSignsDefaultRange() {
        return Math.max(1, Math.min(64, dynamicSignsDefaultRange));
    }
}
