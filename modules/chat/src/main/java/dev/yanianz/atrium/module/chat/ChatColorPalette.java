package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

/**
 * Per-player default chat colours and gradients.
 *
 * <p>A colour is only usable when the player has {@code atrium.chatcolor.<name>}; a gradient additionally
 * requires {@code atrium.chatcolor.gradient}. Unknown names and entries removed from the config resolve to
 * an empty result. The style is applied to the component, never by wrapping the raw player text in
 * MiniMessage tags, so player text can never turn into a tag.
 */
final class ChatColorPalette {
    static final String GRADIENT_PERMISSION = "atrium.chatcolor.gradient";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final int MAX_STOPS = 16;

    record Choice(String name, boolean gradient, List<TextColor> colors) {
        Choice {
            colors = List.copyOf(colors);
            if (colors.isEmpty()) {
                throw new IllegalArgumentException("A chat colour needs at least one colour");
            }
        }

        TextColor primary() {
            return colors.getFirst();
        }
    }

    private final List<Choice> choices;
    private final Set<String> names;

    ChatColorPalette(List<Choice> choices) {
        this.choices = List.copyOf(choices);
        Set<String> unique = new LinkedHashSet<>();
        for (Choice choice : this.choices) {
            unique.add(choice.name());
        }
        this.names = Set.copyOf(unique);
    }

    static ChatColorPalette compile(ChatConfig.ChatColors config, Consumer<String> warn) {
        if (config == null || !config.enabled || config.entries == null) {
            return new ChatColorPalette(List.of());
        }
        List<Choice> compiled = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ChatConfig.ColorEntry raw : config.entries) {
            if (raw == null || raw.name == null) {
                continue;
            }
            String name = raw.name.strip().toLowerCase(Locale.ROOT);
            if (!NAME.matcher(name).matches()) {
                warn.accept("Ignoring chat colour '" + raw.name + "': names may only use a-z, 0-9, '_' and '-'");
                continue;
            }
            if (!seen.add(name)) {
                warn.accept("Ignoring duplicate chat colour '" + name + "'");
                continue;
            }
            boolean gradient = "GRADIENT".equalsIgnoreCase(raw.type == null ? "" : raw.type.strip());
            List<TextColor> stops = new ArrayList<>();
            if (gradient) {
                if (raw.colors != null) {
                    for (String stop : raw.colors) {
                        Optional<TextColor> parsed = parse(stop);
                        if (parsed.isEmpty()) {
                            warn.accept("Ignoring colour stop '" + stop + "' of gradient '" + name + "'");
                            continue;
                        }
                        stops.add(parsed.get());
                        if (stops.size() >= MAX_STOPS) {
                            break;
                        }
                    }
                }
            } else {
                parse(raw.value).ifPresent(stops::add);
                if (stops.isEmpty() && raw.colors != null && !raw.colors.isEmpty()) {
                    parse(raw.colors.getFirst()).ifPresent(stops::add);
                }
            }
            if (stops.isEmpty()) {
                warn.accept("Ignoring chat colour '" + name + "': no valid colour was configured");
                continue;
            }
            compiled.add(new Choice(name, gradient, stops));
        }
        return new ChatColorPalette(compiled);
    }

    List<Choice> choices() {
        return choices;
    }

    boolean isEmpty() {
        return choices.isEmpty();
    }

    /** Resolves a stored name against the current whitelist and the sender's effective permissions. */
    Optional<Choice> resolve(String name, Predicate<String> hasPermission) {
        if (name == null || name.isBlank() || hasPermission == null) {
            return Optional.empty();
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        for (Choice choice : choices) {
            if (!choice.name().equals(key)) {
                continue;
            }
            if (!hasPermission.test(permission(choice.name()))) {
                return Optional.empty();
            }
            if (choice.gradient() && !hasPermission.test(GRADIENT_PERMISSION)) {
                return Optional.empty();
            }
            return Optional.of(choice);
        }
        return Optional.empty();
    }

    Optional<Choice> byName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String key = name.strip().toLowerCase(Locale.ROOT);
        for (Choice choice : choices) {
            if (choice.name().equals(key)) {
                return Optional.of(choice);
            }
        }
        return Optional.empty();
    }

    static String permission(String name) {
        return "atrium.chatcolor." + name;
    }

    /**
     * Applies the colour as a parent style. Children with an explicit colour (for example a player with
     * colour permission who typed {@code &c}) keep their own colour.
     */
    static Component apply(Component base, Choice choice) {
        if (base == null || choice == null) {
            return base;
        }
        if (!choice.gradient()) {
            return base.color(choice.primary());
        }
        if (!isSimple(base)) {
            // Complex components (links, hover, items) cannot be split safely: use the first stop.
            return base.color(choice.primary());
        }
        TextComponent text = (TextComponent) base;
        if (text.content().isEmpty()) {
            return base.color(choice.primary());
        }
        int[] codePoints = text.content().codePoints().toArray();
        List<TextColor> colors = interpolate(choice.colors(), codePoints.length);
        TextComponent.Builder builder = Component.text().style(text.style().color(null));
        for (int i = 0; i < codePoints.length; i++) {
            builder.append(Component.text(new String(Character.toChars(codePoints[i]))).color(colors.get(i)));
        }
        return builder.build();
    }

    /** Evenly distributes the gradient stops over {@code count} visible code points. */
    static List<TextColor> interpolate(List<TextColor> stops, int count) {
        if (count <= 0) {
            return List.of();
        }
        if (stops == null || stops.isEmpty()) {
            return List.of(NamedTextColor.WHITE);
        }
        List<TextColor> out = new ArrayList<>(count);
        if (stops.size() == 1 || count == 1) {
            TextColor only = stops.getFirst();
            for (int i = 0; i < count; i++) {
                out.add(only);
            }
            return out;
        }
        int segments = stops.size() - 1;
        for (int i = 0; i < count; i++) {
            double position = (double) i * segments / (count - 1);
            int segment = Math.min(segments - 1, (int) Math.floor(position));
            double fraction = position - segment;
            out.add(mix(stops.get(segment), stops.get(segment + 1), fraction));
        }
        return out;
    }

    static Optional<TextColor> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.startsWith("#")) {
            try {
                return Optional.ofNullable(TextColor.fromHexString(value));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT)));
    }

    private static boolean isSimple(Component component) {
        return component instanceof TextComponent text
                && text.children().isEmpty()
                && text.clickEvent() == null
                && text.hoverEvent() == null
                && text.insertion() == null;
    }

    private static TextColor mix(TextColor from, TextColor to, double fraction) {
        int a = from.value();
        int b = to.value();
        double t = Math.max(0.0, Math.min(1.0, fraction));
        int red = channel(a, 16, b, t);
        int green = channel(a, 8, b, t);
        int blue = channel(a, 0, b, t);
        return TextColor.color(red, green, blue);
    }

    private static int channel(int from, int shift, int to, double t) {
        int start = (from >> shift) & 0xFF;
        int end = (to >> shift) & 0xFF;
        return (int) Math.round(start + (end - start) * t);
    }
}
