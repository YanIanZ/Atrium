package dev.yanianz.atrium.module.economy;

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
}
