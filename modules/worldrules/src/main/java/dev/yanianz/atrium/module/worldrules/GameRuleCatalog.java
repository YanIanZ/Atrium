package dev.yanianz.atrium.module.worldrules;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.bukkit.GameRule;
import org.bukkit.GameRules;
import org.bukkit.World;
import org.slf4j.Logger;

/**
 * Names and types of every game rule known to this server. Both the modern registry key
 * ({@code advanceTime}) and the legacy Bukkit name ({@code doDaylightCycle}) resolve to the same rule,
 * so neither spelling is silently unknown.
 */
@SuppressWarnings({"removal", "deprecation"})
final class GameRuleCatalog {
    record Entry(GameRule<?> rule, String name, String legacyName) {}

    private final Map<String, Entry> byName = new HashMap<>();
    private final List<String> suggestions;

    /**
     * Legacy spellings kept by the vanilla command and by older plugins. The legacy constants still
     * point at the same underlying rules, so using them here preserves their original (sometimes
     * inverted) meaning instead of guessing from the modern key.
     */
    @SuppressWarnings({"removal", "deprecation"})
    private static final Map<String, GameRule<?>> LEGACY_ALIASES = Map.ofEntries(
            Map.entry("announceAdvancements", GameRule.ANNOUNCE_ADVANCEMENTS),
            Map.entry("commandBlockOutput", GameRule.COMMAND_BLOCK_OUTPUT),
            Map.entry("disablePlayerMovementCheck", GameRule.DISABLE_PLAYER_MOVEMENT_CHECK),
            Map.entry("disableElytraMovementCheck", GameRule.DISABLE_ELYTRA_MOVEMENT_CHECK),
            Map.entry("doDaylightCycle", GameRule.DO_DAYLIGHT_CYCLE),
            Map.entry("doEntityDrops", GameRule.DO_ENTITY_DROPS),
            Map.entry("doFireTick", GameRule.DO_FIRE_TICK),
            Map.entry("doLimitedCrafting", GameRule.DO_LIMITED_CRAFTING),
            Map.entry("projectilesCanBreakBlocks", GameRule.PROJECTILES_CAN_BREAK_BLOCKS),
            Map.entry("doMobLoot", GameRule.DO_MOB_LOOT),
            Map.entry("doMobSpawning", GameRule.DO_MOB_SPAWNING),
            Map.entry("doTileDrops", GameRule.DO_TILE_DROPS),
            Map.entry("doWeatherCycle", GameRule.DO_WEATHER_CYCLE),
            Map.entry("keepInventory", GameRule.KEEP_INVENTORY),
            Map.entry("logAdminCommands", GameRule.LOG_ADMIN_COMMANDS),
            Map.entry("mobGriefing", GameRule.MOB_GRIEFING),
            Map.entry("naturalRegeneration", GameRule.NATURAL_REGENERATION),
            Map.entry("reducedDebugInfo", GameRule.REDUCED_DEBUG_INFO),
            Map.entry("sendCommandFeedback", GameRule.SEND_COMMAND_FEEDBACK),
            Map.entry("showDeathMessages", GameRule.SHOW_DEATH_MESSAGES),
            Map.entry("spectatorsGenerateChunks", GameRule.SPECTATORS_GENERATE_CHUNKS),
            Map.entry("disableRaids", GameRule.DISABLE_RAIDS),
            Map.entry("doInsomnia", GameRule.DO_INSOMNIA),
            Map.entry("doImmediateRespawn", GameRule.DO_IMMEDIATE_RESPAWN),
            Map.entry("drowningDamage", GameRule.DROWNING_DAMAGE),
            Map.entry("fallDamage", GameRule.FALL_DAMAGE),
            Map.entry("fireDamage", GameRule.FIRE_DAMAGE),
            Map.entry("freezeDamage", GameRule.FREEZE_DAMAGE),
            Map.entry("doPatrolSpawning", GameRule.DO_PATROL_SPAWNING),
            Map.entry("doTraderSpawning", GameRule.DO_TRADER_SPAWNING),
            Map.entry("doWardenSpawning", GameRule.DO_WARDEN_SPAWNING),
            Map.entry("forgiveDeadPlayers", GameRule.FORGIVE_DEAD_PLAYERS),
            Map.entry("universalAnger", GameRule.UNIVERSAL_ANGER),
            Map.entry("blockExplosionDropDecay", GameRule.BLOCK_EXPLOSION_DROP_DECAY),
            Map.entry("mobExplosionDropDecay", GameRule.MOB_EXPLOSION_DROP_DECAY),
            Map.entry("tntExplosionDropDecay", GameRule.TNT_EXPLOSION_DROP_DECAY),
            Map.entry("waterSourceConversion", GameRule.WATER_SOURCE_CONVERSION),
            Map.entry("lavaSourceConversion", GameRule.LAVA_SOURCE_CONVERSION),
            Map.entry("globalSoundEvents", GameRule.GLOBAL_SOUND_EVENTS),
            Map.entry("doVinesSpread", GameRule.DO_VINES_SPREAD),
            Map.entry("enderPearlsVanishOnDeath", GameRule.ENDER_PEARLS_VANISH_ON_DEATH),
            Map.entry("allowFireTicksAwayFromPlayer", GameRule.ALLOW_FIRE_TICKS_AWAY_FROM_PLAYER),
            Map.entry("tntExplodes", GameRule.TNT_EXPLODES),
            Map.entry("locatorBar", GameRule.LOCATOR_BAR),
            Map.entry("pvp", GameRule.PVP),
            Map.entry("spawnMonsters", GameRule.SPAWN_MONSTERS),
            Map.entry("allowEnteringNetherUsingPortals", GameRule.ALLOW_ENTERING_NETHER_USING_PORTALS),
            Map.entry("commandBlocksEnabled", GameRule.COMMAND_BLOCKS_ENABLED),
            Map.entry("spawnerBlocksEnabled", GameRule.SPAWNER_BLOCKS_ENABLED),
            Map.entry("randomTickSpeed", GameRule.RANDOM_TICK_SPEED),
            Map.entry("spawnRadius", GameRule.SPAWN_RADIUS),
            Map.entry("maxEntityCramming", GameRule.MAX_ENTITY_CRAMMING),
            Map.entry("maxCommandChainLength", GameRule.MAX_COMMAND_CHAIN_LENGTH),
            Map.entry("maxCommandForkCount", GameRule.MAX_COMMAND_FORK_COUNT),
            Map.entry("commandModificationBlockLimit", GameRule.COMMAND_MODIFICATION_BLOCK_LIMIT),
            Map.entry("playersSleepingPercentage", GameRule.PLAYERS_SLEEPING_PERCENTAGE),
            Map.entry("snowAccumulationHeight", GameRule.SNOW_ACCUMULATION_HEIGHT),
            Map.entry("playersNetherPortalDefaultDelay", GameRule.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY),
            Map.entry("playersNetherPortalCreativeDelay", GameRule.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY),
            Map.entry("minecartMaxSpeed", GameRule.MINECART_MAX_SPEED));

