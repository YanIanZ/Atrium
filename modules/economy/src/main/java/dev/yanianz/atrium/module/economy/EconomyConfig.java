package dev.yanianz.atrium.module.economy;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public class EconomyConfig {
    @Comment("Decimal places, 0 through 10. Restart after changing economy settings.")
    public int scale = 2;
    @Comment("java.math.RoundingMode used at API boundaries.")
    public String rounding = "HALF_UP";
    @Comment("Initial balance for new accounts. Reading a missing account never creates it.")
    public String startingBalance = "0";
    @Comment("Maximum balance; empty disables the policy cap (DECIMAL(30,10) storage limit still applies).")
    public String maxBalance = "";
    @Comment("Minimum balance; negative enables debt. Payments may spend down to this balance.")
    public String minBalance = "0";
    @Comment("Maximum single transaction amount (also enforced before rounding).")
    public String maxAmount = "99999999999999999999.9999999999";
    @Comment("Minimum /pay amount after rounding.")
    public String minimumPayment = "0.01";
    @Comment("Payments above this amount require repeating the identical command within 30 seconds.")
    public String confirmationThreshold = "10000";
    @Comment("Singular currency name.")
    public String currencySingular = "coin";
    @Comment("Plural currency name.")
    public String currencyPlural = "coins";
    @Comment("Currency symbol used by displayFormat.")
    public String symbol = "$";
    @Comment("Plain text display template: {amount}, {symbol}. ROOT locale grouping and decimal separators.")
    public String displayFormat = "{symbol}{amount}";
    @Comment("Minutes between asynchronous leaderboard snapshots; minimum 1.")
    public int leaderboardRefreshMinutes = 5;
    @Comment("Entries shown per /baltop page; 1 through 100.")
    public int pageSize = 10;
    @Comment("""
            Maximum wait in milliseconds for a synchronous Vault call. Reads for online players are answered from
            the cache warmed before login, and reads on a server tick thread never wait (they answer from the cache
            or 0 and load in the background). Only writes and offline lookups on regular plugin threads wait.
            A write on a tick thread, such as a SuperiorSkyblock2 profile switch through GuardedEconomy, waits at
            most this long for the commit: raising it lengthens the worst-case tick stall, lowering it makes a
            write that times out more likely, and a timed-out write has an unknown outcome. Do not retry blindly.""")
    public int vaultTimeoutMillis = 2000;

    @Comment("Tax taken from /pay transfers. Disabled by default: turning it on changes what players receive.")
    public Tax payTax = new Tax();

    @Comment("Tradable banknote items created by /cheque and redeemed by right-clicking them.")
    public Cheque cheque = new Cheque();

    @Comment("Filters for the /baltop snapshot.")
    public Baltop baltop = new Baltop();

    @ConfigSerializable
    public static class Tax {
        @Comment("Enable the /pay tax.")
        public boolean enabled = false;

        @Comment("""
                true  = the sender pays the amount plus the tax, the receiver gets the full amount.
                false = the sender pays the amount, the receiver gets the amount minus the tax.
                Tax money disappears (an economy sink) and is never credited to another account.""")
        public boolean deductOnTop = false;

        @Comment("Percentage component of the tax (0-100000000).")
        public String percent = "0";

        @Comment("Flat component of the tax, added to the percentage part.")
        public String flat = "0";

        @Comment("Lowest tax charged when a maximum above zero is configured.")
        public String minimum = "0";

        @Comment("Highest tax charged; 0 disables the clamp.")
        public String maximum = "0";
    }

    @ConfigSerializable
    public static class Cheque {
        @Comment("Allow /cheque. The money is withdrawn when the note is created, so a lost note is lost money.")
        public boolean enabled = true;

        @Comment("Item type of a cheque; it is always given as a single, non-stackable item.")
        public String material = "PAPER";

        @Comment("Largest amount one cheque may carry.")
        public String maximumAmount = "1000000";

        @Comment("Allow redeeming cheques while in creative mode. Off by default so a creative test world cannot mint money.")
        public boolean allowCreativeRedemption = false;
    }

    @ConfigSerializable
    public static class Baltop {
        @Comment("Names hidden from /baltop (case-insensitive). Useful for server-owned or fake accounts.")
        public List<String> excludedPlayers = new ArrayList<>();

        @Comment("""
                Hide accounts that have not joined for this many days. 0 disables the filter. Accounts without a
                recorded last join are hidden while the filter is active. The filter is applied to the periodic
                snapshot, so a change appears after the next refresh.""")
        public int inactiveDays = 0;
    }
}
