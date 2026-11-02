package dev.yanianz.atrium.module.mobs;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/mobs.yml */
@ConfigSerializable
public class MobsConfig {
    @Comment("Largest [amount] accepted by /spawnmob (1-500).")
    public int maxAmount = 25;

    @Comment("Most entities in one riding stack, e.g. spider+skeleton is 2 (1-10).")
    public int maxStackDepth = 3;

    @Comment("Most entities one /spawnmob may create in total: amount x stack depth (1-1000).")
    public int maxEntitiesPerCommand = 50;

    @Comment("/spawnmob never fills a chunk beyond this many entities (existing + new, 1-10000).")
    public int maxEntitiesPerChunk = 80;

    @Comment("Largest radius in blocks for /killall (1-512). Players must always give a radius.")
    public int killallMaxRadius = 128;

    @Comment("Radius around the console's location (the main world spawn) when /killall has none (1-512).")
    public int killallConsoleRadius = 64;

    @Comment("How far /mobinfo looks for an entity, in blocks (1-32).")
    public double mobinfoReach = 16;

    @Comment("How far /setspawner looks for a spawner, in blocks (1-16).")
    public double spawnerReach = 8;

    int maxAmount() {
        return clamp(maxAmount, 1, 500);
    }

    int maxStackDepth() {
        return clamp(maxStackDepth, 1, 10);
    }

    int maxEntitiesPerCommand() {
        return clamp(maxEntitiesPerCommand, 1, 1000);
    }

    int maxEntitiesPerChunk() {
        return clamp(maxEntitiesPerChunk, 1, 10000);
    }

    int killallMaxRadius() {
        return clamp(killallMaxRadius, 1, 512);
    }

    int killallConsoleRadius() {
        return clamp(killallConsoleRadius, 1, killallMaxRadius());
    }

    double mobinfoReach() {
        return Double.isFinite(mobinfoReach) ? Math.max(1, Math.min(32, mobinfoReach)) : 16;
    }

    double spawnerReach() {
        return Double.isFinite(spawnerReach) ? Math.max(1, Math.min(16, spawnerReach)) : 8;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
