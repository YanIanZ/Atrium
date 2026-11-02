package dev.yanianz.atrium.module.player;

import java.util.List;
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

    @Comment("Maximum stack size /more may set with atrium.more.oversize (clamped to 1-99). Without that\n"
            + "permission /more never exceeds the normal maximum stack size of the item.")
    public int maxOversizeStack = 64;

    @Comment("Reject armour items (helmets, chestplates, leggings and boots) in /hat.")
    public boolean hatBlockArmour = false;

    @Comment("Reject enchanted items in /hat.")
    public boolean hatBlockEnchanted = false;

    @Comment("Reject shulker boxes that still contain items in /hat, so a helmet cannot hide a container full\n"
            + "of items. Empty shulker boxes are always allowed.")
    public boolean hatBlockFilledShulkers = true;

    @Comment("Item whitelist for /hat. When this list is not empty only these materials may be worn as a hat\n"
            + "and the armour/enchanted filters above are skipped. Use material keys such as minecraft:pumpkin.")
    public List<String> hatWhitelist = List.of();

    @Comment("Maximum duration accepted by /tgod, in seconds (clamped to 1-31536000).")
    public int maxTimedGodSeconds = 86400;

    @Comment("Tell online staff (atrium.player.namechange.notify) when a player joins with a changed\n"
            + "Minecraft name. Data stays attached to the UUID.")
    public boolean nameChangeNotify = true;

    @Comment("Console commands run after a detected name change, ten ticks after the join. {old} and {new}\n"
            + "(also [oldname] and [newname]) are replaced after the names pass the [A-Za-z0-9_]{1,16} check.\n"
            + "Example: [\"mail send {new} Welcome back, {old}\"]")
    public List<String> nameChangeCommands = List.of();
}

