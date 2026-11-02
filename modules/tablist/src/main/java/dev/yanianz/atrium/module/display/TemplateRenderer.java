package dev.yanianz.atrium.module.display;

import dev.yanianz.atrium.core.message.Messages;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Renders admin-written display templates for one viewer, on the viewer's thread:
 * {@code <anim:...>} frames → PlaceholderAPI tokens (optional) → MiniMessage with built-in placeholders.
 *
 * <p>Only trusted templates from config files go through here. Player-controlled text must never be
 * passed as a template; it reaches the output only as a literal placeholder value.
 */
public final class TemplateRenderer {
    private static final String PAPI_PLUGIN = "PlaceholderAPI";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final Messages messages;
    private final Logger logger;
    private final Set<String> reportedTokens = ConcurrentHashMap.newKeySet();
    private volatile boolean placeholderApi = true;

    public TemplateRenderer(Messages messages, Logger logger) {
        this.messages = messages;
        this.logger = logger;
    }

    /** Whether PlaceholderAPI tokens in templates are resolved (when the plugin is present). */
    public void placeholderApi(boolean enabled) {
        this.placeholderApi = enabled;
    }

    public static String join(List<String> lines) {
        return lines == null ? "" : String.join("<newline>", lines);
    }

    public Component render(Player viewer, String template, Map<String, String> frames, TagResolver placeholders) {
        String text = Animations.substitute(template, frames);
        if (text.isEmpty()) {
            return Component.empty();
        }
        TagResolver papi = TagResolver.empty();
        if (placeholderApi && text.indexOf('%') >= 0 && papiEnabled()) {
            PapiTokens.Tokenized tokenized = PapiTokens.tokenize(text);
            if (!tokenized.tokens().isEmpty()) {
                text = tokenized.template();
                papi = papiResolver(viewer, tokenized.tokens());
            }
        }
        return messages.parseTrusted(text, placeholders, papi);
    }

    private static boolean papiEnabled() {
        // Checked on every render: PlaceholderAPI can be disabled or reloaded at runtime.
        return Bukkit.getPluginManager().isPluginEnabled(PAPI_PLUGIN);
    }

    private TagResolver papiResolver(Player viewer, List<String> tokens) {
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            Component value;
            try {
                // PlaceholderAPI returns legacy section-coloured text; deserialising it as legacy keeps colours
                // but can never create click/hover events, and the result is never parsed again.
                value = LEGACY.deserialize(PapiHook.resolve(viewer, token));
            } catch (RuntimeException | LinkageError e) {
                if (reportedTokens.add(token)) {
                    logger.warn("PlaceholderAPI failed to resolve %{}% for {}; it is shown literally", token,
                            viewer.getName(), e);
                }
                value = Component.text("%" + token + "%");
            }
            builder.resolver(Placeholder.component(PapiTokens.tagName(i), value));
        }
        return builder.build();
    }
}
