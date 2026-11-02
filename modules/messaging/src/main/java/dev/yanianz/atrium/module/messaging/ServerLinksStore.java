package dev.yanianz.atrium.module.messaging;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.ServerLinks;
import org.slf4j.Logger;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Links shown in the multiplayer screen (Paper's server link API), stored in
 * {@code plugins/Atrium/modules/messaging/serverlinks.yml}.
 *
 * <p>Only links this store added are removed again, so links from {@code server.properties} or another plugin
 * are never touched. A typed link (website, support, ...) uses the client's own label; a custom label is sent as
 * a plain component, never as MiniMessage, because the label is typed on the command line.
 */
final class ServerLinksStore {
    /** One configured link. {@code type} empty means a custom label. */
    record Link(String label, String url, String type) {
        static final int MAX_LABEL = 48;

        Link {
            label = label == null ? "" : label.strip();
            url = url == null ? "" : url.strip();
            type = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
        }

        boolean valid() {
            if (label.isEmpty() || label.length() > MAX_LABEL) {
                return false;
            }
            return parseUrl(url).isPresent();
        }

        /** Normalises the URL: only http(s) with a host, adding {@code https://} when no scheme was typed. */
        static Optional<URI> parseUrl(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            String value = raw.strip();
            if (!value.contains("://")) {
                value = "https://" + value;
            }
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if (!scheme.equals("http") && !scheme.equals("https")) {
                    return Optional.empty();
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(uri);
            } catch (URISyntaxException e) {
                return Optional.empty();
            }
        }
    }

    private final Path file;
    private final Logger logger;
    private final List<Link> links = new ArrayList<>();
    private final List<ServerLinks.ServerLink> applied = new ArrayList<>();

    ServerLinksStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    synchronized List<Link> links() {
        return List.copyOf(links);
    }

    /** Reads the file; a missing file is not an error. Blocking. */
    synchronized void load() throws IOException {
        links.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().load();
        } catch (ConfigurateException e) {
            throw new IOException("cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        for (ConfigurationNode node : root.node("links").childrenList()) {
            Link link = new Link(node.node("label").getString(""), node.node("url").getString(""),
                    node.node("type").getString(""));
            if (!link.valid()) {
                logger.warn("Server link '{}' skipped: a label of 1-{} characters and an http(s) URL are required",
                        link.label(), Link.MAX_LABEL);
                continue;
            }
            links.add(link);
        }
    }

    /** Adds or replaces a link with the same label (case-insensitive). Blocking. */
    synchronized void put(Link link) throws IOException {
        links.removeIf(existing -> existing.label().equalsIgnoreCase(link.label()));
        links.add(link);
        save();
    }

    /** Removes a link by label (case-insensitive); returns false when it was not found. Blocking. */
    synchronized boolean remove(String label) throws IOException {
        boolean removed = links.removeIf(existing -> existing.label().equalsIgnoreCase(label));
        if (removed) {
            save();
        }
        return removed;
    }

    synchronized void clear() throws IOException {
        links.clear();
        save();
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().createNode();
            ConfigurationNode section = root.node("links");
            for (Link link : links) {
                ConfigurationNode node = section.appendListNode();
                node.node("label").set(link.label());
                node.node("url").set(link.url());
                if (!link.type().isEmpty()) {
                    node.node("type").set(link.type());
                }
            }
            YamlConfigurationLoader.builder().path(file).build().save(root);
        } catch (ConfigurateException e) {
            throw new IOException("cannot write " + file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Replaces the links this store added with the configured ones. Returns how many links are shown now.
     * Must be called on the global region thread (the server link registry is shared).
     */
    synchronized int apply(ServerLinks target) {
        for (ServerLinks.ServerLink link : applied) {
            target.removeLink(link);
        }
        applied.clear();
        for (Link link : links) {
            Optional<URI> uri = Link.parseUrl(link.url());
            if (uri.isEmpty()) {
                continue;
            }
            try {
                ServerLinks.ServerLink added;
                if (link.type().isEmpty()) {
                    added = target.addLink(Component.text(link.label()), uri.get());
                } else {
                    Optional<ServerLinks.Type> type = type(link.type());
                    if (type.isPresent()) {
                        added = target.setLink(type.get(), uri.get());
                    } else {
                        logger.warn("Server link '{}' has unknown type '{}'; using a custom label instead",
                                link.label(), link.type());
                        added = target.addLink(Component.text(link.label()), uri.get());
                    }
                }
                if (added != null) {
                    applied.add(added);
                }
            } catch (RuntimeException e) {
                logger.warn("Server link '{}' could not be added", link.label(), e);
            }
        }
        return applied.size();
    }

    private static Optional<ServerLinks.Type> type(String name) {
        for (ServerLinks.Type type : ServerLinks.Type.values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
