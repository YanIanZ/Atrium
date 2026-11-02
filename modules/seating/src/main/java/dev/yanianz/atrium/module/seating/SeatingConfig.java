package dev.yanianz.atrium.module.seating;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/seating.yml */
@ConfigSerializable
public class SeatingConfig {
    @Comment("Worlds where /sit, /lay, /crawl and click-to-sit are disabled. Players with\n"
            + "atrium.seating.worlds.bypass ignore this list.")
    public List<String> disabledWorlds = new ArrayList<>();

    @Comment("Enable /sit.")
    public boolean sit = true;

    @Comment("Enable /lay.\n"
            + "Minecraft only renders the sleeping pose for a player who is really in a bed. Atrium does not\n"
            + "send a fake bed (that needs NMS or a fragile protocol trick), so /lay uses the normal seat\n"
            + "entity moved down by seat.lay-depth. It looks like a lower, reclined seat and never traps the\n"
            + "player inside a block: when they stand up Atrium puts them back on the surface.")
    public boolean lay = true;

    @Comment("Enable /crawl. Crawling sends a client-side barrier block above the player's head so the\n"
            + "client uses the swimming pose and a one-block hitbox. Nothing is ever changed in the world.")
    public boolean crawl = true;

    @Comment("Right-clicking a stair or slab with an empty hand to sit down.")
    public Interaction interaction = new Interaction();

    @Comment("Seat entity behaviour and cleanup.")
    public Seat seat = new Seat();

    @Comment("Crawl behaviour.")
    public CrawlOptions crawlOptions = new CrawlOptions();

    @Comment("Ride behaviour (/ride).")
    public Ride ride = new Ride();

    @ConfigSerializable
    public static class Interaction {
        @Comment("Enable click-to-sit. Requires atrium.seating.interact.")
        public boolean enabled = true;

        @Comment("Only sit when the main hand is empty. Keep this on: with an item in hand the click would\n"
                + "otherwise have placed a block or used the item before Atrium could sit the player.")
        public boolean requireEmptyHand = true;

        @Comment("Allow sitting on stairs.")
        public boolean stairs = true;

        @Comment("Allow sitting on slabs. Only bottom slabs are seats; double and top slabs are not.")
        public boolean slabs = true;

        @Comment("Allow sitting on carpets.")
        public boolean carpets = false;

        @Comment("Do not sit while the player is sneaking. Sneaking is also the way out of a seat.")
        public boolean notWhileSneaking = true;
    }

    @ConfigSerializable
    public static class Seat {
        @Comment("Stand up when the seated player takes damage.")
        public boolean dismountOnDamage = true;

        @Comment("Stand up when the seated player starts sneaking.")
        public boolean dismountOnSneak = true;

        @Comment("Extra Y offset added to every seat, in blocks. Increase it slightly if players appear\n"
                + "to sink into the block; negative values lower the seat.")
        public double heightOffset = 0.0;

        @Comment("How far /lay lowers the seat below the surface, in blocks (0 through 1).\n"
                + "Players are moved back to the surface when they stand up.")
        public double layDepth = 0.25;

        @Comment("Seconds between consistency checks of active seats. 0 disables the check.\n"
                + "The check only looks at players who are sitting right now, so an idle server does no work.")
        public int checkIntervalSeconds = 10;
    }

    @ConfigSerializable
    public static class Ride {
        @Comment("Enable /ride (mount a player or the entity you look at, /ride stop, /ride launch).")
        public boolean enabled = true;

        @Comment("Allow riding other players. Requires atrium.seating.ride.players.")
        public boolean players = true;

        @Comment("How many players may ride the same vehicle at once (1-8).")
        public int maxPassengers = 3;

        @Comment("How far /ride looks for the entity in front of the player, in blocks (1-16).")
        public double reach = 5.0;

        @Comment("Whether a rider keeps its own fall damage after /ride launch.\n"
                + "false (default) clears it, so a launch cannot kill the rider.")
        public boolean launchFallDamage = false;

        int reach() {
            return (int) Math.round(Math.clamp(reach, 1.0, 16.0));
        }

        int maxPassengers() {
            return Math.clamp(maxPassengers, 1, 8);
        }
    }

    @ConfigSerializable
    public static class CrawlOptions {
        @Comment("Stop crawling when the player sneaks.")
        public boolean stopOnSneak = true;

        @Comment("Stop crawling when the player jumps.")
        public boolean stopOnJump = true;

        @Comment("Stop crawling when the player takes damage.")
        public boolean stopOnDamage = true;

        @Comment("Stop crawling when the player starts gliding or flying.")
        public boolean stopOnFlight = true;
    }
}
