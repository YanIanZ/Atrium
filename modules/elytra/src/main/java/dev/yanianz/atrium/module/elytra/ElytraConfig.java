package dev.yanianz.atrium.module.elytra;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/elytra.yml. Speeds are in blocks per tick (20 ticks = 1 second). */
@ConfigSerializable
public class ElytraConfig {
    @Comment("Worlds (by name) where neither the boost nor the launch works.")
    public List<String> disabledWorlds = new ArrayList<>();

    @Comment("Players with any of these permissions are never given the double-jump launch, because they may\n"
            + "legitimately fly (for example with /fly). Their flight state is never changed by this module.")
    public List<String> flyPermissions = new ArrayList<>(List.of("atrium.fly"));

    @Comment("Sneak while gliding to boost (needs atrium.elytra.boost).")
    public Boost boost = new Boost();

    @Comment("Double-jump while wearing an elytra to launch upward and start gliding (needs atrium.elytra.launch).")
    public Launch launch = new Launch();

    @ConfigSerializable
    public static class Boost {
        @Comment("Enable the sneak boost.")
        public boolean enabled = true;

        @Comment("Current velocity is multiplied by this (1-5).")
        public double multiplier = 1.5;

        @Comment("When the result is slower than this, the player is pushed along the look direction at this speed (0-5).")
        public double minimumSpeed = 0.8;

        @Comment("Hard speed cap in blocks per tick (0.5-10). 2.5 = 50 blocks per second. A player already at the\n"
                + "cap gets no boost; the cap is checked against the measured movement, not only the reported velocity.")
        public double maxSpeed = 2.5;

        @Comment("Milliseconds between two boosts of one player (0-60000).")
        public long cooldownMillis = 1500;
    }

    @ConfigSerializable
    public static class Launch {
        @Comment("Enable the double-jump launch. It briefly allows flight for eligible players on the ground, so\n"
                + "check your anti-cheat before enabling.")
        public boolean enabled = false;

        @Comment("Upward velocity of the launch in blocks per tick (0.1-4).")
        public double upwardVelocity = 1.4;

        @Comment("Forward velocity along the look direction in blocks per tick (0-4).")
        public double forwardVelocity = 0.5;

        @Comment("Milliseconds between two launches of one player (0-600000).")
        public long cooldownMillis = 3000;

        @Comment("Ticks after the launch before gliding starts (1-20).")
        public int glideDelayTicks = 4;
    }
}
