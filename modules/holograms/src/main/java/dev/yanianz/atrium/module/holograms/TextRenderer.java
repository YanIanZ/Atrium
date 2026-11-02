package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.core.message.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Renders admin line templates. PlaceholderAPI is optional: it is only called while the plugin is enabled,
 * and only for admin templates. A viewer of {@code null} renders the shared (non-personal) text.
 */
final class TextRenderer {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR).hexColors().useUnusualXRepeatedCharacterHexFormat().build();

    private final Messages messages;
    private final Logger logger;
    private final Map<String, PlaceholderText.Prepared> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> failedTokens = ConcurrentHashMap.newKeySet();

    TextRenderer(Messages messages, Logger logger) {
        this.messages = messages;
        this.logger = logger;
    }

    boolean papiEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    private PlaceholderText.Prepared prepared(String template) {
        if (cache.size() > 4096) {
            cache.clear();
        }
        return cache.computeIfAbsent(template, PlaceholderText::prepare);
    }

    /** One multi-line component for a merged text element, with the per-line hover and click events. */
    Component render(List<HoloLine> lines, @Nullable Player viewer) {
        List<Component> parts = new ArrayList<>(lines.size());
        for (HoloLine line : lines) {
            parts.add(renderLine(line, viewer));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), parts);
    }

    /** One line including its optional hover text and click command. */
    Component renderLine(HoloLine line, @Nullable Player viewer) {
        Component component = switch (line.content()) {
            case LineContent.Text text -> renderLine(text.template(), viewer);
            default -> Component.text(line.raw());
        };
        return withEvents(component, line, viewer);
    }

    private Component withEvents(Component component, HoloLine line, @Nullable Player viewer) {
        Component result = component;
        if (!line.hover().isEmpty()) {
            result = result.hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                    renderLine(line.hover(), viewer)));
        }
        if (!line.click().isEmpty() && viewer != null) {
            java.util.Optional<String> command = ClickAction.fill(line.click(), viewer.getName(),
                    viewer.getUniqueId());
            if (command.isPresent()) {
                result = result.clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/" + command.get()));
            }
        }
        return result;
    }

    Component renderLine(String template, @Nullable Player viewer) {
        PlaceholderText.Prepared prepared = prepared(template);
        List<TagResolver> resolvers = new ArrayList<>(prepared.tokens().size() + 1);
        boolean papi = !prepared.tokens().isEmpty() && papiEnabled();
        for (int i = 0; i < prepared.tokens().size(); i++) {
            String token = prepared.tokens().get(i);
            Component value = papi ? papi(token, viewer) : Component.text('%' + token + '%');
            resolvers.add(Placeholder.component(PlaceholderText.TAG_PREFIX + i, value));
        }
        resolvers.add(Placeholder.unparsed("viewer", viewer == null ? "" : viewer.getName()));
        return messages.parseTrusted(prepared.miniMessage(), resolvers.toArray(TagResolver[]::new));
    }

    private Component papi(String token, @Nullable Player viewer) {
        String raw = '%' + token + '%';
        try {
            return LEGACY.deserialize(Papi.apply(viewer, raw));
        } catch (RuntimeException | LinkageError e) {
            if (failedTokens.add(token)) {
                logger.warn("PlaceholderAPI failed to resolve {} (shown literally from now on for this error)", raw, e);
            }
            return Component.text(raw);
        }
    }

    /** Visible characters of a template, for size estimates. Placeholders count as their token text. */
    int visibleChars(String template) {
        PlaceholderText.Prepared prepared = prepared(template);
        List<TagResolver> resolvers = new ArrayList<>();
        for (int i = 0; i < prepared.tokens().size(); i++) {
            resolvers.add(Placeholder.unparsed(PlaceholderText.TAG_PREFIX + i, '%' + prepared.tokens().get(i) + '%'));
        }
        resolvers.add(Placeholder.unparsed("viewer", "MMMMMMMMMMMM"));
        Component component = messages.parseTrusted(prepared.miniMessage(), resolvers.toArray(TagResolver[]::new));
        return PlainTextComponentSerializer.plainText().serialize(component).length();
    }

    /** Isolates the PlaceholderAPI classes: only loaded when a call is actually made. */
    private static final class Papi {
        static String apply(@Nullable Player viewer, String text) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(viewer, text);
        }
    }
}
