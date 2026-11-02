package dev.yanianz.atrium.module.holograms;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;

/**
 * Loads and caches player skins for {@code #img:} lines. A skin is only downloaded from the texture URL of
 * the player's Mojang profile when that URL is exactly {@code https://textures.minecraft.net/...}; the body
 * is limited in size and time, and the decoded PNG is cached in memory and on disk.
 *
 * <p>Offline-mode servers (and failed lookups) fall back to the built-in default skin. No network call ever
 * runs on a region or player thread: everything goes through the I/O executor.
 */
final class SkinCache {
    private static final long FALLBACK_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    record CachedSkin(BufferedImage image, long fetchedAt, boolean fallback) {}

    private record RenderKey(String player, SkinKind kind, int size) {}

    private final ModuleContext ctx;
    private final Supplier<HologramsConfig> config;
    private final Path directory;
    private final Map<String, CachedSkin> skins = new ConcurrentHashMap<>();
    private final Map<RenderKey, List<String>> rendered = new ConcurrentHashMap<>();
    private final Map<RenderKey, Long> renderedAt = new ConcurrentHashMap<>();
    private final Map<RenderKey, Runnable> waiting = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<CachedSkin>> loading = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    SkinCache(ModuleContext ctx, Supplier<HologramsConfig> config, Path directory) {
        this.ctx = ctx;
        this.config = config;
        this.directory = directory;
    }

    /**
     * Rows for an image line, or empty when the skin still has to load. {@code onReady} runs once on the I/O
     * thread after the rows are cached; the caller re-renders its hologram there.
     */
    Optional<List<String>> rows(LineContent.Image image, Runnable onReady) {
        RenderKey key = new RenderKey(SkinSource.cacheKey(image.player()), image.kind(), image.size());
        long now = System.currentTimeMillis();
        List<String> cached = rendered.get(key);
        if (cached != null) {
            if (now - renderedAt.getOrDefault(key, 0L) <= cacheTtl()) {
                return Optional.of(cached);
            }
            rendered.remove(key);
            renderedAt.remove(key);
        }
        CachedSkin skin = skins.get(key.player());
        if (skin != null && !expired(skin, now)) {
            List<String> rows = render(key, skin);
            rendered.put(key, rows);
            renderedAt.put(key, now);
            return Optional.of(rows);
        }
        if (skin != null) {
            skins.remove(key.player(), skin);
        }
        if (stopping) {
            return Optional.of(defaultRows(key));
        }
        waiting.put(key, onReady);
        loading.computeIfAbsent(key.player(), this::load)
                .whenComplete((loaded, error) -> complete(key.player(), loaded, error));
        return Optional.empty();
    }

    private CompletableFuture<CachedSkin> load(String player) {
        return ctx.scheduler().supplyIo(() -> read(player));
    }

