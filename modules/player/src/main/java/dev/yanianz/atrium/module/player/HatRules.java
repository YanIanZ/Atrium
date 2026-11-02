package dev.yanianz.atrium.module.player;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure /hat filter rules. Server objects are reduced to booleans before they reach this class. */
final class HatRules {
    enum Result {
        ALLOW,
        NOT_WHITELISTED,
        ARMOUR,
        ENCHANTED,
        FILLED_SHULKER
    }

    record Filters(Set<String> whitelist, boolean blockArmour, boolean blockEnchanted,
            boolean blockFilledShulkers) {
        public Filters {
            whitelist = Set.copyOf(whitelist);
        }

        static Filters empty() {
            return new Filters(Set.of(), false, false, false);
        }

        static Filters from(List<String> whitelist, boolean blockArmour, boolean blockEnchanted,
                boolean blockFilledShulkers) {
            Set<String> keys = new LinkedHashSet<>();
            if (whitelist != null) {
                for (String entry : whitelist) {
                    String key = UtilityValues.materialKey(entry);
                    if (!key.isEmpty()) {
                        keys.add(key.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return new Filters(keys, blockArmour, blockEnchanted, blockFilledShulkers);
        }
    }

    private HatRules() { }

    static Result check(Filters filters, String materialKey, boolean armour, boolean enchanted,
            boolean filledShulker) {
        Filters rules = filters == null ? Filters.empty() : filters;
        if (!rules.whitelist().isEmpty()) {
            return rules.whitelist().contains(UtilityValues.materialKey(materialKey))
                    ? Result.ALLOW
                    : Result.NOT_WHITELISTED;
        }
        if (armour && rules.blockArmour()) {
            return Result.ARMOUR;
        }
        if (enchanted && rules.blockEnchanted()) {
            return Result.ENCHANTED;
        }
        if (filledShulker && rules.blockFilledShulkers()) {
            return Result.FILLED_SHULKER;
        }
        return Result.ALLOW;
    }
}
