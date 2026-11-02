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
}
