package dev.yanianz.atrium.core.message;

import dev.yanianz.atrium.core.config.CoreConfig;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jspecify.annotations.Nullable;

/**
 * The server-wide colour palette and GUI defaults ({@code theme} in {@code config.yml}).
 *
 * <p>Every palette entry is exposed to admin-written templates as a MiniMessage styling tag, e.g.
 * {@code <primary>Homes</primary> <muted>(3)}. The tags are only part of the resolver used for trusted
 * templates; text inserted with {@link Msg#unparsed(String, String)} is never parsed, so a player typing
 * {@code <error>} sees it literally. Placeholders passed to a single render call win over theme tags with
 * the same name (several modules pass an {@code <error>} placeholder).
 *
 * <p>Immutable and pure (no server access), so it can be unit-tested.
 */
public final class Theme {
    /** Tag names, in the order they appear in config.yml. */
    public static final List<String> TAGS =
            List.of("primary", "secondary", "accent", "muted", "success", "warning", "error", "highlight");

    /**
     * GUI defaults. Materials and sounds are kept as strings so this record stays server-independent;
     * the menu service resolves them. Empty sound = silent; empty filler = no filler.
     */
    public record Gui(String filler, @Nullable Key openSound, @Nullable Key clickSound, @Nullable Key errorSound,
            float soundVolume, long clickDebounceMillis) {
        public static Gui defaults() {
            return new Gui("GRAY_STAINED_GLASS_PANE", Key.key("minecraft:ui.button.click"),
                    Key.key("minecraft:ui.button.click"), Key.key("minecraft:entity.villager.no"), 0.5f, 150);
        }
    }

    private static final Map<String, TextColor> DEFAULT_COLORS = defaultColors();

    private final Map<String, TextColor> colors;
    private final String prefix;
    private final Gui gui;
    private final TagResolver tags;

    private Theme(Map<String, TextColor> colors, String prefix, Gui gui) {
        this.colors = Collections.unmodifiableMap(new LinkedHashMap<>(colors));
        this.prefix = prefix;
        this.gui = gui;
        TagResolver.Builder builder = TagResolver.builder();
        this.colors.forEach((name, color) -> builder.tag(name, Tag.styling(color)));
        this.tags = builder.build();
    }

    /** The built-in palette (matches the default prefix gradient). */
    public static Theme defaults() {
        return new Theme(DEFAULT_COLORS, "", Gui.defaults());
    }

    /**
     * Builds a theme from config. Invalid values fall back to the default for that entry and are reported
     * through {@code warn} (one line per problem), so a typo never breaks every message.
     */
    public static Theme from(CoreConfig.ThemeSettings settings, Consumer<String> warn) {
        if (settings == null) {
            return defaults();
        }
        Map<String, TextColor> colors = new LinkedHashMap<>();
        String[] values = {settings.primary, settings.secondary, settings.accent, settings.muted,
                settings.success, settings.warning, settings.error, settings.highlight};
        for (int i = 0; i < TAGS.size(); i++) {
            String name = TAGS.get(i);
            TextColor parsed = parseColor(values[i]);
            if (parsed == null) {
                warn.accept("theme." + name + ": '" + values[i] + "' is not a hex (#RRGGBB) or named colour; using the default");
                parsed = DEFAULT_COLORS.get(name);
            }
            colors.put(name, parsed);
        }
        Gui gui = gui(settings.gui, warn);
        return new Theme(colors, settings.prefix == null ? "" : settings.prefix, gui);
    }

    private static Gui gui(CoreConfig.@Nullable GuiSettings settings, Consumer<String> warn) {
        Gui fallback = Gui.defaults();
        if (settings == null) {
            return fallback;
        }
        float volume = (float) Math.max(0.0, Math.min(1.0, settings.soundVolume));
        long debounce = Math.max(0, settings.clickDebounceMillis);
        return new Gui(settings.filler == null ? "" : settings.filler.trim(),
                sound("theme.gui.open-sound", settings.openSound, fallback.openSound(), warn),
                sound("theme.gui.click-sound", settings.clickSound, fallback.clickSound(), warn),
                sound("theme.gui.error-sound", settings.errorSound, fallback.errorSound(), warn),
                volume, debounce);
    }

    private static @Nullable Key sound(String path, @Nullable String value, @Nullable Key fallback, Consumer<String> warn) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Key.key(value.trim().toLowerCase(Locale.ROOT));
        } catch (RuntimeException e) {
            warn.accept(path + ": '" + value + "' is not a valid sound key; using the default");
            return fallback;
        }
    }

    /** Parses {@code #RRGGBB}, {@code RRGGBB} or a named colour ({@code gray}, {@code dark_aqua}); null if invalid. */
    public static @Nullable TextColor parseColor(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.matches("#?[0-9a-fA-F]{6}")) {
            return TextColor.fromHexString(v.startsWith("#") ? v : "#" + v);
        }
        String name = v.toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (name.equals("grey")) {
            name = "gray";
        } else if (name.equals("dark_grey")) {
            name = "dark_gray";
        }
        return NamedTextColor.NAMES.value(name);
    }

    /** Resolver with one styling tag per palette entry. */
    public TagResolver tags() {
        return tags;
    }

    /** Colour of a palette entry, e.g. {@code color("primary")}; the default if the name is unknown. */
    public TextColor color(String name) {
        TextColor color = colors.get(name);
        return color != null ? color : DEFAULT_COLORS.getOrDefault(name, NamedTextColor.WHITE);
    }

    public Map<String, TextColor> colors() {
        return colors;
    }

    /** Admin prefix template; empty means "use the lang file's core.prefix". */
    public String prefix() {
        return prefix;
    }

    public Gui gui() {
        return gui;
    }

    private static Map<String, TextColor> defaultColors() {
        Map<String, TextColor> map = new LinkedHashMap<>();
        map.put("primary", TextColor.color(0x5FB3A1));
        map.put("secondary", TextColor.color(0x3B7F9A));
        map.put("accent", TextColor.color(0xF2C46D));
        map.put("muted", NamedTextColor.GRAY);
        map.put("success", TextColor.color(0x6BCB77));
        map.put("warning", TextColor.color(0xF5A65B));
        map.put("error", TextColor.color(0xE5534B));
        map.put("highlight", NamedTextColor.WHITE);
        return Collections.unmodifiableMap(map);
    }
}
