package dev.yanianz.atrium.module.npcs;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/npcs.yml */
@ConfigSerializable
public class NpcsConfig {
    @Comment("Backend: auto, native, citizens or fancynpcs. auto uses Citizens when enabled, then FancyNpcs, "
            + "then Paper's native Mannequin entity.")
    public String engine = "auto";

    @Comment("How long a resolved skin texture is trusted before it is looked up again (hours, 1-8760).")
    public int skinCacheHours = 168;

    @Comment("Profile lookup timeout in seconds (1-60). A timeout never hides an NPC; it only skips the skin.")
    public int skinTimeoutSeconds = 10;

    @Comment("Ticks between look-at updates while a player is within an NPC's range (5-100). "
            + "Each NPC stops its own timer when nobody is near; there is no global per-tick loop.")
    public int lookIntervalTicks = 10;

    @Comment("Maximum distance for /npc select, in blocks (2-32).")
    public double selectRange = 8;

    @Comment("Minimum view alignment for /npc select: 1.0 looks exactly at the NPC, 0.7 is about a 45 degree cone.")
    public double selectMinAlignment = 0.7;

    @Comment("Create a hologram nameplate through the holograms module when it is enabled. "
            + "When false (or the module is missing) the NPC's own custom name is shown instead.")
    public boolean nameplateHologram = true;

    @Comment("Height of the nameplate above the NPC's feet, in blocks (0.5-4).")
    public double nameplateHeight = 2.1;

    @Comment("Maximum number of action lines per click (1-64).")
    public int maxActionsPerClick = 32;

    @Comment("Default cooldown for a new NPC, in seconds. 0 disables it.")
    public int defaultCooldownSeconds = 0;

    @Comment("Log every skipped or failed click action. Useful while setting an NPC up.")
    public boolean logActionProblems = true;
}