    /**
     * Modern names (1.21.11 style). These use {@link GameRules} constants, whose meaning is correct for
     * the renamed/inverted rules such as {@code raids} and {@code playerMovementCheck}.
     */
    private static final Map<String, GameRule<?>> MODERN_ALIASES = Map.ofEntries(
            Map.entry("advanceTime", GameRules.ADVANCE_TIME),
            Map.entry("advanceWeather", GameRules.ADVANCE_WEATHER),
            Map.entry("allowEnteringNetherUsingPortals", GameRules.ALLOW_ENTERING_NETHER_USING_PORTALS),
            Map.entry("blockDrops", GameRules.BLOCK_DROPS),
            Map.entry("blockExplosionDropDecay", GameRules.BLOCK_EXPLOSION_DROP_DECAY),
            Map.entry("commandBlockOutput", GameRules.COMMAND_BLOCK_OUTPUT),
            Map.entry("commandBlocksWork", GameRules.COMMAND_BLOCKS_WORK),
            Map.entry("drowningDamage", GameRules.DROWNING_DAMAGE),
            Map.entry("elytraMovementCheck", GameRules.ELYTRA_MOVEMENT_CHECK),
            Map.entry("enderPearlsVanishOnDeath", GameRules.ENDER_PEARLS_VANISH_ON_DEATH),
            Map.entry("entityDrops", GameRules.ENTITY_DROPS),
            Map.entry("fallDamage", GameRules.FALL_DAMAGE),
            Map.entry("fireDamage", GameRules.FIRE_DAMAGE),
            Map.entry("fireSpreadRadiusAroundPlayer", GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER),
            Map.entry("forgiveDeadPlayers", GameRules.FORGIVE_DEAD_PLAYERS),
            Map.entry("freezeDamage", GameRules.FREEZE_DAMAGE),
            Map.entry("globalSoundEvents", GameRules.GLOBAL_SOUND_EVENTS),
            Map.entry("immediateRespawn", GameRules.IMMEDIATE_RESPAWN),
            Map.entry("keepInventory", GameRules.KEEP_INVENTORY),
            Map.entry("lavaSourceConversion", GameRules.LAVA_SOURCE_CONVERSION),
            Map.entry("limitedCrafting", GameRules.LIMITED_CRAFTING),
            Map.entry("locatorBar", GameRules.LOCATOR_BAR),
            Map.entry("logAdminCommands", GameRules.LOG_ADMIN_COMMANDS),
            Map.entry("maxBlockModifications", GameRules.MAX_BLOCK_MODIFICATIONS),
            Map.entry("maxCommandForks", GameRules.MAX_COMMAND_FORKS),
            Map.entry("maxCommandSequenceLength", GameRules.MAX_COMMAND_SEQUENCE_LENGTH),
            Map.entry("maxEntityCramming", GameRules.MAX_ENTITY_CRAMMING),
            Map.entry("maxMinecartSpeed", GameRules.MAX_MINECART_SPEED),
            Map.entry("maxSnowAccumulationHeight", GameRules.MAX_SNOW_ACCUMULATION_HEIGHT),
            Map.entry("mobDrops", GameRules.MOB_DROPS),
            Map.entry("mobExplosionDropDecay", GameRules.MOB_EXPLOSION_DROP_DECAY),
            Map.entry("mobGriefing", GameRules.MOB_GRIEFING),
            Map.entry("naturalHealthRegeneration", GameRules.NATURAL_HEALTH_REGENERATION),
            Map.entry("playerMovementCheck", GameRules.PLAYER_MOVEMENT_CHECK),
            Map.entry("playersNetherPortalCreativeDelay", GameRules.PLAYERS_NETHER_PORTAL_CREATIVE_DELAY),
            Map.entry("playersNetherPortalDefaultDelay", GameRules.PLAYERS_NETHER_PORTAL_DEFAULT_DELAY),
            Map.entry("playersSleepingPercentage", GameRules.PLAYERS_SLEEPING_PERCENTAGE),
            Map.entry("projectilesCanBreakBlocks", GameRules.PROJECTILES_CAN_BREAK_BLOCKS),
            Map.entry("pvp", GameRules.PVP),
            Map.entry("raids", GameRules.RAIDS),
            Map.entry("randomTickSpeed", GameRules.RANDOM_TICK_SPEED),
            Map.entry("reducedDebugInfo", GameRules.REDUCED_DEBUG_INFO),
            Map.entry("respawnRadius", GameRules.RESPAWN_RADIUS),
            Map.entry("sendCommandFeedback", GameRules.SEND_COMMAND_FEEDBACK),
            Map.entry("showAdvancementMessages", GameRules.SHOW_ADVANCEMENT_MESSAGES),
            Map.entry("showDeathMessages", GameRules.SHOW_DEATH_MESSAGES),
            Map.entry("spawnMobs", GameRules.SPAWN_MOBS),
            Map.entry("spawnMonsters", GameRules.SPAWN_MONSTERS),
            Map.entry("spawnPatrols", GameRules.SPAWN_PATROLS),
            Map.entry("spawnPhantoms", GameRules.SPAWN_PHANTOMS),
            Map.entry("spawnWanderingTraders", GameRules.SPAWN_WANDERING_TRADERS),
            Map.entry("spawnWardens", GameRules.SPAWN_WARDENS),
            Map.entry("spawnerBlocksWork", GameRules.SPAWNER_BLOCKS_WORK),
            Map.entry("spectatorsGenerateChunks", GameRules.SPECTATORS_GENERATE_CHUNKS),
            Map.entry("spreadVines", GameRules.SPREAD_VINES),
            Map.entry("tntExplodes", GameRules.TNT_EXPLODES),
            Map.entry("tntExplosionDropDecay", GameRules.TNT_EXPLOSION_DROP_DECAY),
            Map.entry("universalAnger", GameRules.UNIVERSAL_ANGER),
            Map.entry("waterSourceConversion", GameRules.WATER_SOURCE_CONVERSION));


