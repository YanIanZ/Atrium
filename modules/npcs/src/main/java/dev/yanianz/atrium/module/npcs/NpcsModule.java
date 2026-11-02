package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.npcs.NpcInteractEvent;
import dev.yanianz.atrium.api.npcs.NpcService;
import dev.yanianz.atrium.api.npcs.NpcType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Server NPCs with three interchangeable backends. Atrium is the source of truth: definitions live in
 * {@code npcs_npc}, backends only render them. Every world change is scheduled on the region that owns the
 * NPC, and profile lookups run on the I/O executor.
 */
public final class NpcsModule implements AtriumModule, NpcService, Listener {
    static final String PERM_ADMIN = "atrium.npc";
    static final String PERM_USE = "atrium.npc.use";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final List<String> GLOW_COLORS = List.of("black", "dark_blue", "dark_green", "dark_aqua",
            "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white");

    enum Apply {
        REFRESH,
        MOVE,
        NONE
    }

    private ModuleContext ctx;
    private ConfigHandle<NpcsConfig> config;
    private NpcStore store;
    private NpcSkinResolver skins;
    private NpcActions actions;
    private NpcBackend.Nameplates nameplates;
    private NativeLookAt lookAt;
    private NativeBackend nativeBackend;
    private @Nullable CitizensBackend citizensBackend;
    private @Nullable FancyBackend fancyBackend;
    private final Map<NpcType, NpcBackend> backends = new ConcurrentHashMap<>();
    private final Map<String, NpcDef> defs = new ConcurrentHashMap<>();
    private final Map<UUID, String> selected = new ConcurrentHashMap<>();
    private volatile boolean stopping;
    private volatile String reportedEngine;
    private final java.util.Set<NpcType> reportedFallback = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "npcs";
    }

    @Override
    public String description() {
        return "NPCs: native mannequins, Citizens and FancyNpcs with skins, equipment, poses, scale, nameplates and click actions (/npc).";
    }

    @Override
    public java.util.Set<String> softDependencies() {
        return java.util.Set.of("actions", "dialogs", "economy", "holograms", "proxy", "teleport", "warps");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.stopping = false;
        this.config = context.config(NpcsConfig.class, NpcsConfig::new);
        context.migrate(NpcStore.MIGRATIONS);
        this.store = new NpcStore(context.database(), message -> context.logger().warn(message));
        this.skins = new NpcSkinResolver(context, store, config::get);
        this.actions = new NpcActions(context, config::get);
        this.nameplates = new NpcBackend.Nameplates(context, config::get);

        List<NpcDef> loaded = store.loadAll().get(30, TimeUnit.SECONDS);
        for (NpcDef def : loaded) {
            defs.put(def.key(), def);
        }

        this.nativeBackend = new NativeBackend(context, config::get, skins, nameplates, this::definitions,
                (player, interaction) -> click(player, interaction.npc(), interaction.click()));
        this.lookAt = new NativeLookAt(context, config::get, this::definitions, nativeBackend::entity);
        this.nativeBackend.lookAt(lookAt);
        backends.put(NpcType.NATIVE, nativeBackend);

        hookCitizens();
        hookFancy();

        context.permission(PERM_ADMIN, PermissionDefault.OP, "Create, edit and remove NPCs (/npc)");
        context.permission(PERM_USE, PermissionDefault.TRUE, "Click NPCs that have click actions");

        context.listen(nativeBackend);
        context.listen(this);
        if (citizensBackend != null) {
            context.listen(citizensBackend);
        }
        if (fancyBackend != null) {
            context.listen(fancyBackend);
        }

        NpcCommands commands = new NpcCommands(this, context);
        commands.register();
        context.service(NpcService.class, this);

        spawnAll();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            context.scheduler().entityLater(player, () -> lookAt.touch(player), null, 20L);
        }
        context.logger().info("Loaded {} NPCs on engine {}", defs.size(), activeEngine().configName());
    }

    private void hookCitizens() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            return;
        }
        try {
            citizensBackend = new CitizensBackend(ctx, nameplates, this::definitions,
                    (player, interaction) -> click(player, interaction.npc(), interaction.click()));
            backends.put(NpcType.CITIZENS, citizensBackend);
        } catch (Throwable e) {
            ctx.logger().warn("Citizens is enabled but its API could not be used; NPCs will use native mannequins", e);
        }
    }

    private void hookFancy() {
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyNpcs")) {
            return;
        }
        try {
            fancyBackend = new FancyBackend(ctx, nameplates, this::definitions,
                    (player, interaction) -> click(player, interaction.npc(), interaction.click()));
            backends.put(NpcType.FANCYNPC, fancyBackend);
        } catch (Throwable e) {
            ctx.logger().warn("FancyNpcs is enabled but its API could not be used; NPCs will use native mannequins", e);
        }
    }

    @Override
    public void disable() {
        stopping = true;
        if (lookAt != null) {
            lookAt.shutdown();
        }
        for (NpcBackend backend : backends.values()) {
            try {
                backend.shutdown();
            } catch (RuntimeException e) {
                ctx.logger().warn("Could not shut down the {} NPC backend cleanly", backend.type().configName(), e);
            }
        }
        if (skins != null) {
            skins.stop();
        }
        selected.clear();
    }

    @Override
    public void reload() {
        if (lookAt != null) {
            lookAt.shutdown();
        }
        // The engine or nameplate settings may have changed: tear every display down and build it again.
        respawnAll();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            ctx.scheduler().entity(player, () -> lookAt.touch(player), null);
        }
    }

    // ---- NpcService ----

    @Override
    public Collection<NpcInfo> all() {
        return defs.values().stream()
                .sorted(Comparator.comparing(NpcDef::name, String.CASE_INSENSITIVE_ORDER))
                .map(this::info)
                .toList();
    }

    @Override
    public Optional<NpcInfo> get(String name) {
        if (name == null) {
            return Optional.empty();
        }
        NpcDef def = defs.get(name.toLowerCase(Locale.ROOT));
        return def == null ? Optional.empty() : Optional.of(info(def));
    }

    private NpcInfo info(NpcDef def) {
        return new NpcInfo(def.id(), def.name(), def.displayName(), def.location(), def.skin() == null ? "" : def.skin(),
                effectiveType(def));
    }

    // ---- package API for commands ----

    Collection<NpcDef> definitions() {
        return List.copyOf(defs.values());
    }

    @Nullable
    NpcDef def(String name) {
        return name == null ? null : defs.get(name.toLowerCase(Locale.ROOT));
    }

    @Nullable
    NpcDef selected(Player player) {
        String key = selected.get(player.getUniqueId());
        return key == null ? null : defs.get(key);
    }

    void select(Player player, NpcDef def) {
        selected.put(player.getUniqueId(), def.key());
    }

    NpcType configuredEngine() {
        NpcType parsed = NpcType.parse(config.get().engine).orElse(null);
        if (parsed == null) {
            if (!config.get().engine.equals(reportedEngine)) {
                reportedEngine = config.get().engine;
                ctx.logger().warn("Unknown npcs.yml engine '{}'; using auto", config.get().engine);
            }
            return NpcType.AUTO;
        }
        return parsed;
    }

    NpcType activeEngine() {
        return NpcEngines.resolve(configuredEngine(), citizensAvailable(), fancyAvailable());
    }

    NpcType effectiveType(NpcDef def) {
        return def.engine() == NpcType.AUTO ? activeEngine() : def.engine();
    }

    boolean citizensAvailable() {
        return backends.containsKey(NpcType.CITIZENS) && citizensBackend != null && citizensBackend.available();
    }

    boolean fancyAvailable() {
        return backends.containsKey(NpcType.FANCYNPC) && fancyBackend != null && fancyBackend.available();
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static List<String> glowColors() {
        return GLOW_COLORS;
    }

    long defaultCooldownMillis() {
        return Math.max(0, config.get().defaultCooldownSeconds) * 1000L;
    }

    int maxActions() {
        return Math.max(1, Math.min(64, config.get().maxActionsPerClick));
    }

    double selectRange() {
        return Math.max(2, Math.min(32, config.get().selectRange));
    }

    double selectAlignment() {
        return Math.max(-1, Math.min(1, config.get().selectMinAlignment));
    }

    /** What the engine that renders this NPC can actually apply; pure matrix in {@link NpcCapabilities}. */
    NpcCapabilities.Support capability(NpcDef def, NpcCapabilities.Capability capability) {
        return NpcCapabilities.support(effectiveType(def), capability);
    }

    /** True when a nameplate change becomes a separate hologram instead of the vanilla name. */
    boolean hologramsActive() {
        return nameplates.active();
    }

    /** Poses the engine can apply, in canonical lower-case Bukkit {@code Pose} spelling. */
    List<String> supportedPoses(NpcType type) {
        NpcType engine = type == null || type == NpcType.AUTO ? activeEngine() : type;
        return switch (engine) {
            case NATIVE -> Mannequin.validPoses().stream()
                    .map(pose -> pose.name().toLowerCase(Locale.ROOT)).sorted().toList();
            case CITIZENS -> NpcPose.CITIZENS_POSES;
            case FANCYNPC, AUTO -> List.of();
        };
    }

    boolean supportsPose(NpcType type, String pose) {
        return pose != null && supportedPoses(type).contains(pose);
    }

    /**
     * Exact ray trace first ({@code Player}'s eye, max {@code selectRange} blocks, only entities that map to
     * a stored NPC), then the view cone as a fallback. Runs on the player's region thread.
     */
    @Nullable
    NpcDef pickSelect(Player player) {
        Location eye = player.getEyeLocation();
        org.bukkit.util.Vector direction = eye.getDirection();
        double range = selectRange();
        NpcRay.Hit exact = exactHit(player, eye, direction, range);
        // Only pay for the cone scan when the ray did not resolve to a stored NPC (no hit or a stale marker).
        boolean resolved = exact != null && exact.npcKey() != null && defs.containsKey(exact.npcKey());
        NpcRay.Hit cone = resolved ? null : coneHit(player, eye, direction, range);
        String key = NpcRay.pick(exact, cone, range, defs::containsKey);
        return key == null ? null : defs.get(key);
    }

    private NpcRay.Hit exactHit(Player player, Location eye, org.bukkit.util.Vector direction,
            double range) {
        org.bukkit.util.RayTraceResult result = player.getWorld().rayTraceEntities(eye, direction, range, 0.35,
                entity -> !entity.getUniqueId().equals(player.getUniqueId()) && npcKeyOf(entity) != null);
        if (result == null || result.getHitEntity() == null) {
            return null;
        }
        double distance = result.getHitPosition().distance(eye.toVector());
        // Do not select an NPC 8 blocks behind a wall: the entity ray ignores blocks, so compare the block hit.
        org.bukkit.util.RayTraceResult wall = player.getWorld().rayTraceBlocks(eye, direction, range,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (wall != null && wall.getHitPosition().distance(eye.toVector()) <= distance) {
            return null;
        }
        return new NpcRay.Hit(distance, npcKeyOf(result.getHitEntity()));
    }

    private NpcRay.Hit coneHit(Player player, Location eye, org.bukkit.util.Vector direction,
            double range) {
        List<NpcDef> candidates = new ArrayList<>();
        List<Location> locations = new ArrayList<>();
        for (NpcDef def : defs.values()) {
            if (!def.world().equals(player.getWorld().getName())) {
                continue;
            }
            Location location = def.location().toLocation().orElse(null);
            if (location == null) {
                continue;
            }
            candidates.add(def);
            locations.add(location);
        }
        int index = NpcGeometry.select(eye, direction, locations, range, selectAlignment());
        if (index < 0) {
            return null;
        }
        return new NpcRay.Hit(locations.get(index).distance(eye), candidates.get(index).key());
    }

    /** The Atrium NPC key behind an entity owned by any active backend, or {@code null}. */
    @Nullable
    String npcKeyOf(org.bukkit.entity.Entity entity) {
        if (entity == null) {
            return null;
        }
        String key = nativeBackend.npcKey(entity);
        if (key != null) {
            return defs.containsKey(key) ? key : null;
        }
        for (NpcBackend backend : backends.values()) {
            if (backend == nativeBackend || !backend.available()) {
                continue;
            }
            key = backend.npcKey(entity);
            if (key != null && defs.containsKey(key)) {
                return key;
            }
        }
        return null;
    }

    Optional<String> actionProblem(String raw) {
        return actions.problem(raw);
    }

    void persist(NpcDef def, Apply apply, @Nullable CommandSender sender, String successKey,
            @Nullable Consumer<NpcDef> onSaved, TagResolver... tags) {
        store.save(def).whenComplete((stored, error) -> {
            if (error != null) {
                ctx.logger().error("Could not store NPC '{}'", def.name(), error);
                reply(sender, "npcs.failed");
                return;
            }
            defs.put(stored.key(), stored);
            applyOnRegion(stored, apply);
            if (onSaved != null) {
                onSaved.accept(stored);
            }
            reply(sender, successKey, tags);
        });
    }

    void delete(NpcDef def, @Nullable CommandSender sender) {
        store.delete(def.name()).whenComplete((deleted, error) -> {
            if (error != null) {
                ctx.logger().error("Could not delete NPC '{}'", def.name(), error);
                reply(sender, "npcs.failed");
                return;
            }
            if (!deleted) {
                reply(sender, "npcs.unknown", Msg.unparsed("npc", def.name()));
                return;
            }
            defs.remove(def.key());
            selected.values().removeIf(def.key()::equals);
            Location location = def.location().toLocation().orElse(null);
            if (location != null) {
                ctx.scheduler().region(location, () -> {
                    for (NpcBackend backend : backends.values()) {
                        backend.despawn(def);
                    }
                });
            } else {
                nameplates.remove(def);
            }
            reply(sender, "npcs.deleted", Msg.unparsed("npc", def.name()));
        });
    }

    void migrate(NpcType from, NpcType to, CommandSender sender) {
        List<NpcDef> changed = new ArrayList<>();
        for (NpcDef def : defs.values()) {
            if (effectiveType(def) == from) {
                changed.add(def.withEngine(to));
            }
        }
        if (changed.isEmpty()) {
            reply(sender, "npcs.migrate-empty", Msg.unparsed("from", from.configName()));
            return;
        }
        List<java.util.concurrent.CompletableFuture<NpcDef>> saves = new ArrayList<>();
        for (NpcDef def : changed) {
            saves.add(store.save(def));
        }
        java.util.concurrent.CompletableFuture.allOf(saves.toArray(java.util.concurrent.CompletableFuture[]::new))
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ctx.logger().error("Could not migrate NPCs from {} to {}", from.configName(), to.configName(), error);
                        reply(sender, "npcs.failed");
                        return;
                    }
                    for (var future : saves) {
                        NpcDef stored = future.join();
                        defs.put(stored.key(), stored);
                    }
                    respawnAll();
                    reply(sender, "npcs.migrated", Msg.unparsed("count", Integer.toString(changed.size())),
                            Msg.unparsed("from", from.configName()), Msg.unparsed("to", to.configName()));
                });
    }

    void respawnAll() {
        for (NpcDef def : defs.values()) {
            Location location = def.location().toLocation().orElse(null);
            if (location == null) {
                continue;
            }
            ctx.scheduler().region(location, () -> {
                for (NpcBackend backend : backends.values()) {
                    backend.despawn(def);
                }
                spawn(def);
            });
        }
    }

    // ---- spawning ----

    void spawnAll() {
        for (NpcDef def : defs.values()) {
            spawn(def);
        }
    }

    private void spawn(NpcDef def) {
        Location location = def.location().toLocation().orElse(null);
        if (location == null || !chunkLoaded(location)) {
            return;
        }
        ctx.scheduler().region(location, () -> backendFor(def).spawn(def));
    }

    private void applyOnRegion(NpcDef def, Apply apply) {
        if (apply == Apply.NONE) {
            return;
        }
        Location location = def.location().toLocation().orElse(null);
        if (location == null) {
            return;
        }
        ctx.scheduler().region(location, () -> {
            NpcBackend backend = backendFor(def);
            if (apply == Apply.MOVE) {
                backend.move(def);
            } else if (chunkLoaded(location)) {
                backend.refresh(def);
            }
        });
    }

    private NpcBackend backendFor(NpcDef def) {
        NpcType type = effectiveType(def);
        NpcBackend backend = backends.get(type);
        if (backend != null && backend.available()) {
            return backend;
        }
        if (type != NpcType.NATIVE && reportedFallback.add(type)) {
            ctx.logger().warn("The {} backend is not enabled; those NPCs use native mannequins", type.configName());
        }
        return nativeBackend;
    }

    private static boolean chunkLoaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    // ---- clicks ----

    private void click(Player player, String npcKey, NpcInteractClick click) {
        if (stopping) {
            return;
        }
        NpcDef def = defs.get(npcKey);
        if (def == null) {
            return;
        }
        // Our event is fired before any permission, cooldown or cost check, so protection plugins can
        // deny a click without side effects.
        NpcInteractEvent event = new NpcInteractEvent(player, def.name(), click.event(), effectiveType(def));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return;
        }
        if (!player.hasPermission(PERM_USE)) {
            ctx.messages().send(player, "npcs.click-no-permission");
            return;
        }
        List<String> lines = def.actions(click);
        if (lines.isEmpty()) {
            return;
        }
        boolean hasPermission = def.permission() == null || player.hasPermission(def.permission());
        BigDecimal cost = def.cost();
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        BigDecimal balance = cost.signum() <= 0 ? BigDecimal.ZERO
                : economy == null ? null
                : Optional.ofNullable(economy.cachedBalance(player.getUniqueId())).orElse(BigDecimal.ZERO);
        NpcAccess.Outcome outcome = NpcAccess.decide(def.permission(), hasPermission, Duration.ZERO, cost, balance);
        if (outcome != NpcAccess.Outcome.ALLOWED) {
            sendOutcome(player, outcome, def, Duration.ZERO, cost);
            return;
        }

        var user = ctx.users().user(player);
        String cooldownKey = "npcs:" + def.key();
        Duration left = Duration.ZERO;
        if (def.cooldownMillis() > 0) {
            left = ctx.cooldowns().tryAcquire(user, cooldownKey, Duration.ofMillis(def.cooldownMillis()));
        }
        NpcAccess.Outcome cooldown = NpcAccess.decide(null, true, left, BigDecimal.ZERO, BigDecimal.ZERO);
        if (cooldown != NpcAccess.Outcome.ALLOWED) {
            sendOutcome(player, cooldown, def, left, cost);
            return;
        }

        if (cost.signum() > 0 && economy != null) {
            economy.withdraw(player.getUniqueId(), cost, "npc:" + def.name()).whenComplete((result, error) -> {
                boolean paid = error == null && result != null && result.success();
                if (!paid) {
                    // Never keep the cooldown of a click that was not paid for.
                    ctx.cooldowns().clear(user, cooldownKey);
                    ctx.scheduler().entity(player, () -> ctx.messages().send(player, "npcs.click-no-money",
                            Msg.unparsed("cost", cost.toPlainString())), null);
                    return;
                }
                ctx.scheduler().entity(player, () -> actions.run(def, player, click), null);
            });
            return;
        }
        actions.run(def, player, click);
    }

    private void sendOutcome(Player player, NpcAccess.Outcome outcome, NpcDef def, Duration left, BigDecimal cost) {
        switch (outcome) {
            case NO_PERMISSION -> ctx.messages().send(player, "npcs.click-no-permission");
            case COOLDOWN -> ctx.messages().send(player, "npcs.click-cooldown", Msg.duration("time", left));
            case NO_ECONOMY -> ctx.messages().send(player, "npcs.click-no-economy");
            case INSUFFICIENT_FUNDS -> ctx.messages().send(player, "npcs.click-no-money",
                    Msg.unparsed("cost", cost.toPlainString()));
            case ALLOWED -> { }
        }
    }

    // ---- events ----

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (NpcDef def : defs.values()) {
            StoredLocation location = def.location();
            if (!location.world().equals(event.getWorld().getName())
                    || (location.blockX() >> 4) != event.getChunk().getX()
                    || (location.blockZ() >> 4) != event.getChunk().getZ()) {
                continue;
            }
            ctx.scheduler().region(def.location().toLocation().orElse(event.getChunk().getBlock(0, 0, 0).getLocation()),
                    () -> backendFor(def).spawn(def));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo().getBlockX() != event.getFrom().getBlockX()
                || event.getTo().getBlockZ() != event.getFrom().getBlockZ()) {
            lookAt.touch(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        lookAt.touch(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        ctx.scheduler().entityLater(event.getPlayer(), () -> lookAt.touch(event.getPlayer()), null, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        selected.remove(event.getPlayer().getUniqueId());
        lookAt.removePlayer(event.getPlayer().getUniqueId());
        nativeBackend.removePlayer(event.getPlayer().getUniqueId());
    }

    // ---- helpers ----

    void reply(@Nullable CommandSender sender, String key, TagResolver... tags) {
        if (sender == null) {
            return;
        }
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    String formatCost(BigDecimal value) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        return economy == null ? value.toPlainString() : economy.format(value);
    }
}
