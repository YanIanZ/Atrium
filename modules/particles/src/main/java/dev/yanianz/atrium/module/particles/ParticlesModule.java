package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import dev.yanianz.atrium.module.particles.shape.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/** Player particle cosmetics with per-style shapes, colours, presets and a chest menu. */
public final class ParticlesModule implements AtriumModule, Listener {
    static final String PERM_BASE = "atrium.particles";
    static final String PERM_ADMIN = "atrium.particles.admin";
    static final String PERM_PRESET = "atrium.particles.preset";
    static final String PERM_HIDE_OTHERS = "atrium.particles.hideothers";
    static final String SLOT_PREFIX = "atrium.particles.slots";

    private ModuleContext ctx;
    private ConfigHandle<ParticlesConfig> config;
    private ParticleStore store;
    private ParticleEngine engine;
    private ParticleMenu menu;

    @Override
    public String id() {
        return "particles";
    }

    @Override
    public String description() {
        return "Player particle cosmetics: shapes, colours, presets and a menu";
    }

    @Override
    public java.util.Set<String> softDependencies() {
        return java.util.Set.of("vanish", "afk");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(ParticlesConfig.class, ParticlesConfig::new);
        config.get().validate();
        context.migrate(ParticleStore.MIGRATIONS);
        this.store = new ParticleStore(context.database());
        context.component(store.component);
        this.engine = new ParticleEngine(context, config, store, new ParticleData(config.get(), context.logger()),
                this::slots);
        this.menu = new ParticleMenu(context, this, config);

        registerPermissions();
        new ParticleCommands(context, this, config, menu).register();

        context.listen(this);
        for (Player player : List.copyOf(org.bukkit.Bukkit.getOnlinePlayers())) {
            context.scheduler().entity(player, () -> engine.join(player), null);
        }
    }

    private void registerPermissions() {
        ctx.permission(PERM_BASE, PermissionDefault.TRUE, "Use player particle effects");
        ctx.permission(PERM_ADMIN, PermissionDefault.OP, "Set and clear particle effects of other players");
        ctx.permission(PERM_PRESET, PermissionDefault.TRUE, "Use particle presets");
        ctx.permission(PERM_HIDE_OTHERS, PermissionDefault.TRUE, "Hide particle effects of other players");
        ctx.permission(SLOT_PREFIX, PermissionDefault.TRUE,
                "Number of active effects, granted as " + SLOT_PREFIX + ".<n> (default from config)");
        ctx.permission(SLOT_PREFIX + ".unlimited", PermissionDefault.OP, "Unlimited active particle effects");
        for (Style style : Style.values()) {
            ctx.permission(style.permission(), PermissionDefault.TRUE, "Use the " + style.key() + " particle style");
        }
        for (Particle particle : Particle.values()) {
            if (ParticleData.supported(particle)) {
                ctx.permission(ParticleNames.permission(particle), PermissionDefault.TRUE,
                        "Use the " + ParticleNames.key(particle) + " particle");
            }
        }
    }

    @Override
    public void reload() {
        config.get().validate();
        engine.reload();
    }