    GameRuleCatalog(Logger logger) {
        Set<String> modern = new TreeSet<>();
        Set<String> legacyOnly = new TreeSet<>();
        // Legacy constants stay available as aliases (for example doDaylightCycle) even though the
        // registry exposes the modern name (advanceTime). Both map to the same rule.
        for (GameRule<?> rule : GameRule.values()) {
            add(rule, modern, legacyOnly);
        }
        try {
            for (GameRule<?> rule : RegistryAccess.registryAccess().getRegistry(RegistryKey.GAME_RULE)) {
                add(rule, modern, legacyOnly);
            }
        } catch (RuntimeException e) {
            logger.warn("Cannot read the game rule registry; only legacy aliases are available", e);
        }
        for (Map.Entry<String, GameRule<?>> alias : LEGACY_ALIASES.entrySet()) {
            Entry entry = new Entry(alias.getValue(), alias.getKey(), alias.getKey());
            byName.putIfAbsent(alias.getKey().toLowerCase(Locale.ROOT), entry);
            if (!modern.contains(alias.getKey())) {
                legacyOnly.add(alias.getKey());
            }
        }
        for (Map.Entry<String, GameRule<?>> alias : MODERN_ALIASES.entrySet()) {
            Entry entry = new Entry(alias.getValue(), alias.getKey(), alias.getKey());
            byName.putIfAbsent(alias.getKey().toLowerCase(Locale.ROOT), entry);
            modern.add(alias.getKey());
            // Also accept the registry spelling (advance_time) without crowding tab completion.
            String snake = snakeCase(alias.getKey());
            if (!snake.equals(alias.getKey())) {
                byName.putIfAbsent(snake, entry);
            }
        }
        List<String> names = new ArrayList<>(modern);
        names.addAll(legacyOnly);
        this.suggestions = List.copyOf(new LinkedHashSet<>(names));
    }

