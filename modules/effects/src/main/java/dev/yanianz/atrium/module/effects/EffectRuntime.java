package dev.yanianz.atrium.module.effects;

import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Spawns one parsed effect: particles on the owning region thread, text on the subject's entity thread and
 * a marked firework whose explosion damage is cancelled by {@link FireworkGuard}.
 */
final class EffectRuntime {
    /**
     * How an event is presented.
     *
     * @param showText       titles and action bars go to the subject (false when they are leaving)
     * @param includeSubject whether the subject also sees and hears the particles
     * @param skipVanished   skip the effect entirely for a vanished subject
     * @param delayTicks     wait this long first, so a teleport or respawn has completed
     */
    record Fire(boolean showText, boolean includeSubject, boolean skipVanished, long delayTicks) {
        static final Fire NORMAL = new Fire(true, true, true, 0);
        static final Fire OTHERS_ONLY = new Fire(false, false, true, 0);
    }

    private final ModuleContext ctx;
    private final Logger logger;
    private final Supplier<Map<String, EffectStyle>> styles;
    private final NamespacedKey fireworkKey;
    private final @Nullable VanishService vanish;
    private final Set<String> unknownSounds = ConcurrentHashMap.newKeySet();

    EffectRuntime(
            ModuleContext ctx,
            Supplier<Map<String, EffectStyle>> styles,
            @Nullable VanishService vanish,
            NamespacedKey fireworkKey) {
        this.ctx = ctx;
        this.logger = ctx.logger();
        this.styles = styles;
        this.vanish = vanish;
        this.fireworkKey = fireworkKey;
    }

    /** Runs a configured event effect after the permission, vanish and cooldown checks. */
    void fire(String key, Player subject, Location origin, Fire fire) {
        EffectStyle style = styles.get().get(EffectKeys.normalize(key));
        if (style != null) {
            fire(style, subject, origin, fire);
        }
    }

    /** Runs one parsed effect after the permission, vanish and cooldown checks. */
    void fire(EffectStyle style, Player subject, Location origin, Fire fire) {
        if (!style.enabled()) {
            return;
        }
        if (fire.skipVanished() && isVanished(subject)) {
            return;
        }
        if (!style.permission().isBlank() && !subject.hasPermission(style.permission())) {
            return;
        }
        if (style.hasCooldown()) {
            Duration left = ctx.cooldowns().tryAcquire(
                    ctx.users().user(subject), "effects:" + style.key(), style.cooldown());
            if (!left.isZero()) {
                return;
            }
        }
        play(style, subject, origin, fire);
    }

    /** Shows an effect without permission, cooldown or enabled checks. Used by {@code /effects test}. */
    void preview(EffectStyle style, Player subject) {
        // Read the location on the subject's own thread: /effects test can target another player.
        ctx.scheduler().entity(subject,
                () -> play(style, subject, subject.getLocation(), new Fire(true, true, false, 0)), null);
    }

    private void play(EffectStyle style, Player subject, Location origin, Fire fire) {
        Location centre = origin.clone();
        boolean hasWorldEffects = style.particles() != null || style.sound() != null || style.firework() != null;
        if (hasWorldEffects) {
            Runnable spawn = () -> spawn(style, centre, subject, fire);
            try {
                if (fire.delayTicks() > 0) {
                    ctx.scheduler().regionLater(centre, spawn, fire.delayTicks());
                } else {
                    ctx.scheduler().region(centre, spawn);
                }
            } catch (RuntimeException e) {
                // The world can be unloaded by another plugin between the event and this call.
                logger.warn("Could not schedule effect at {}", centre, e);
            }
        }
        if (fire.showText() && style.hasText()) {
            Runnable text = () -> showText(style, subject);
            try {
                if (fire.delayTicks() > 0) {
                    ctx.scheduler().entityLater(subject, text, null, fire.delayTicks());
                } else {
                    ctx.scheduler().entity(subject, text, null);
                }
            } catch (RuntimeException e) {
                logger.warn("Could not schedule effect text for {}", subject.getName(), e);
            }
        }
    }

    private void spawn(EffectStyle style, Location centre, Player subject, Fire fire) {
        World world = centre.getWorld();
        if (world == null) {
            return;
        }
        List<Player> viewers = viewers(centre, style.radius(), subject, fire.includeSubject());
        if (style.sound() != null) {
            playSound(style.sound(), centre, viewers);
        }
        if (style.firework() != null) {
            spawnFirework(style.firework(), centre);
        }
        if (style.particles() != null) {
            animate(style, centre, subject, fire.includeSubject());
        }
    }

    private void animate(EffectStyle style, Location centre, Player subject, boolean includeSubject) {
        EffectStyle.ParticleSpec spec = style.particles();
        int[] phase = {0};
        TaskHandle[] handle = new TaskHandle[1];
        handle[0] = ctx.track(ctx.scheduler().regionTimer(centre, () -> {
            if (phase[0] >= spec.frames()) {
                handle[0].cancel();
                return;
            }
            List<Player> viewers = viewers(centre, style.radius(), subject, includeSubject);
            if (viewers.isEmpty()) {
                // A player can arrive a tick after a teleport; keep the short animation running without
                // sending packets until somebody is in range. The frame count bounds this work.
                phase[0]++;
                return;
            }
            render(centre, spec, viewers, phase[0]++);
        }, 1, spec.intervalTicks()));
    }

