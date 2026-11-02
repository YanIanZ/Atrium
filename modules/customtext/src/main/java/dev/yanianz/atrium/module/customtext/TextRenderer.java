package dev.yanianz.atrium.module.customtext;

import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Renders text pages for one viewer. Must be called on the viewer's thread (or the global thread for the
 * console), because {@link Player#getWorld()} and PlaceholderAPI are not thread-safe.
 *
 * <p>Order: PlaceholderAPI tokens are resolved on the admin template first and their values are escaped, then
 * MiniMessage parses the result with {@code <player>}, {@code <online>} and {@code <world>} unparsed. No value
 * is ever parsed twice, so a nickname such as {@code <red>Steve} cannot inject formatting.
 */
final class TextRenderer {
    private static final String PAPI_PLUGIN = "PlaceholderAPI";

    private final Messages messages;
    private final Logger logger;
    private final Set<String> reportedTokens = ConcurrentHashMap.newKeySet();

    TextRenderer(Messages messages, Logger logger) {
        this.messages = messages;
        this.logger = logger;
    }

    static boolean papiEnabled() {
        // Checked on every render: PlaceholderAPI can be disabled or reloaded at runtime.
        return Bukkit.getPluginManager().isPluginEnabled(PAPI_PLUGIN);
    }

    /** Sends one page plus (optionally) the clickable prev/next line. */
    void send(Audience audience, @Nullable Player viewer, TextDefinition text, int requestedPage,
            boolean navigation) {
        int pageCount = PageMath.pageCount(text.pages());
        int page = PageMath.clampPage(requestedPage, pageCount);
        List<String> lines = text.pages().get(page - 1);
        for (String line : lines) {
            audience.sendMessage(render(line, viewer));
        }
        if (navigation && pageCount > 1) {
            audience.sendMessage(navigation(audience, text.name(), page, pageCount));
        }
    }

    Component render(String template, @Nullable Player viewer) {
        String prepared = template;
        if (template.indexOf('%') >= 0 && viewer != null && papiEnabled()) {
            prepared = PlaceholderSubstitution.substituteOnce(template, token -> resolve(viewer, token));
        }
        String playerName = viewer == null ? "Console" : viewer.getName();
        String world = viewer == null ? "-" : viewer.getWorld().getName();
        return messages.parseTrusted(prepared,
                Msg.unparsed("player", playerName),
                Msg.number("online", Bukkit.getOnlinePlayers().size()),
                Msg.unparsed("world", world));
    }

    private String resolve(Player viewer, String token) {
        try {
            String value = PapiHook.resolve(viewer, token);
            return value == null ? "" : value;
        } catch (RuntimeException | LinkageError e) {
            if (reportedTokens.add(token)) {
                logger.warn("PlaceholderAPI failed to resolve %{}% for {}; it is shown literally", token,
                        viewer.getName(), e);
            }
            return "%" + token + "%";
        }
    }

    private Component navigation(Audience audience, String name, int page, int pages) {
        // The command is built from a validated file name and integer page numbers only.
        Component prev = page > 1
                ? button(audience, "customtext.nav-prev", "customtext.nav-prev-hover", name, page - 1)
                : messages.render(audience, "customtext.nav-prev-off");
        Component next = page < pages
                ? button(audience, "customtext.nav-next", "customtext.nav-next-hover", name, page + 1)
                : messages.render(audience, "customtext.nav-next-off");
        Component counter = messages.render(audience, "customtext.nav-page",
                Msg.number("page", page), Msg.number("pages", pages));
        return messages.render(audience, "customtext.page-nav",
                Msg.component("prev", prev), Msg.component("page", counter), Msg.component("next", next));
    }

    private Component button(Audience audience, String labelKey, String hoverKey, String name, int targetPage) {
        Component label = messages.render(audience, labelKey);
        Component hover = messages.render(audience, hoverKey, Msg.number("page", targetPage));
        return label
                .clickEvent(ClickEvent.runCommand("/ctext " + name + " " + targetPage))
                .hoverEvent(HoverEvent.showText(hover));
    }
}
