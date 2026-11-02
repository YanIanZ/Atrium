package dev.yanianz.atrium.module.placeholders;

/**
 * Values read from one player on that player's own thread and then published to the cache.
 *
 * <p>Never read these from {@link org.bukkit.entity.Player} directly inside a PlaceholderAPI request:
 * PlaceholderAPI may call from an async thread. The display name is already plain and sanitised.
 */
record PlayerSnapshot(
        String displayName,
        double health,
        double maxHealth,
        int foodLevel,
        int experienceLevel,
        String gameMode,
        String world,
        int ping) {}
