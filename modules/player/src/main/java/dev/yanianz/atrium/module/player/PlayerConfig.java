package dev.yanianz.atrium.module.player;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/player.yml */
@ConfigSerializable
public class PlayerConfig {
    @Comment("Keep flight enabled after rejoining when it was on when the player left.")
    public boolean restoreFlightOnJoin = true;

    @Comment("Also remove negative potion effects and fire when healing.")
    public boolean healClearsEffects = true;
    @Comment("Maximum duration accepted by /burn, in seconds (clamped to 1-107374182).")
    public int maxBurnSeconds = 3600;

    @Comment("Use harmless lightning effects instead of damaging lightning strikes.")
    public boolean lightningEffectOnly = false;
    @Comment("Default /near radius in blocks; numeric atrium.near.radius permissions can raise the limit.")
    public int nearRadius = 100;

    @Comment("Seconds to repeat an identical /clear command to confirm clearing another player (1-300).")
    public int clearConfirmationSeconds = 15;

    @Comment("Maximum input length for item names and each lore line (1-8192 characters).")
    public int maxItemTextLength = 256;
}

