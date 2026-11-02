package dev.yanianz.atrium.module.deathmessages;

import java.util.Locale;

/** Kill/death counters of one player. */
public record DeathStats(long kills, long deaths) {
    public static final DeathStats EMPTY = new DeathStats(0, 0);

    /** Kills per death, counting zero deaths as one so a new player is not shown infinity. */
    public double ratio() {
        return (double) kills / Math.max(1L, deaths);
    }

    public String ratioText() {
        return String.format(Locale.ROOT, "%.2f", ratio());
    }
}