    private CachedSkin read(String player) {
        long now = System.currentTimeMillis();
        HologramsConfig.Images settings = config.get().images;
        CachedSkin disk = readDisk(player);
        if (!settings.fetchSkins || !Bukkit.getServer().getOnlineMode()) {
            if (warned.add("offline:" + player)) {
                ctx.logger().warn("holograms: image line for '{}' uses the built-in default skin ({})", player,
                        settings.fetchSkins ? "offline mode" : "fetch-skins is off");
            }
            return defaultSkin(now);
        }
        if (disk != null && !expired(disk, now)) {
            return disk;
        }
        try {
            BufferedImage image = fetch(player, settings);
            if (image != null) {
                writeDisk(player, image);
                return new CachedSkin(image, now, false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (warned.add("fetch:" + player)) {
                ctx.logger().warn("holograms: could not load the skin of '{}' for an image line: {}", player,
                        e.toString());
            }
        }
        return disk != null ? disk : defaultSkin(now);
    }

    private BufferedImage fetch(String player, HologramsConfig.Images settings) throws Exception {
        PlayerProfile profile = Bukkit.createProfile(player);
        profile.update().get(Math.max(1, settings.timeoutSeconds), TimeUnit.SECONDS);
        String textures = null;
        for (ProfileProperty property : profile.getProperties()) {
            if ("textures".equals(property.getName())) {
                textures = property.getValue();
                break;
            }
        }
        Optional<String> url = SkinSource.textureUrl(textures);
        if (url.isEmpty()) {
            if (warned.add("texture:" + player)) {
                ctx.logger().warn("holograms: no allowed texture URL in the Mojang profile of '{}'; "
                        + "the built-in default skin is used", player);
            }
            return null;
        }
        byte[] png = download(url.get(), settings);
        if (png == null) {
            return null;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        if (image == null || image.getWidth() < 64 || image.getHeight() < 64) {
            if (warned.add("decode:" + player)) {
                ctx.logger().warn("holograms: the skin of '{}' is not a 64x64 PNG; the built-in default skin is used",
                        player);
            }
            return null;
        }
        return image;
    }

    private byte[] download(String url, HologramsConfig.Images settings) throws IOException, InterruptedException {
        int timeout = Math.max(1, Math.min(60, settings.timeoutSeconds));
        int max = Math.max(1024, Math.min(1_048_576, settings.maxBytes));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeout))
                .header("User-Agent", "Atrium")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            return null;
        }
        try (InputStream in = response.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > max) {
                    return null;
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private void complete(String player, CachedSkin loaded, Throwable error) {
        loading.remove(player);
        if (stopping) {
            waiting.keySet().removeIf(key -> key.player().equals(player));
            return;
        }
        CachedSkin skin = loaded;
        if (error != null || skin == null) {
            if (error != null && warned.add("load:" + player)) {
                ctx.logger().warn("holograms: image skin task for '{}' failed: {}", player, error.toString());
            }
            skin = defaultSkin(System.currentTimeMillis());
        }
        skins.put(player, skin);
        for (RenderKey key : List.copyOf(waiting.keySet())) {
            if (!key.player().equals(player)) {
                continue;
            }
            Runnable callback = waiting.remove(key);
            List<String> rows = render(key, skin);
            rendered.put(key, rows);
            renderedAt.put(key, System.currentTimeMillis());
            if (callback != null) {
                callback.run();
            }
        }
    }

    private List<String> render(RenderKey key, CachedSkin skin) {
        try {
            return SkinImage.rows(skin.image(), key.kind(), key.size());
        } catch (IllegalArgumentException e) {
            return defaultRows(key);
        }
    }

    private List<String> defaultRows(RenderKey key) {
        return SkinImage.rows(SkinImage.defaultSkin(), key.kind(), key.size());
    }

    private long cacheTtl() {
        return Duration.ofHours(Math.max(1, Math.min(8760, config.get().images.cacheHours))).toMillis();
    }

    private boolean expired(CachedSkin skin, long now) {
        long ttl = skin.fallback() ? FALLBACK_TTL_MILLIS : cacheTtl();
        return now - skin.fetchedAt() > ttl;
    }

    private CachedSkin defaultSkin(long now) {
        return new CachedSkin(SkinImage.defaultSkin(), now, true);
    }

    private Path file(String player) {
        return directory.resolve(player + ".png");
    }

    private Path stamp(String player) {
        return directory.resolve(player + ".stamp");
    }

    private CachedSkin readDisk(String player) {
        Path image = file(player);
        Path stamp = stamp(player);
        if (!Files.isRegularFile(image) || !Files.isRegularFile(stamp)) {
            return null;
        }
        try {
            long fetchedAt = Long.parseLong(Files.readString(stamp, StandardCharsets.UTF_8).strip());
            BufferedImage decoded = ImageIO.read(image.toFile());
            if (decoded == null || decoded.getWidth() < 64 || decoded.getHeight() < 64) {
                return null;
            }
            return new CachedSkin(decoded, fetchedAt, false);
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private void writeDisk(String player, BufferedImage image) {
        try {
            Files.createDirectories(directory);
            ImageIO.write(image, "png", file(player).toFile());
            Files.writeString(stamp(player), Long.toString(System.currentTimeMillis()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (warned.add("disk:" + player)) {
                ctx.logger().warn("holograms: could not cache the skin of '{}' on disk: {}", player, e.toString());
            }
        }
    }

    void stop() {
        stopping = true;
        waiting.clear();
        loading.clear();
        skins.clear();
        rendered.clear();
        renderedAt.clear();
    }
}
