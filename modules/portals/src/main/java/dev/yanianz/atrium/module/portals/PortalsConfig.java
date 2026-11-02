package dev.yanianz.atrium.module.portals;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/portals.yml */
@ConfigSerializable
public class PortalsConfig {
    @Comment("Item type of the selection wand handed out by /portal wand. Only wands given by that command\n"
            + "(they carry a hidden marker) select corners: left click = pos1, right click = pos2.")
    public String wandMaterial = "BLAZE_ROD";

    @Comment("Seconds before the same player can use any portal again (0 = no cooldown). Together with the\n"
            + "'inside' tracking this prevents players from being bounced between two portals.")
    public int reentryCooldownSeconds = 3;

    @Comment("Largest allowed portal side length in blocks (1-512). Keeps the chunk index small.")
    public int maxSideLength = 128;

    @Comment("Allow portal regions to overlap each other.")
    public boolean allowOverlap = false;

    @Comment("Also trigger a portal when a player lands inside it with an ender pearl or chorus fruit.\n"
            + "Command and plugin teleports never trigger portals.")
    public boolean triggerOnPearlTeleport = true;

    @Comment("Use the teleport module when it is enabled (records /back, honours its teleport event). The\n"
            + "portal teleport itself never has a warmup or cooldown; the re-entry cooldown above applies instead.")
    public boolean useTeleportService = true;

    @Comment("Search a safe spot near the destination before teleporting (needs the teleport module).")
    public boolean safeTeleport = false;

    @Comment("Tell players on the action bar when they lack the permission of a portal they walked into.")
    public boolean notifyNoPermission = true;

    @Comment("Particle outline shown to admins holding the wand.")
    public Outline outline = new Outline();

    @ConfigSerializable
    public static class Outline {
        @Comment("Show portal and selection outlines to admins holding the wand.")
        public boolean enabled = true;

        @Comment("Particle type (a particle that needs no extra data, e.g. END_ROD, HAPPY_VILLAGER, FLAME).")
        public String particle = "HAPPY_VILLAGER";

        @Comment("Only portals within this many blocks of the admin are drawn (4-128).")
        public int viewDistance = 48;

        @Comment("Ticks between redraws (5-100).")
        public int intervalTicks = 10;

        @Comment("Distance between particles along an edge in blocks (0.25-4).")
        public double spacing = 0.5;

        @Comment("Upper bound of particles sent to one admin per redraw (50-5000). Spacing grows when exceeded.")
        public int maxParticles = 600;
    }
}