    private static String snakeCase(String camel) {
        StringBuilder out = new StringBuilder(camel.length() + 4);
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private void add(GameRule<?> rule, Set<String> modern, Set<String> legacyOnly) {
        String key;
        try {
            key = rule.getKey() == null ? "" : rule.getKey().getKey();
        } catch (RuntimeException e) {
            key = "";
        }
        String legacy = rule.getName() == null ? "" : rule.getName();
        Entry entry = new Entry(rule, key.isEmpty() ? legacy : key, legacy);
        if (!key.isEmpty()) {
            byName.putIfAbsent(key.toLowerCase(Locale.ROOT), entry);
            modern.add(key);
        }
        if (!legacy.isEmpty()) {
            byName.putIfAbsent(legacy.toLowerCase(Locale.ROOT), entry);
            if (!legacy.equalsIgnoreCase(key)) {
                legacyOnly.add(legacy);
            }
        }
    }

    Entry find(String name) {
        return name == null ? null : byName.get(name.trim().toLowerCase(Locale.ROOT));
    }

    List<String> suggestions() {
        return suggestions;
    }

    /** Names to offer for a partially typed rule. */
    List<String> suggestions(String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Set<String> out = new LinkedHashSet<>();
        for (String name : suggestions) {
            if (name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                out.add(name);
            }
        }
        return List.copyOf(out);
    }

    static Object value(World world, GameRule<?> rule) {
        return read(world, rule);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object read(World world, GameRule<?> rule) {
        return world.getGameRuleValue((GameRule) rule);
    }

    static boolean set(World world, GameRule<?> rule, Object value) {
        return write(world, rule, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean write(World world, GameRule<?> rule, Object value) {
        return world.setGameRule((GameRule) rule, value);
    }
}
