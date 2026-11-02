package dev.yanianz.atrium.module.commandrules;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** A validated rule for one canonical command root. {@code configuredRoot} is what the admin wrote. */
public record CommandRule(String root, String configuredRoot, BigDecimal cost, int expLevels, int cooldownSeconds,
        int warmupSeconds, Set<String> blockedWorlds, String permission, boolean confirmCost, Set<String> aliases) {
    public CommandRule {
        blockedWorlds = Set.copyOf(blockedWorlds);
        permission = permission == null ? "" : permission;
        configuredRoot = configuredRoot == null || configuredRoot.isBlank() ? root : configuredRoot;
        aliases = aliases == null ? Set.of() : Set.copyOf(aliases);
    }

    public boolean hasMoneyCost() {
        return cost.signum() > 0;
    }

    public boolean hasCost() {
        return hasMoneyCost() || expLevels > 0;
    }

    public boolean blocksWorld(String worldName) {
        return worldName != null && blockedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