    @Override
    public void disable() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> engine.join(player), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        engine.stop(event.getPlayer().getUniqueId());
    }

    // ---- used by commands and the menu ----

    ParticlesConfig config() {
        return config.get();
    }

    ParticleEngine engine() {
        return engine;
    }

    ParticleProfile profileOf(Player player) {
        try {
            return ctx.users().user(player).get(store.component);
        } catch (IllegalStateException e) {
            ctx.logger().warn("Particle profile of {} is not loaded yet", player.getName());
            return ParticleProfile.EMPTY;
        }
    }

    int slots(Player player) {
        return SlotLimits.resolve(config.get().defaultSlots, player.hasPermission(SLOT_PREFIX + ".unlimited"),
                Permissions.highestNumeric(player, SLOT_PREFIX));
    }

    boolean canUseStyle(Player player, Style style) {
        return player.hasPermission(style.permission());
    }

    boolean canUseParticle(Player player, Particle particle) {
        return player.hasPermission(ParticleNames.permission(particle));
    }

    ParticleEffect effectFor(Style style, Particle particle, ParticleColours colours) {
        ParticlesConfig cfg = config.get();
        return new ParticleEffect(style, particle, colours, cfg.radius(style), cfg.density(style), cfg.speed(style));
    }

    ParticleColours colours(Style style, String value) {
        return config.get().colours(style, value);
    }

    /** Default particle of a style from config, resolved and checked for support. */
    Particle defaultParticle(Style style) {
        Particle particle = ParticleNames.resolve(config.get().style(style).particle);
        return particle != null && ParticleData.supported(particle) ? particle : Particle.DUST;
    }

    void applySelf(Player player, ParticleEffect effect) {
        if (!canUseStyle(player, effect.style())) {
            send(player, "particles.style-denied", Msg.unparsed("style", effect.style().key()));
            return;
        }
        if (!canUseParticle(player, effect.particle())) {
            send(player, "particles.particle-denied", Msg.unparsed("particle", ParticleNames.key(effect.particle())));
            return;
        }
        ParticleProfile.AddResult result = ParticleProfile.add(profileOf(player), effect, slots(player));
        if (result.full()) {
            send(player, "particles.slots-full", Msg.number("slots", slots(player)));
            return;
        }
        ctx.users().user(player).update(store.component, ignored -> result.profile());
        engine.restart(player);
        send(player, "particles.effect-applied",
                Msg.unparsed("style", effect.style().key()),
                Msg.unparsed("particle", ParticleNames.key(effect.particle())));
    }

    void removeSelf(Player player, Style style) {
        ParticleProfile current = profileOf(player);
        if (current.effect(style) == null) {
            send(player, "particles.not-active", Msg.unparsed("style", style.key()));
            return;
        }
        ctx.users().user(player).update(store.component, currentProfile -> currentProfile.without(style));
        engine.restart(player);
        send(player, "particles.effect-removed", Msg.unparsed("style", style.key()));
    }

    void clearSelf(Player player) {
        ctx.users().user(player).update(store.component, ParticleProfile::withoutAll);
        engine.restart(player);
        send(player, "particles.all-removed");
    }

    void listSelf(Player player) {
        ParticleProfile profile = profileOf(player);
        if (profile.effects().isEmpty()) {
            send(player, "particles.list-empty");
            return;
        }
        send(player, "particles.list-header");
        for (ParticleEffect effect : profile.effects()) {
            send(player, "particles.list-entry",
                    Msg.unparsed("style", effect.style().key()),
                    Msg.unparsed("particle", ParticleNames.key(effect.particle())),
                    Msg.unparsed("colour", ParticleColours.hex(effect.colours().primary())));
        }
    }

    void toggleHideOthers(Player player) {
        if (!player.hasPermission(PERM_HIDE_OTHERS)) {
            send(player, "particles.hideothers-denied");
            return;
        }
        ParticleProfile current = profileOf(player);
        boolean value = !current.hideOthers();
        ctx.users().user(player).update(store.component, profile -> profile.withHideOthers(value));
        send(player, value ? "particles.hideothers-on" : "particles.hideothers-off");
    }

    void toggleHidden(Player player) {
        ParticleProfile current = profileOf(player);
        boolean value = !current.hidden();
        ctx.users().user(player).update(store.component, profile -> profile.withHidden(value));
        engine.restart(player);
        send(player, value ? "particles.hidden-on" : "particles.hidden-off");
    }

    boolean applyPreset(Player player, String name) {
        if (!player.hasPermission(PERM_PRESET)) {
            send(player, "particles.preset-denied");
            return false;
        }
        ParticlesConfig cfg = config.get();
        ParticlesConfig.Preset preset = findPreset(cfg, name);
        if (preset == null) {
            send(player, "particles.preset-unknown", Msg.unparsed("name", name));
            return false;
        }
        List<ParticleEffect> effects = new ArrayList<>();
        java.util.Map<Style, ParticleEffect> byStyle = new java.util.LinkedHashMap<>();
        if (preset.effects != null) {
            for (ParticlesConfig.PresetEffect raw : preset.effects) {
                ParticleEffect effect = presetEffect(cfg, raw);
                if (effect != null) {
                    byStyle.put(effect.style(), effect);
                }
            }
        }
        effects.addAll(byStyle.values());
        if (effects.isEmpty()) {
            send(player, "particles.preset-empty", Msg.unparsed("name", name));
            return false;
        }
        int slots = slots(player);
        boolean truncated = effects.size() > slots;
        if (truncated) {
            effects = new ArrayList<>(effects.subList(0, Math.max(0, slots)));
        }
        List<ParticleEffect> chosen = List.copyOf(effects);
        ctx.users().user(player).update(store.component,
                current -> new ParticleProfile(chosen, current.hideOthers(), current.hidden()));
        engine.restart(player);
        send(player, "particles.preset-applied", Msg.unparsed("name", name));
        if (truncated) {
            send(player, "particles.preset-truncated", Msg.number("slots", slots));
        }
        return true;
    }

    private static ParticlesConfig.Preset findPreset(ParticlesConfig cfg, String name) {
        if (name == null || cfg.presets == null) {
            return null;
        }
        for (Map.Entry<String, ParticlesConfig.Preset> entry : cfg.presets.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private ParticleEffect presetEffect(ParticlesConfig cfg, ParticlesConfig.PresetEffect raw) {
        if (raw == null) {
            return null;
        }
        Style style = Style.byName(raw.style);
        Particle particle = ParticleNames.resolve(raw.particle);
        if (style == null || particle == null || !ParticleData.supported(particle)) {
            ctx.logger().warn("Skipping invalid particle preset entry style='{}' particle='{}'", raw.style,
                    raw.particle);
            return null;
        }
        ParticleColours base = cfg.colours(style, null);
        Color primary = ParticleColours.parse(raw.colour, base.primary());
        Color secondary = ParticleColours.parse(raw.colour2, base.secondary());
        ParticleColours.Mode mode = ParticleColours.Mode.byName(raw.colourMode);
        ParticleColours colours = new ParticleColours(mode == null ? base.mode() : mode, primary, secondary);
        return new ParticleEffect(style, particle, colours,
                raw.radius > 0 ? raw.radius : cfg.radius(style),
                raw.density > 0 ? raw.density : cfg.density(style),
                raw.speed > 0 ? raw.speed : cfg.speed(style));
    }

    // ---- admin ----

    /** Applies an effect for an online player on their own thread. */
    void applyAdmin(Player target, ParticleEffect effect) {
        ParticleProfile current = profileOf(target);
        List<ParticleEffect> copy = new ArrayList<>(current.effects());
        copy.removeIf(existing -> existing.style() == effect.style());
        copy.add(effect);
        while (copy.size() > 8) {
            copy.removeFirst();
        }
        ctx.users().user(target).update(store.component,
                ignored -> new ParticleProfile(copy, current.hideOthers(), current.hidden()));
        engine.restart(target);
        send(target, "particles.admin-set-target", Msg.unparsed("style", effect.style().key()));
    }

    void clearAdmin(Player target) {
        ctx.users().user(target).update(store.component, ParticleProfile::withoutAll);
        engine.restart(target);
        send(target, "particles.admin-clear-target");
    }

    /** Offline variant used by the admin commands; completes with false when the account is unknown. */
    CompletableFuture<Boolean> applyAdminOffline(UUID uuid, ParticleEffect effect) {
        return modifyOffline(uuid, current -> {
            List<ParticleEffect> copy = new ArrayList<>(current.effects());
            copy.removeIf(existing -> existing.style() == effect.style());
            copy.add(effect);
            while (copy.size() > 8) {
                copy.removeFirst();
            }
            return new ParticleProfile(copy, current.hideOthers(), current.hidden());
        });
    }

    CompletableFuture<Boolean> clearAdminOffline(UUID uuid) {
        return modifyOffline(uuid, ParticleProfile::withoutAll);
    }

    /**
     * Changes an offline profile through the core user cache: the account is loaded, updated and saved
     * with the same API the online path uses. A join that happens in between sees this loaded instance, so
     * the edit is never overwritten by stale data loaded from the tables.
     */
    private CompletableFuture<Boolean> modifyOffline(UUID uuid, java.util.function.UnaryOperator<ParticleProfile> change) {
        return ctx.users().load(uuid).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            try {
                found.get().update(store.component, change);
            } catch (IllegalStateException e) {
                // The component registration changed under us; report a failure instead of writing blind.
                ctx.logger().warn("Particle profile of {} is not loaded; the edit was not applied", uuid);
                return CompletableFuture.completedFuture(false);
            }
            return ctx.users().saveNow(uuid).thenApply(ignored -> true);
        });
    }

    // ---- helpers ----

    private void send(CommandSender sender, String key,
            net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        ctx.messages().send(sender, key, tags);
    }

}
