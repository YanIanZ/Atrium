package dev.yanianz.atrium.module.player;

import java.util.Map;

/** Descriptions shown by /atrium help and the generated docs for the commands of this module. */
final class CommandDescriptions {
    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("feed", "Restore the hunger of yourself or another player"),
            Map.entry("god", "Toggle god mode (no damage) for yourself or another player"),
            Map.entry("extinguish", "Put out the fire on yourself or another player"),
            Map.entry("suicide", "Kill yourself or another player"),
            Map.entry("hat", "Wear the held item as a hat"),
            Map.entry("lightning", "Strike lightning at your location or at another player"),
            Map.entry("gamemode", "Change the game mode of yourself or another player"),
            Map.entry("gmc", "Switch to creative mode"),
            Map.entry("gms", "Switch to survival mode"),
            Map.entry("gma", "Switch to adventure mode"),
            Map.entry("gmsp", "Switch to spectator mode"),
            Map.entry("speed", "Set the walking or flying speed (0-10)"),
            Map.entry("repair", "Repair the held item or the whole inventory"),
            Map.entry("burn", "Set a player on fire for a number of seconds"),
            Map.entry("exp", "Show, give, set or take experience points or levels"),
            Map.entry("workbench", "Open a portable crafting table"),
            Map.entry("anvil", "Open a portable anvil"),
            Map.entry("grindstone", "Open a portable grindstone"),
            Map.entry("stonecutter", "Open a portable stonecutter"),
            Map.entry("loom", "Open a portable loom"),
            Map.entry("cartographytable", "Open a portable cartography table"),
            Map.entry("smithingtable", "Open a portable smithing table"),
            Map.entry("clear", "Clear items from your inventory or, after confirming, another player's"),
            Map.entry("near", "List the visible players near you"),
            Map.entry("skull", "Get the head of a player"),
            Map.entry("more", "Fill the held stack, add a copy with /more clone, or set an oversized amount"),
            Map.entry("itemname", "Rename the held item"),
            Map.entry("lore", "Add, insert, set, remove or clear the lore lines of the held item"),
            Map.entry("falldistance", "Set the fall distance of yourself or another player"),
            Map.entry("tgod", "Enable god mode for a limited number of seconds"),
            Map.entry("sound", "Play a sound for yourself, one player or everyone"),
            Map.entry("smite", "Strike lightning at yourself or another player, or a safe effect"),
            Map.entry("solve", "Evaluate an arithmetic expression"),
            Map.entry("shoot", "Launch a projectile in the direction you are looking"),
            Map.entry("enderchest", "Open your ender chest or another player's, live or from their last saved snapshot"),
            Map.entry("invsee", "Inspect or edit a player's inventory, live or from their last saved snapshot"));

    private CommandDescriptions() {
    }

    /** The description of a command of this module; fails fast for an undocumented command. */
    static String of(String command) {
        String description = DESCRIPTIONS.get(command);
        if (description == null) {
            throw new IllegalArgumentException("No description for /" + command);
        }
        return description;
    }

    static java.util.Set<String> commands() {
        return DESCRIPTIONS.keySet();
    }
}
