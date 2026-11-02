package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.KnownWorld;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Tab completion of world names: every provider's worlds (loaded and loadable), prefix and permission filtered. */
final class WorldSuggestions {
    static final int LIMIT = 100;

    private WorldSuggestions() {}

    /**
     * Names (and single-word aliases) starting with {@code typed}, ignoring case, for worlds the sender may use,
     * sorted ignoring case, without duplicates, at most {@link #LIMIT}. Only valid names are offered.
     */
    static List<String> filter(Collection<KnownWorld> worlds, String typed, Predicate<String> allowed) {
        String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
        TreeSet<String> result = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (KnownWorld world : worlds) {
            if (!WorldNames.valid(world.name()) || !allowed.test(world.name())) {
                continue;
            }
            if (world.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(world.name());
            }
            if (world.hasAlias() && WorldNames.valid(world.alias())
                    && world.alias().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                result.add(world.alias());
            }
        }
        List<String> list = new ArrayList<>(result);
        return list.size() > LIMIT ? List.copyOf(list.subList(0, LIMIT)) : List.copyOf(list);
    }

    /** Page {@code page} (1-based, clamped) of {@code size} entries. */
    static <T> List<T> page(List<T> all, int page, int size) {
        int pages = pageCount(all.size(), size);
        int current = Math.max(1, Math.min(page, pages));
        int from = Math.min(all.size(), (current - 1) * size);
        int to = Math.min(all.size(), from + size);
        return List.copyOf(all.subList(from, to));
    }

    static int pageCount(int entries, int size) {
        return entries <= 0 || size <= 0 ? 1 : (entries + size - 1) / size;
    }
}
