package dev.yanianz.atrium.module.placeholders;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Reads Bukkit statistics on one player's own thread into a plain map for the placeholder cache. Only the
 * untyped counters are read by default; typed totals must be enabled explicitly because summing them walks
 * every material and entity.
 */
final class StatisticReader {
    private StatisticReader() {}

    static Map<String, Long> read(Player player, boolean typedTotals) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (StatisticsCatalog.Entry entry : StatisticsCatalog.entries()) {
            if (entry.subtyped()) {
                if (typedTotals) {
                    values.put(entry.key(), total(player, entry.statistic()));
                }
                continue;
            }
            try {
                values.put(entry.key(), (long) player.getStatistic(entry.statistic()));
            } catch (IllegalArgumentException e) {
                // A statistic this server treats differently; leaving it out makes the placeholder use its
                // fallback instead of reporting a wrong zero.
            }
        }
        return Map.copyOf(values);
    }

    private static long total(Player player, Statistic statistic) {
        long total = 0L;
        switch (statistic.getType()) {
            case ITEM, BLOCK -> {
                // No Material.isBlock()/isItem(): those calls may resolve a legacy type and stall the
                // player's thread. getStatistic rejects materials that do not belong to the statistic.
                for (Material material : Material.values()) {
                    total += value(player, statistic, material);
                }
            }
            case ENTITY -> {
                for (EntityType type : EntityType.values()) {
                    total += value(player, statistic, type);
                }
            }
            default -> {
                try {
                    total = player.getStatistic(statistic);
                } catch (IllegalArgumentException e) {
                    total = 0L;
                }
            }
        }
        return Math.min(Integer.MAX_VALUE, Math.max(0L, total));
    }

    private static long value(Player player, Statistic statistic, Material material) {
        try {
            return player.getStatistic(statistic, material);
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    private static long value(Player player, Statistic statistic, EntityType type) {
        try {
            return player.getStatistic(statistic, type);
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }
}
