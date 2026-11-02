package dev.yanianz.atrium.module.vanish;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/vanish.yml */
@ConfigSerializable
public class VanishConfig {
    @Comment("Players who leave while vanished rejoin vanished (requires atrium.vanish at join).")
    public boolean keepVanishedOnRejoin = true;

    @Comment("Show a fake quit message when vanishing and a fake join message when reappearing to players who\n"
            + "cannot see vanished players (messages vanish.fake-quit / vanish.fake-join).")
    public boolean fakeMessages = true;

    @Comment("Tell players who can see the vanished player (and the console) that they vanished or reappeared.")
    public boolean notifyStaff = true;

    @Comment("Seconds between action bar reminders for vanished players (0 disables the reminder).")
    public int actionBarIntervalSeconds = 2;

    @Comment("Seconds between visibility re-checks, so permission or level changes apply without relogging (1-300).")
    public int resyncIntervalSeconds = 5;

    @Comment("Remove vanished players from the player count and sample of the server list.")
    public boolean hideFromServerList = true;

    @Comment("Also block experience orb pickup while item pickup is off (see /vanish pickup).")
    public boolean blockExperiencePickup = true;

    @Comment("Mobs do not target vanished players; current targets are cleared when vanishing.")
    public boolean preventMobTargeting = true;

    @Comment("Vanished players do not trigger pressure plates, tripwires, farmland or other physical interactions.")
    public boolean preventPhysicalInteraction = true;

    @Comment("Vanished players do not trigger sculk sensors, calibrated sculk sensors or sculk shriekers.")
    public boolean preventVibrations = true;

    @Comment("Vanished players do not count for sleeping through the night.")
    public boolean ignoreSleeping = true;

    @Comment("Vanished players do not cause mobs to spawn around them.")
    public boolean preventMobSpawning = true;

    @Comment("Hide advancement announcements of vanished players.")
    public boolean hideAdvancements = true;

    @Comment("Open chests, barrels and shulker boxes without animation or sound while vanished. The view is\n"
            + "read-only unless the player has atrium.vanish.silentchest.modify; edits apply to the real container.")
    public boolean silentContainers = true;
}
