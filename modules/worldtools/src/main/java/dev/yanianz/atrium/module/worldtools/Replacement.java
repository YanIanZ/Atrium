package dev.yanianz.atrium.module.worldtools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * Weighted replacement list like {@code stone%50+diamond_ore%5}. Entries are rolled in order; the first
 * that passes wins, so a block is replaced at most once. When no entry passes the block stays as it is: a
 * failed roll never turns into air.
 */
final class Replacement {
    record Choice<T>(T value, double chance) {
        Choice {
            if (!Double.isFinite(chance) || chance <= 0 || chance > 100) {
                throw new IllegalArgumentException("chance must be in (0, 100]: " + chance);
            }
        }
    }

    private Replacement() {}

    /** Parses {@code name[%chance]} entries separated by {@code +} or {@code ,}; chance defaults to 100. */
    static <T> List<Choice<T>> parse(String spec, Function<String, Optional<T>> resolver) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Empty replacement list");
        }
        List<Choice<T>> out = new ArrayList<>();
        for (String raw : spec.split("[+,]", -1)) {
            String entry = raw.trim();
            if (entry.isEmpty()) {
                throw new IllegalArgumentException("Empty entry in " + spec);
            }
            double chance = 100;
            int percent = entry.indexOf('%');
            String name = entry;
            if (percent >= 0) {
                name = entry.substring(0, percent).trim();
                try {
                    chance = Double.parseDouble(entry.substring(percent + 1).trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Bad chance in " + entry, e);
                }
            }
            String key = name.toLowerCase(Locale.ROOT);
            T value = resolver.apply(key).orElseThrow(() -> new IllegalArgumentException("Unknown entry " + key));
            out.add(new Choice<>(value, chance));
        }
        return List.copyOf(out);
    }

    /** First entry whose roll passes, or empty: the caller must then leave the block unchanged. */
    static <T> Optional<T> pick(List<Choice<T>> choices, RandomGenerator random) {
        for (Choice<T> choice : choices) {
            if (choice.chance() >= 100 || random.nextDouble() * 100 < choice.chance()) {
                return Optional.of(choice.value());
            }
        }
        return Optional.empty();
    }

    /** Distinct, well-mixed seed per chunk of one job (never one seed per millisecond). */
    static long chunkSeed(long jobSeed, int chunkX, int chunkZ) {
        long h = jobSeed ^ (((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
        h = (h ^ (h >>> 33)) * 0xff51afd7ed558ccdL;
        h = (h ^ (h >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return h ^ (h >>> 33);
    }
}
