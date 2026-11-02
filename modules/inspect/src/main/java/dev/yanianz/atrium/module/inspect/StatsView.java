package dev.yanianz.atrium.module.inspect;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Reads Bukkit statistics on the owning player's thread.
 *
 * <p>Only untyped statistics have a direct value. Statistics with a material or entity subtype are summed
 * over the registry; that is a few thousand hash lookups, so the caller decides when it is worth it (the
 * {@code /stats} menu does it once, the placeholder cache only when enabled in the config).
 */
final class StatsView {
    record Row(StatisticsCatalog.Entry entry, long value) {}

    private StatsView() {}

    /** All statistics of one player; subtyped ones are skipped when {@code typedTotals} is false. */
    static List<Row> read(Player player, boolean typedTotals) {
        List<Row> rows = new ArrayList<>();
        for (StatisticsCatalog.Entry entry : StatisticsCatalog.entries()) {
            if (entry.subtyped()) {
                if (!typedTotals) {
                    continue;
                }
                rows.add(new Row(entry, total(player, entry.statistic())));
            } else {
                rows.add(new Row(entry, readUntyped(player, entry.statistic())));
            }
        }
        return List.copyOf(rows);
    }

    static long readUntyped(Player player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (IllegalArgumentException e) {
            // A statistic this server reports as typed even though the catalog expected otherwise.
            return 0L;
        }
    }

    /** Total across every subtype, clamped to the int range Bukkit stores. */
    static long total(Player player, Statistic statistic) {
        long total = 0L;
        switch (statistic.getType()) {
            case ITEM, BLOCK -> {
                // Try every material and let getStatistic reject the ones that do not belong. Material
                // classification calls (isBlock/isItem) can resolve a legacy type and stall the thread.
                for (Material material : Material.values()) {
                    total += subtypeValue(player, statistic, material);
                }
            }
            case ENTITY -> {
                for (EntityType type : EntityType.values()) {
                    total += entityValue(player, statistic, type);
                }
            }
            default -> total = readUntyped(player, statistic);
        }
        return Math.min(Integer.MAX_VALUE, Math.max(0L, total));
    }

    private static long subtypeValue(Player player, Statistic statistic, Material material) {
        try {
            return player.getStatistic(statistic, material);
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    private static long entityValue(Player player, Statistic statistic, EntityType type) {
        try {
            return player.getStatistic(statistic, type);
        } catch (IllegalArgumentException e) {
            return 0L;
        }
    }

    /** Current value of one statistic (typed statistics need the subtype); invalid combinations throw. */
    static int readStrict(Player player, Statistic statistic, Material material, EntityType entityType) {
        return switch (statistic.getType()) {
            case ITEM, BLOCK -> {
                if (material == null) {
                    throw new IllegalArgumentException("A material subtype is required");
                }
                yield player.getStatistic(statistic, material);
            }
            case ENTITY -> {
                if (entityType == null) {
                    throw new IllegalArgumentException("An entity subtype is required");
                }
                yield player.getStatistic(statistic, entityType);
            }
            default -> player.getStatistic(statistic);
        };
    }

    /** Writes one value; false when Bukkit rejected the material/entity subtype. */
    static boolean write(Player player, Statistic statistic, Material material, EntityType entityType, int value) {
        int safe = Math.max(0, Math.min(Integer.MAX_VALUE, value));
        try {
            switch (statistic.getType()) {
                case ITEM, BLOCK -> {
                    if (material == null) {
                        return false;
                    }
                    player.setStatistic(statistic, material, safe);
                }
                case ENTITY -> {
                    if (entityType == null) {
                        return false;
                    }
                    player.setStatistic(statistic, entityType, safe);
                }
                default -> player.setStatistic(statistic, safe);
            }
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
