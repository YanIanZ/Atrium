package dev.yanianz.atrium.module.playerplus;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/playerplus.yml */
@ConfigSerializable
public class PlayerPlusConfig {
    @Comment("Temporary flight (/tfly) settings.")
    public Tfly tfly = new Tfly();

    @Comment("Charged flight (/flyc, /flightcharge) settings.")
    public FlightCharge flightCharge = new FlightCharge();

    @Comment("Cuff (/cuff, /uncuff) settings.")
    public Cuff cuff = new Cuff();

    @Comment("Pet gifting (/giftpet) settings.")
    public GiftPet giftPet = new GiftPet();

    @Comment("Hunger and saturation limits.")
    public Food food = new Food();

    @ConfigSerializable
    public static class Tfly {
        @Comment("Longest duration /tfly accepts, in seconds. Durations above the limit are refused rather\n"
                + "than silently shortened.")
        public long maxSeconds = 86_400;

        @Comment("Seconds before the end at which the player is warned and the flight timer is visible.")
        public int warningSeconds = 10;

        @Comment("Seconds of slow falling granted when the temporary flight ends, so the player lands safely.")
        public int graceSeconds = 5;
    }

    @ConfigSerializable
    public static class FlightCharge {
        @Comment("Enable charged flight. When false, /flyc, /flightcharge and /recharge are refused.")
        public boolean enabled = true;

        @Comment("Maximum stored flight time in seconds.")
        public long maxSeconds = 3_600;

        @Comment("Flight time a player starts with the first time the module sees them, in seconds.")
        public long startingSeconds = 0;

        @Comment("Flight time deducted every second while the player is actually flying.")
        public long consumePerSecond = 1;

        @Comment("Currency used by a plain /flightcharge buy and by auto-recharge: MONEY or EXP.")
        public Currency currency = Currency.MONEY;

        @Comment("Money charged per second of flight (EconomyService). A value of 0 disables money purchases.")
        public double moneyPerSecond = 10.0;

        @Comment("Experience points charged per second of flight. Partial points are rounded up, so a\n"
                + "fractional price can never be paid with less than its value. 0 disables exp purchases.")
        public double expPerSecond = 1.0;

        @Comment("Seconds bought by /flightcharge buy without an amount, and by auto-recharge.")
        public long defaultPurchaseSeconds = 300;

        @Comment("Default of /autorecharge for a player who has never toggled it.")
        public boolean autoRechargeDefault = false;

        @Comment("Auto-recharge when the remaining charge drops to this many seconds. 0 disables the early top-up.")
        public long autoRechargeBelowSeconds = 60;

        @Comment("Minimum milliseconds between two automatic purchase attempts.")
        public long autoRechargeCooldownMillis = 10_000;
    }

    /** Currency of a flight-charge purchase. */
    public enum Currency {
        MONEY,
        EXP
    }

    @ConfigSerializable
    public static class Cuff {
        @Comment("Radius in blocks around the cuff point a cuffed player may not leave.")
        public double radius = 2.0;

        @Comment("Commands a cuffed player may still use. Matched word by word: 'msg' allows /msg and\n"
                + "/minecraft:msg but not /msgtoggle; 'mail send' allows that sub command only. A leading\n"
                + "slash and upper case are ignored.")
        public List<String> allowedCommands = new ArrayList<>(List.of(
                "msg", "tell", "w", "whisper", "m", "t", "pm", "r", "reply", "help", "helpop", "rules", "ping"));

        @Comment("Minimum milliseconds between two 'you are cuffed' notices, so movement spam cannot flood chat.")
        public long noticeCooldownMillis = 2_000;
    }

    @ConfigSerializable
    public static class GiftPet {
        @Comment("Seconds after /giftpet in which the owner must right-click the pet.")
        public int windowSeconds = 30;
    }

    @ConfigSerializable
    public static class Food {
        @Comment("Highest food level /hunger accepts (vanilla is 20).")
        public int maxFoodLevel = 20;

        @Comment("Highest saturation /saturation accepts (vanilla is 20).")
        public float maxSaturation = 20f;
    }
}
