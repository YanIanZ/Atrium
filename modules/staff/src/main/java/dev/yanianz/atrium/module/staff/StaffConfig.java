package dev.yanianz.atrium.module.staff;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/staff.yml */
@ConfigSerializable
public class StaffConfig {
    @Comment("What happens to items picked up, crafted or taken while staff mode is active.\n"
            + "CLEAR deletes them (default), KEEP moves them into free slots of the restored inventory\n"
            + "and DROP throws them on the ground when staff mode ends. Staff tools are always deleted.\n"
            + "On a restore at join (after a crash or quit) items are only merged when the inventory still\n"
            + "holds staff tools; otherwise it may be an older save and is replaced to prevent duplicates.")
    public ExtraItemsMode extraItemsOnExit = ExtraItemsMode.CLEAR;

    @Comment("Block item pickup while a player is in staff mode.")
    public boolean blockItemPickup = true;

    @Comment("Block dropping items while a player is in staff mode. Staff tools can never be dropped.")
    public boolean blockItemDrop = true;

    @Comment("Distance in blocks staff tools reach when targeting a player (1-32).")
    public int toolRange = 8;

    @Comment("Command names (without the slash) whose arguments are hidden by /commandspy.\n"
            + "Aliases and namespaced forms (plugin:login) of a listed command are masked too.\n"
            + "The command name is still shown, for example \"/login \u2022\u2022\u2022\".")
    public List<String> sensitiveCommands = new ArrayList<>(List.of(
            "login", "log", "l", "register", "reg", "changepassword", "changepass", "cp", "unregister",
            "2fa", "totp", "email", "msg", "tell", "w", "whisper", "pm", "m", "t", "dm", "r", "reply", "mail",
            "sudo"));

    @Comment("Commands (without the slash) refused while a player is in staff mode, so staff tools or items\n"
            + "cannot leave staff mode through another plugin (wear, sell, trade, store ...).\n"
            + "Aliases and namespaced forms of a listed command are refused too.")
    public List<String> blockedCommands = new ArrayList<>(List.of(
            "hat", "ah", "auction", "auctionhouse", "sell", "sellhand", "sellall", "trade", "market", "shop",
            "pv", "playervault", "vault", "backpack", "bp", "condense", "compact", "stack"));
}
