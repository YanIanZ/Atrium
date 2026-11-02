package dev.yanianz.atrium.module.skins;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * plugins/Atrium/modules/skins.yml.
 *
 * <p>Applying a skin uses Paper's {@code Player#setPlayerProfile}, which re-sends the player: other players see
 * them disappear and reappear for a moment, and the player's own client gets a refresh (open screens close).
 */
@ConfigSerializable
public class SkinsConfig {
    @Comment("Hours a fetched skin texture stays in the cache before it is fetched again (clamped to 1-8760).")
    public int cacheHours = 24;

    @Comment("Delete cache rows older than this many days when the module starts (0 keeps them forever).")
    public int purgeAfterDays = 30;

    @Comment("Seconds to wait for the Mojang profile lookup before giving up (clamped to 1-60).")
    public int fetchTimeoutSeconds = 10;

    @Comment("Seconds a player must wait between /skin changes (0 disables). Bypass: atrium.skin.bypass-cooldown.")
    public int cooldownSeconds = 30;

    @Comment("Apply each player's chosen skin again when they join (uses the stored texture, no network request).")
    public boolean restoreOnJoin = true;

    @Comment("When a stored skin is older than cache-hours, fetch it again in the background after join "
            + "and apply it if the account changed its skin.")
    public boolean refreshStaleOnJoin = true;
}
