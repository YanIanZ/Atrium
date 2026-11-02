package dev.yanianz.atrium.module.homes;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/homes.yml */
@ConfigSerializable
public class HomesConfig {
    @Comment("Homes a player may have without a limit permission. Grant atrium.homes.<number> for more\n"
            + "(the highest one wins, never below this value) or atrium.homes.unlimited.")
    public int defaultLimit = 3;

    @Comment("Name used by /sethome and /home without a name.")
    public String defaultName = "home";

    @Comment("Let players use their bed or respawn anchor as a home (needs atrium.home.bed).")
    public boolean bedHome = true;

    @Comment("Name of the bed home in /home and /homes. Players cannot create a normal home with this name.")
    public String bedHomeName = "bed";

    @Comment("Worlds where /sethome is not allowed (bypass: atrium.home.bypass.worlds).")
    public List<String> noSetWorlds = new ArrayList<>();

    @Comment("Worlds whose homes cannot be teleported to (bypass: atrium.home.bypass.worlds).")
    public List<String> noTeleportWorlds = new ArrayList<>();

    @Comment("When a home is unsafe (lava, void, inside blocks), offer a clickable '/home <name> confirm'\n"
            + "that teleports to the exact spot anyway.")
    public boolean offerUnsafeConfirm = true;

    boolean blocked(List<String> worlds, String world) {
        return worlds != null && worlds.stream().anyMatch(w -> w != null && w.equalsIgnoreCase(world));
    }
}
