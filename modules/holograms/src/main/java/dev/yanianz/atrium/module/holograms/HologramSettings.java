package dev.yanianz.atrium.module.holograms;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

/** Immutable set of {@link Setting} values. Every setting always has a value. */
final class HologramSettings {
    private static final HologramSettings BUILT_IN = builtIn();

    private final EnumMap<Setting, Object> values;

    private HologramSettings(EnumMap<Setting, Object> values) {
        this.values = values;
    }

    private static HologramSettings builtIn() {
        EnumMap<Setting, Object> map = new EnumMap<>(Setting.class);
        for (Setting setting : Setting.values()) {
            map.put(setting, setting.parse(setting.defaultValue));
        }
        return new HologramSettings(map);
    }

    static HologramSettings builtInDefaults() {
        return BUILT_IN;
    }

    /** A copy with one value changed. Throws {@link IllegalArgumentException} for an invalid value. */
    HologramSettings with(Setting setting, String raw) {
        Object parsed = setting.parse(raw);
        EnumMap<Setting, Object> copy = new EnumMap<>(values);
        copy.put(setting, parsed);
        return new HologramSettings(copy);
    }

    Object get(Setting setting) {
        return values.get(setting);
    }

    String formatted(Setting setting) {
        return setting.format(values.get(setting));
    }

    /** {@code key=value} per line, in declaration order. Locale-independent. */
    String encode() {
        StringBuilder out = new StringBuilder();
        for (Setting setting : Setting.values()) {
            out.append(setting.key).append('=').append(formatted(setting)).append('\n');
        }
        return out.toString();
    }

    /**
     * Reads {@link #encode()} on top of {@code base}. Unknown keys and invalid values keep the base value and
     * are reported to {@code problems} (key, reason).
     */
    static HologramSettings decode(String text, HologramSettings base, BiConsumer<String, String> problems) {
        EnumMap<Setting, Object> map = new EnumMap<>(base.values);
        if (text != null) {
            for (String line : text.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    problems.accept(line, "not key=value");
                    continue;
                }
                String key = line.substring(0, eq);
                var setting = Setting.byKey(key);
                if (setting.isEmpty()) {
                    problems.accept(key, "unknown setting");
                    continue;
                }
                try {
                    map.put(setting.get(), setting.get().parse(line.substring(eq + 1)));
                } catch (IllegalArgumentException e) {
                    problems.accept(key, e.getMessage());
                }
            }
        }
        return new HologramSettings(map);
    }

    Map<Setting, Object> asMap() {
        return java.util.Collections.unmodifiableMap(values);
    }

    // ---- typed accessors ----

    Display.Billboard billboard() {
        return (Display.Billboard) values.get(Setting.BILLBOARD);
    }

    float scale() {
        return (Float) values.get(Setting.SCALE);
    }

    /** ARGB, or {@code null} for the vanilla default background. */
    Integer backgroundArgb() {
        return Setting.backgroundArgb((String) values.get(Setting.BACKGROUND));
    }

    boolean shadow() {
        return (Boolean) values.get(Setting.SHADOW);
    }

    boolean seeThrough() {
        return (Boolean) values.get(Setting.SEE_THROUGH);
    }

    float lineSpacing() {
        return (Float) values.get(Setting.LINE_SPACING);
    }

    TextDisplay.TextAlignment alignment() {
        return (TextDisplay.TextAlignment) values.get(Setting.ALIGNMENT);
    }

    float viewRange() {
        return (Float) values.get(Setting.VIEW_RANGE);
    }

    int lineWidth() {
        return (Integer) values.get(Setting.LINE_WIDTH);
    }

    int updateInterval() {
        return (Integer) values.get(Setting.UPDATE_INTERVAL);
    }

    int pageInterval() {
        return (Integer) values.get(Setting.PAGE_INTERVAL);
    }

    String permission() {
        return (String) values.get(Setting.PERMISSION);
    }

    String clickPermission() {
        return (String) values.get(Setting.CLICK_PERMISSION);
    }

    int clickCooldownMillis() {
        return (Integer) values.get(Setting.CLICK_COOLDOWN);
    }

    Setting.PerViewer perViewer() {
        return (Setting.PerViewer) values.get(Setting.PER_VIEWER);
    }

    boolean clickPages() {
        return (Boolean) values.get(Setting.CLICK_PAGES);
    }

    float interactionWidth() {
        return (Float) values.get(Setting.INTERACTION_WIDTH);
    }

    float interactionHeight() {
        return (Float) values.get(Setting.INTERACTION_HEIGHT);
    }

    boolean enabled() {
        return (Boolean) values.get(Setting.ENABLED);
    }

    float rotateDegreesPerSecond() {
        return (Float) values.get(Setting.ROTATE_SPEED);
    }

    int fadeTicks() {
        return (Integer) values.get(Setting.FADE_TICKS);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HologramSettings s && s.values.equals(values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return encode().replace('\n', ' ').trim();
    }
}
