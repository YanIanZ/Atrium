package dev.yanianz.atrium.module.particles;

import dev.yanianz.atrium.module.particles.shape.Style;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Persisted per-player particle state: active effects plus the two visibility flags. */
public record ParticleProfile(List<ParticleEffect> effects, boolean hideOthers, boolean hidden) {
    public static final ParticleProfile EMPTY = new ParticleProfile(List.of(), false, false);

    public ParticleProfile {
        effects = List.copyOf(effects);
    }

    public @Nullable ParticleEffect effect(Style style) {
        for (ParticleEffect effect : effects) {
            if (effect.style() == style) {
                return effect;
            }
        }
        return null;
    }

    public ParticleProfile plus(ParticleEffect effect) {
        List<ParticleEffect> copy = new ArrayList<>(effects);
        copy.removeIf(existing -> existing.style() == effect.style());
        copy.add(effect);
        return new ParticleProfile(copy, hideOthers, hidden);
    }

    public ParticleProfile without(Style style) {
        List<ParticleEffect> copy = new ArrayList<>(effects);
        copy.removeIf(existing -> existing.style() == style);
        return new ParticleProfile(copy, hideOthers, hidden);
    }

    public ParticleProfile withoutAll() {
        return new ParticleProfile(List.of(), hideOthers, hidden);
    }

    public ParticleProfile withHideOthers(boolean value) {
        return new ParticleProfile(effects, value, hidden);
    }

    public ParticleProfile withHidden(boolean value) {
        return new ParticleProfile(effects, hideOthers, value);
    }

    /**
     * Keeps at most {@code slots} effects, in their current order. Used when the slot permission drops
     * (a rank loss) and on join, so stale effects are neither rendered nor kept forever.
     */
    public ParticleProfile limit(int slots) {
        int keep = Math.max(0, Math.min(slots, effects.size()));
        if (keep == effects.size()) {
            return this;
        }
        return new ParticleProfile(effects.subList(0, keep), hideOthers, hidden);
    }

    /**
     * Adds or replaces an effect. When every slot is used the profile is returned unchanged and
     * {@link AddResult#full()} is true.
     */
    public static AddResult add(ParticleProfile current, ParticleEffect effect, int slots) {
        if (current.effect(effect.style()) != null) {
            return new AddResult(current.plus(effect), false, true);
        }
        if (current.effects().size() >= slots) {
            return new AddResult(current, true, false);
        }
        return new AddResult(current.plus(effect), false, false);
    }

    public record AddResult(ParticleProfile profile, boolean full, boolean replaced) {}
}
