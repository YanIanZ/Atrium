package dev.yanianz.atrium.module.display;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** One animation in a display module config. Use it in templates as {@code <anim:name>}. */
@ConfigSerializable
public class AnimationConfig {
    @Comment("Ticks each frame stays visible (20 ticks = 1 second). Minimum 1.")
    public int intervalTicks = 10;

    @Comment("Optional per-frame durations in ticks, one entry per frame. Leave empty to use interval-ticks.")
    public List<Integer> frameTicks = new ArrayList<>();

    @Comment("Frames (MiniMessage). Frames may use the same placeholders as the template but not <anim:...>.")
    public List<String> frames = new ArrayList<>();

    public static AnimationConfig of(int intervalTicks, List<Integer> frameTicks, String... frames) {
        AnimationConfig config = new AnimationConfig();
        config.intervalTicks = intervalTicks;
        config.frameTicks = new ArrayList<>(frameTicks);
        config.frames = new ArrayList<>(List.of(frames));
        return config;
    }
}
