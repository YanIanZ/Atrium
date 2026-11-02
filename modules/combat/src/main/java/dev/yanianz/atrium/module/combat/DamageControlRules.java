package dev.yanianz.atrium.module.combat;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Pure matching of the damage-control rules. */
final class DamageControlRules {
    private DamageControlRules() {}

    /**
     * Multiplier for one damage event. Rules are checked in order and the first match wins; a rule matches
     * when its cause, world and permissions match. No match means {@code 1.0}.
     */
    static double multiplier(List<CombatConfig.DamageRule> rules, String cause, String world,
            Predicate<String> hasPermission) {
        if (rules == null || rules.isEmpty()) {
            return 1.0;
        }
        for (CombatConfig.DamageRule rule : rules) {
            if (rule == null || !causeMatches(rule.cause, cause) || !worldMatches(rule.worlds, world)
                    || !permissionsMatch(rule.permissions, hasPermission)) {
                continue;
            }
            return clamp(rule.multiplier);
        }
        return 1.0;
    }

    /** A blank cause, {@code *} or {@code all} matches every cause. Names ignore case and dashes. */
    static boolean causeMatches(String ruleCause, String eventCause) {
        if (matchesAll(ruleCause)) {
            return true;
        }
        if (eventCause == null) {
            return false;
        }
        return normalize(ruleCause).equals(normalize(eventCause));
    }

    /** Whether the configured cause is a catch-all entry. */
    static boolean matchesAll(String ruleCause) {
        if (ruleCause == null || ruleCause.isBlank()) {
            return true;
        }
        String value = ruleCause.trim().toLowerCase(Locale.ROOT);
        return value.equals("*") || value.equals("all");
    }

    static boolean worldMatches(Collection<String> ruleWorlds, String world) {
        if (ruleWorlds == null || ruleWorlds.isEmpty()) {
            return true;
        }
        if (world == null) {
            return false;
        }
        for (String entry : ruleWorlds) {
            if (entry != null && entry.equalsIgnoreCase(world)) {
                return true;
            }
        }
        return false;
    }

    static boolean permissionsMatch(Collection<String> permissions, Predicate<String> hasPermission) {
        if (permissions == null || permissions.isEmpty()) {
            return true;
        }
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank() && hasPermission.test(permission)) {
                return true;
            }
        }
        return false;
    }

    /** Clamps a configured multiplier to the supported 0..100 range; NaN counts as unchanged. */
    static double clamp(double multiplier) {
        if (!Double.isFinite(multiplier)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(100.0, multiplier));
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
}
