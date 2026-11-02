package dev.yanianz.atrium.module.ranks;

import java.math.BigDecimal;

/** Requirement values for one rank at one point in time. Pure so progress formatting is testable. */
record RankProgress(RankLadder.Rank rank, long playtimeSeconds, BigDecimal money, boolean permission) {
    boolean playtimeMet() {
        return playtimeSeconds >= rank.playtimeSeconds();
    }

    boolean moneyMet() {
        return money.compareTo(rank.money()) >= 0;
    }

    boolean permissionMet() {
        return rank.permission().isEmpty() || permission;
    }

    boolean met() {
        return playtimeMet() && moneyMet() && permissionMet();
    }

    /** 0..100, the arithmetic mean of the requirements that exist. */
    int percent() {
        int total = 0;
        int parts = 0;
        if (rank.playtimeSeconds() > 0) {
            total += (int) Math.min(100, playtimeSeconds * 100 / rank.playtimeSeconds());
            parts++;
        }
        if (rank.money().signum() > 0) {
            int moneyPercent = money.max(BigDecimal.ZERO).multiply(BigDecimal.valueOf(100))
                    .divide(rank.money(), 0, java.math.RoundingMode.DOWN).intValue();
            total += Math.min(100, moneyPercent);
            parts++;
        }
        if (!rank.permission().isEmpty()) {
            total += permission ? 100 : 0;
            parts++;
        }
        return parts == 0 ? 100 : Math.min(100, total / parts);
    }
}
