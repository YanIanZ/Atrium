package dev.yanianz.atrium.module.kits;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/kits.yml (kits themselves are stored in the database, edited in game). */
@ConfigSerializable
public class KitsConfig {
    @Comment("What happens when a kit does not fit: DROP = drop the overflow at the player's feet; REFUSE = do not give (and do not charge) the kit.")
    public String overflowMode = "DROP";

    @Comment("Ticks to wait after a first join before giving first-join kits (lets other join handlers run first).")
    public int firstJoinDelayTicks = 20;

    @Comment("Show kits that are on cooldown or already claimed in /kits (greyed out).")
    public boolean listUnavailable = true;

    @Comment("Rows of the /kitpreview browser, 3 to 6 (the bottom row holds the page buttons).")
    public int previewRows = 6;

    @Comment("Seconds a player has to repeat /kitcdreset or /kitusagereset with 'all' to confirm the bulk reset.")
    public int resetConfirmationSeconds = 30;
}
