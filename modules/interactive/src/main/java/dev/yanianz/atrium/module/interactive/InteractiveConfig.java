package dev.yanianz.atrium.module.interactive;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/interactive.yml */
@ConfigSerializable
public class InteractiveConfig {
    @Comment("Minimum milliseconds between two triggers by the same player (stops double clicks and pressure-plate spam).\n"
            + "Bound clicks are still cancelled (when cancel-event is on) inside this window.")
    public long clickIntervalMillis = 250L;

    @Comment("Seconds a player has to click the block or entity after /ic bind or /ic unbind.")
    public int selectionTimeoutSeconds = 30;

    @Comment("Maximum number of action lines per interactive command.")
    public int maxActions = 100;

    @Comment("cancel-event for newly created interactive commands: the vanilla interaction (opening a chest, breaking\n"
            + "the block, trading with a villager) is cancelled when a binding is clicked.")
    public boolean defaultCancelEvent = true;

    @Comment("Run an interactive command when a player right-clicks a sign with a line that reads [ic:name].\n"
            + "The sign itself is the binding (nothing is stored) and the command's own permission still applies.\n"
            + "The other sign lines become {sign_line1}..{sign_line4} and {sign_args} in the action lines.")
    public boolean signCommands = true;
}
