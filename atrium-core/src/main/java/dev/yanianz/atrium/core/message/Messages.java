package dev.yanianz.atrium.core.message;

import dev.yanianz.atrium.core.config.ConfigService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * Translated MiniMessage templates.
 *
 * <p>Each module ships defaults at {@code lang/<module>/<locale>.yml} inside the jar. They are copied to
 * {@code plugins/Atrium/lang/<locale>/<module>.yml} where admins edit them; missing keys are added on
 * load. Keys are addressed as {@code <module>.<path>}, e.g. {@code teleport.warmup-started}.
 *
 * <p>Templates are trusted (written by admins). Anything a player typed must be inserted with
 * {@link Msg#unparsed(String, String)}, never parsed as MiniMessage.
 */
public final class Messages {
    public static final List<String> SHIPPED_LOCALES = List.of("en_US", "id_ID");

    private final Path langDirectory;
    private final Function<String, InputStream> resources;
    private final Logger logger;
    private final MiniMessage mini = MiniMessage.miniMessage();
    /** locale -> (key -> template) */
    private final Map<String, Map<String, String>> templates = new ConcurrentHashMap<>();
    private final Set<String> bundles = ConcurrentHashMap.newKeySet();
    private final Set<String> reportedMissing = ConcurrentHashMap.newKeySet();
    private volatile String defaultLocale = "en_US";
    private volatile boolean perPlayerLocale = true;
    private volatile TagResolver globalResolver = TagResolver.empty();
    private volatile Theme theme = Theme.defaults();

    public Messages(Path langDirectory, Function<String, InputStream> resources, Logger logger) {
        this.langDirectory = langDirectory;
        this.resources = resources;
        this.logger = logger;
    }

    public void configure(String defaultLocale, boolean perPlayerLocale) {
        this.defaultLocale = normalize(defaultLocale);
        this.perPlayerLocale = perPlayerLocale;
    }

    /** Loads (or reloads) the bundle of one module for every shipped and every admin-added locale. */
    public void loadBundle(String bundle) {
        bundles.add(bundle);
        for (String locale : localesOnDisk()) {
            loadBundle(bundle, locale);
        }
        refreshGlobals();
    }

    /**
     * Reloads one module for one locale without writing the file back.
     *
     * <p>Used by the locale editor right after it saved a file under its own lock: a full
     * {@link #loadBundle(String)} would read and re-save every locale of the module, so a concurrent
     * edit of another locale could be lost. Missing keys from the bundled defaults are merged in memory
     * only; the file on disk is left exactly as the editor wrote it.
     */
    public void reloadLocale(String bundle, String locale) {
        if (bundle == null || bundle.isBlank() || locale == null || locale.isBlank()) {
            return;
        }
        bundles.add(bundle);
        loadBundle(bundle, locale, false);
        refreshGlobals();
    }

    public void reloadAll() {
        templates.clear();
        reportedMissing.clear();
        for (String bundle : bundles) {
            for (String locale : localesOnDisk()) {
                loadBundle(bundle, locale);
            }
        }
        refreshGlobals();
    }

    private List<String> localesOnDisk() {
        List<String> locales = new ArrayList<>(SHIPPED_LOCALES);
        if (Files.isDirectory(langDirectory)) {
            try (var stream = Files.list(langDirectory)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(name -> !locales.contains(name))
                        .forEach(locales::add);
            } catch (IOException e) {
                logger.warn("Cannot list {}", langDirectory, e);
            }
        }
        return locales;
    }

    private void loadBundle(String bundle, String locale) {
        loadBundle(bundle, locale, true);
    }

    private void loadBundle(String bundle, String locale, boolean persist) {
        Path file = langDirectory.resolve(locale).resolve(bundle + ".yml");
        try {
            CommentedConfigurationNode defaults = null;
            try (InputStream in = resources.apply("lang/" + bundle + "/" + locale + ".yml")) {
                if (in != null) {
                    defaults = ConfigService.read(in);
                }
            }
            if (defaults == null && !Files.exists(file)) {
                return;
            }
            if (persist) {
                Files.createDirectories(file.getParent());
            }
            YamlConfigurationLoader loader = ConfigService.loader(file);
            CommentedConfigurationNode node = loader.load();
            if (defaults != null) {
                node.mergeFrom(defaults);
                if (persist) {
                    loader.save(node);
                }
            }
            Map<String, String> target = templates.computeIfAbsent(locale, k -> new ConcurrentHashMap<>());
            flatten(bundle, node, target);
        } catch (IOException e) {
            logger.error("Cannot load messages {}", file, e);
        }
    }

    private static void flatten(String prefix, ConfigurationNode node, Map<String, String> out) {
        if (node.isMap()) {
            node.childrenMap().forEach((key, child) -> flatten(prefix + "." + key, child, out));
        } else if (node.isList()) {
            List<String> lines = new ArrayList<>();
            for (ConfigurationNode child : node.childrenList()) {
                lines.add(child.getString(""));
            }
            out.put(prefix, String.join("<newline>", lines));
        } else if (!node.virtual()) {
            out.put(prefix, node.getString(""));
        }
    }

    private void refreshGlobals() {
        Theme current = theme;
        TagResolver themeTags = current.tags();
        String prefix = current.prefix().isBlank() ? rawTemplate(defaultLocale, "core.prefix") : current.prefix();
        Component prefixComponent = prefix == null ? Component.empty() : mini.deserialize(prefix, themeTags);
        // Order matters: per-call placeholders are checked first (see render), then <prefix>, then theme tags.
        globalResolver = TagResolver.resolver(
                TagResolver.resolver("prefix", Tag.selfClosingInserting(prefixComponent)), themeTags);
    }

    /**
     * Per-call placeholders first, then {@code <prefix>} and the theme tags. Adventure merges plain named
     * resolvers into one map (last one wins), so the precedence is enforced explicitly here: a module that
     * passes an {@code <error>} placeholder keeps getting its text, not the theme colour.
     */
    private TagResolver withGlobals(TagResolver... resolvers) {
        TagResolver globals = globalResolver;
        if (resolvers.length == 0) {
            return globals;
        }
        TagResolver call = TagResolver.resolver(resolvers);
        return new TagResolver() {
            @Override
            public net.kyori.adventure.text.minimessage.tag.@org.jspecify.annotations.Nullable Tag resolve(
                    String name, net.kyori.adventure.text.minimessage.tag.resolver.ArgumentQueue arguments,
                    net.kyori.adventure.text.minimessage.Context ctx)
                    throws net.kyori.adventure.text.minimessage.ParsingException {
                return call.has(name) ? call.resolve(name, arguments, ctx) : globals.resolve(name, arguments, ctx);
            }

            @Override
            public boolean has(String name) {
                return call.has(name) || globals.has(name);
            }
        };
    }

    /**
     * Installs the colour palette used by the {@code <primary>}, {@code <muted>}, ... tags of every template
     * and rebuilds {@code <prefix>}. Called on startup and by {@code /atrium reload}.
     */
    public void applyTheme(Theme theme) {
        this.theme = theme == null ? Theme.defaults() : theme;
        refreshGlobals();
    }

    /** The active palette and GUI defaults. */
    public Theme theme() {
        return theme;
    }

    /**
     * The resolver applied to every trusted template ({@code <prefix>} plus the theme tags). Useful when a
     * module parses admin MiniMessage with its own {@link MiniMessage} call.
     */
    public TagResolver globalTags() {
        return globalResolver;
    }

    public static String normalize(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en_US";
        }
        String[] parts = locale.replace('-', '_').split("_");
        return parts.length >= 2
                ? parts[0].toLowerCase(Locale.ROOT) + "_" + parts[1].toUpperCase(Locale.ROOT)
                : parts[0].toLowerCase(Locale.ROOT);
    }

    /** The locale used for an audience: the player's client locale if translated, else the default. */
    public String localeOf(Audience audience) {
        if (perPlayerLocale && audience instanceof Player player) {
            String wanted = normalize(player.locale().toString());
            if (templates.containsKey(wanted)) {
                return wanted;
            }
            String language = wanted.split("_")[0];
            for (String available : templates.keySet()) {
                if (available.startsWith(language + "_")) {
                    return available;
                }
            }
        }
        return defaultLocale;
    }

    private String rawTemplate(String locale, String key) {
        Map<String, String> map = templates.get(locale);
        String template = map == null ? null : map.get(key);
        if (template == null && !locale.equals(defaultLocale)) {
            Map<String, String> fallback = templates.get(defaultLocale);
            template = fallback == null ? null : fallback.get(key);
        }
        if (template == null && !locale.equals("en_US")) {
            Map<String, String> english = templates.get("en_US");
            template = english == null ? null : english.get(key);
        }
        return template;
    }

    public boolean has(String key) {
        return rawTemplate(defaultLocale, key) != null;
    }

    public Component render(String locale, String key, TagResolver... resolvers) {
        String template = rawTemplate(locale, key);
        if (template == null) {
            if (reportedMissing.add(key)) {
                logger.warn("Missing message key '{}'", key);
            }
            return Component.text(key);
        }
        if (template.isEmpty()) {
            return Component.empty();
        }
        return mini.deserialize(template, withGlobals(resolvers));
    }

    public Component render(Audience audience, String key, TagResolver... resolvers) {
        return render(localeOf(audience), key, resolvers);
    }

    /** Sends a message; an empty template means "disabled" and sends nothing. */
    public void send(Audience audience, String key, TagResolver... resolvers) {
        Component component = render(audience, key, resolvers);
        if (component != Component.empty()) {
            audience.sendMessage(component);
        }
    }

    public void actionBar(Audience audience, String key, TagResolver... resolvers) {
        Component component = render(audience, key, resolvers);
        if (component != Component.empty()) {
            audience.sendActionBar(component);
        }
    }

    /** Parses a trusted, admin-written MiniMessage string (from configs). */
    public Component parseTrusted(String miniMessage, TagResolver... resolvers) {
        return mini.deserialize(miniMessage, withGlobals(resolvers));
    }

    public MiniMessage miniMessage() {
        return mini;
    }
}
