package dev.yanianz.atrium.module.kits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Kit group weights. Several kits can share a group (for example {@code vip-1} and {@code vip-2}): only the
 * highest weight the player may actually use is offered, and claiming a lower one is refused with the name of
 * the higher kit. A kit flagged {@link Kit#showDespiteWeight()} stays in the list even when it is outranked.
 *
 * <p>Pure logic over kit definitions, tested without a server.
 */
final class KitGroups {
    private KitGroups() {}

    /**
     * The kit the player must claim when they ask for {@code requested}: itself when it is ungrouped or is the
     * highest accessible weight of its group, otherwise the kit that outranks it.
     */
    static Optional<Kit> resolve(Kit requested, List<Kit> accessible) {
        if (requested.group().isEmpty()) {
            return Optional.of(requested);
        }
        Kit best = requested;
        for (Kit candidate : accessible) {
            if (!candidate.group().equals(requested.group())) {
                continue;
            }
            if (candidate.weight() > best.weight()) {
                best = candidate;
            }
        }
        return Optional.of(best);
    }

    /**
     * Kits a player should see: every ungrouped kit, the highest accessible weight per group, and any kit
     * flagged {@code showDespiteWeight}. {@code accessible} must already be filtered by permission.
     * Order is preserved.
     */
    static List<Kit> visible(List<Kit> accessible) {
        Map<String, Integer> bestWeight = new LinkedHashMap<>();
        for (Kit kit : accessible) {
            if (kit.group().isEmpty()) {
                continue;
            }
            bestWeight.merge(kit.group(), kit.weight(), Math::max);
        }
        List<Kit> out = new ArrayList<>(accessible.size());
        for (Kit kit : accessible) {
            if (kit.group().isEmpty() || kit.showDespiteWeight()
                    || kit.weight() == bestWeight.getOrDefault(kit.group(), kit.weight())) {
                out.add(kit);
            }
        }
        return List.copyOf(out);
    }

    /** Groups of the given kits, in name order (for command suggestions). */
    static List<String> groups(List<Kit> kits) {
        return kits.stream()
                .map(Kit::group)
                .filter(group -> !group.isEmpty())
                .distinct()
                .sorted()
                .toList();
    }
}
