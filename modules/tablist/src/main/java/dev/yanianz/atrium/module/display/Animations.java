package dev.yanianz.atrium.module.display;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The set of animations of one module. {@link #framesAt(long)} is computed once per update on the global
 * timer and handed (immutable) to every per-player render task.
 */
public final class Animations {
    /** {@code <anim:name>} in a template is replaced by the current frame of that animation. */
    private static final Pattern TAG = Pattern.compile("<anim:([A-Za-z0-9_.-]+)>");

    private final Map<String, Animation> animations;

    private Animations(Map<String, Animation> animations) {
        this.animations = Map.copyOf(animations);
    }

    public static Animations empty() {
        return new Animations(Map.of());
    }

    /**
     * Builds animations from config. Invalid names are skipped and reported. A frame that itself contains
     * {@code <anim:...>} is kept literally (substitution is single-pass), so animations can never recurse.
     */
    public static Animations from(Map<String, AnimationConfig> config, BiConsumer<String, String> problems) {
        Map<String, Animation> out = new LinkedHashMap<>();
        if (config != null) {
            config.forEach((rawName, value) -> {
                String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
                if (!name.matches("[a-z0-9_.-]+")) {
                    problems.accept(String.valueOf(rawName), "animation names may only use a-z, 0-9, _, . and -");
                    return;
                }
                if (value == null || value.frames == null || value.frames.isEmpty()) {
                    problems.accept(name, "animation has no frames");
                    return;
                }
                if (value.frameTicks != null && !value.frameTicks.isEmpty() && value.frameTicks.size() != value.frames.size()) {
                    problems.accept(name, "frame-ticks must list one value per frame; using interval-ticks for all frames");
                }
                out.put(name, Animation.of(name, value.frames, value.intervalTicks, value.frameTicks));
            });
        }
        return new Animations(out);
    }

    public boolean isEmpty() {
        return animations.isEmpty();
    }

    /** Current frame text of every animation at {@code tick}. */
    public Map<String, String> framesAt(long tick) {
        Map<String, String> frames = new HashMap<>(animations.size() * 2);
        animations.forEach((name, animation) -> frames.put(name, animation.frameAt(tick)));
        return Map.copyOf(frames);
    }

    /**
     * Replaces every {@code <anim:name>} with the given frame (single pass, unknown names become empty).
     * Both template and frames are admin-written, so this is plain text substitution of trusted text.
     */
    public static String substitute(String template, Map<String, String> frames) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (!template.contains("<anim:")) {
            return template;
        }
        Matcher matcher = TAG.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 32);
        while (matcher.find()) {
            String frame = frames.getOrDefault(matcher.group(1).toLowerCase(Locale.ROOT), "");
            matcher.appendReplacement(out, Matcher.quoteReplacement(frame));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