    private void render(Location centre, EffectStyle.ParticleSpec spec, List<Player> viewers, int phase) {
        List<Shapes.Offset> offsets = Shapes.frame(
                spec.shape(), spec.radius(), spec.height(), spec.points(), spec.rotation(), phase);
        for (Shapes.Offset offset : offsets) {
            double x = centre.getX() + offset.x();
            double y = centre.getY() + offset.y();
            double z = centre.getZ() + offset.z();
            for (Player viewer : viewers) {
                spawnParticle(viewer, spec, x, y, z);
            }
        }
    }

    private void spawnParticle(Player viewer, EffectStyle.ParticleSpec spec, double x, double y, double z) {
        ParticleData.Check data = spec.data();
        if (data.dust() != null) {
            viewer.spawnParticle(spec.particle(), x, y, z, spec.count(), 0.05, 0.05, 0.05, spec.speed(), data.dust());
        } else if (data.transition() != null) {
            viewer.spawnParticle(spec.particle(), x, y, z, spec.count(), 0.05, 0.05, 0.05, spec.speed(), data.transition());
        } else if (data.colour() != null) {
            viewer.spawnParticle(spec.particle(), x, y, z, spec.count(), 0.05, 0.05, 0.05, spec.speed(), data.colour());
        } else {
            viewer.spawnParticle(spec.particle(), x, y, z, spec.count(), 0.05, 0.05, 0.05, spec.speed());
        }
    }

    @SuppressWarnings("removal")
    private void playSound(EffectStyle.SoundSpec spec, Location centre, List<Player> viewers) {
        for (Player viewer : viewers) {
            if (spec.enumName()) {
                try {
                    Sound sound = Sound.valueOf(spec.key());
                    viewer.playSound(centre, sound, spec.category(), spec.volume(), spec.pitch());
                } catch (IllegalArgumentException e) {
                    if (unknownSounds.add(spec.key())) {
                        logger.warn("Unknown sound {} in effects.yml; skipping it", spec.key());
                    }
                }
            } else {
                viewer.playSound(centre, spec.key(), spec.category(), spec.volume(), spec.pitch());
            }
        }
    }

    private void spawnFirework(EffectStyle.FireworkSpec spec, Location centre) {
        World world = centre.getWorld();
        if (world == null) {
            return;
        }
        try {
            Firework firework = world.spawn(centre, Firework.class, spawned -> {
                FireworkMeta meta = spawned.getFireworkMeta();
                meta.addEffect(spec.effect());
                meta.setPower(spec.power());
                spawned.setFireworkMeta(meta);
                spawned.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
                spawned.setTicksToDetonate(Math.max(1, spec.detonateAfterTicks()));
            });
            long delay = Math.max(1, spec.detonateAfterTicks());
            ctx.scheduler().entityLater(firework, () -> {
                if (!firework.isDetonated()) {
                    firework.detonate();
                }
            }, null, delay);
            ctx.scheduler().entityLater(firework, () -> {
                if (firework.isValid()) {
                    firework.remove();
                }
            }, null, delay + 2);
        } catch (RuntimeException e) {
            // A world edit or a plugin can remove the chunk between the region task and the spawn.
            logger.warn("Could not spawn effect firework at {}", centre, e);
        }
    }

    private void showText(EffectStyle style, Player subject) {
        TagResolver player = Msg.unparsed("player", subject.getName());
        EffectStyle.TitleSpec title = style.title();
        if (title != null) {
            Component main = title.title().isBlank()
                    ? Component.empty()
                    : ctx.messages().parseTrusted(title.title(), player);
            Component sub = title.subtitle().isBlank()
                    ? Component.empty()
                    : ctx.messages().parseTrusted(title.subtitle(), player);
            subject.showTitle(Title.title(main, sub, Title.Times.times(
                    Duration.ofMillis(title.fadeInTicks() * 50L),
                    Duration.ofMillis(title.stayTicks() * 50L),
                    Duration.ofMillis(title.fadeOutTicks() * 50L))));
        }
        if (style.actionBar() != null && !style.actionBar().isBlank()) {
            subject.sendActionBar(ctx.messages().parseTrusted(style.actionBar(), player));
        }
    }

    /**
     * Players owned by the current region within the effect radius. Runs on the owning region thread and
     * uses the region-local nearby lookup instead of scanning every player of the world; only players whose
     * region is the current one receive packets.
     */
    private List<Player> viewers(Location centre, double radius, Player subject, boolean includeSubject) {
        List<Player> out = new ArrayList<>();
        if (centre.getWorld() == null) {
            return out;
        }
        for (Player viewer : centre.getNearbyPlayers(radius)) {
            if (!viewer.isOnline() || !Bukkit.isOwnedByCurrentRegion(viewer)) {
                continue;
            }
            if (!includeSubject && viewer.getUniqueId().equals(subject.getUniqueId())) {
                continue;
            }
            out.add(viewer);
        }
        return out;
    }

    /** Whether the subject currently has vanish on, when the vanish module is present. */
    boolean isVanished(Player player) {
        return vanish != null && vanish.isVanished(player.getUniqueId());
    }
}
