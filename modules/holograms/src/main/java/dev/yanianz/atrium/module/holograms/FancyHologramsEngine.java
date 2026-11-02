package dev.yanianz.atrium.module.holograms;

import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.DisplayHologramData;
import de.oliver.fancyholograms.api.data.HologramData;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.data.property.Visibility;
import de.oliver.fancyholograms.api.events.HologramsLoadedEvent;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;
import org.joml.Vector3f;

/**
 * Mirrors Atrium holograms into FancyHolograms text holograms. Only loaded when FancyHolograms is enabled.
 *
 * <p>Atrium owns persistence and visibility: the data is created with {@code persistent = false}, and
 * {@link Visibility#MANUAL} means FancyHolograms only shows it to the players Atrium explicitly shows it to.
 * Every call runs on the global region thread; FancyHolograms supports Folia and schedules entity work itself.
 */
final class FancyHologramsEngine extends MirroredEngine {
    private FancyHologramsEngine(ModuleContext ctx, Supplier<HologramsConfig> config, TextRenderer renderer) {
        super(ctx, config, renderer, false);
    }

    static FancyHologramsEngine create(ModuleContext ctx, Supplier<HologramsConfig> config, TextRenderer renderer) {
        if (!Bukkit.getPluginManager().isPluginEnabled("FancyHolograms")) {
            throw new EngineException("FancyHolograms is not enabled");
        }
        if (!FancyHologramsPlugin.isEnabled()) {
            throw new EngineException("the FancyHolograms API is not running");
        }
        return new FancyHologramsEngine(ctx, config, renderer);
    }

    @Override
    public EngineKind kind() {
        return EngineKind.FANCYHOLOGRAMS;
    }

    @Override
    public EngineCapabilities capabilities() {
        return EngineCapabilities.fancyHolograms();
    }

    @Override
    protected MirrorText.Format format() {
        return MirrorText.Format.MINI_MESSAGE;
    }

    // ---- hook operations (global thread) ----

    @Override
    protected void createExternal(Hologram hologram) {
        Optional<Location> location = hologram.location().toLocation();
        if (location.isEmpty()) {
            return;
        }
        HologramManager manager = manager();
        String name = EngineNames.externalName(hologram.name());
        if (manager.getHologram(name).isPresent()) {
            updateExternal(hologram);
            return;
        }
        TextHologramData data = new TextHologramData(name, location.get());
        apply(data, hologram, location.get());
        de.oliver.fancyholograms.api.hologram.Hologram mirror = manager.create(data);
        apply(mirror.getData(), hologram, location.get());
        mirror.getData().setPersistent(false);
        manager.addHologram(mirror);
    }

    @Override
    protected void updateExternal(Hologram hologram) {
        Optional<Location> location = hologram.location().toLocation();
        if (location.isEmpty()) {
            return;
        }
        HologramManager manager = manager();
        String name = EngineNames.externalName(hologram.name());
        Optional<de.oliver.fancyholograms.api.hologram.Hologram> found = manager.getHologram(name);
        if (found.isEmpty()) {
            createExternal(hologram);
            return;
        }
        de.oliver.fancyholograms.api.hologram.Hologram mirror = found.get();
        if (!(mirror.getData() instanceof TextHologramData)
                || moved(mirror.getData().getLocation(), hologram.location())) {
            // A text mirror cannot be turned into another type in place, and FancyHolograms has no cheap
            // move: recreate so the right entity exists at the new place.
            deleteExternal(name);
            createExternal(hologram);
            return;
        }
        apply(mirror.getData(), hologram, location.get());
        mirror.forceUpdate();
    }

    @Override
    protected void deleteExternal(String externalName) {
        if (!EngineNames.validExternalName(externalName)) {
            return;
        }
        HologramManager manager = manager();
        manager.getHologram(externalName).ifPresent(mirror -> {
            // removeHologram only unregisters and drops the saved data; remove the display entity explicitly.
            mirror.deleteHologram();
            manager.removeHologram(mirror);
        });
    }

    @Override
    protected List<Prefixed> listPrefixed() {
        List<Prefixed> found = new ArrayList<>();
        for (de.oliver.fancyholograms.api.hologram.Hologram mirror : manager().getHolograms()) {
            String name = mirror.getName();
            if (EngineNames.isManaged(name)) {
                found.add(new Prefixed(name, mirror.getData().isPersistent(), () -> fingerprint(mirror)));
            }
        }
        return found;
    }

    @Override
    protected @Nullable Boolean persistentOf(String externalName) {
        if (!EngineNames.validExternalName(externalName)) {
            return null;
        }
        return manager().getHologram(externalName).map(mirror -> mirror.getData().isPersistent()).orElse(null);
    }

    @Override
    protected List<Listener> hookListeners() {
        return List.of(new ReloadListener());
    }

    /** {@code /fancyholograms reload} unloads and reloads the holograms; recreate the mirrors afterwards. */
    final class ReloadListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onLoaded(HologramsLoadedEvent event) {
            externalReloaded();
        }
    }

    @Override
    protected void showExternal(Player viewer, Hologram hologram, int page) {
        manager().getHologram(EngineNames.externalName(hologram.name())).ifPresent(mirror ->
                mirror.forceShowHologram(viewer));
    }

    @Override
    protected void hideExternal(Player viewer, Hologram hologram) {
        manager().getHologram(EngineNames.externalName(hologram.name())).ifPresent(mirror ->
                mirror.forceHideHologram(viewer));
    }

    // ---- conversion ----

    private HologramManager manager() {
        return FancyHologramsPlugin.get().getHologramManager();
    }

    private void apply(HologramData data, Hologram hologram, Location location) {
        HologramSettings settings = hologram.settings();
        data.setLocation(location);
        data.setPersistent(false);
        data.setVisibility(Visibility.MANUAL);
        data.setVisibilityDistance(Math.max(1, Math.min(1024, Math.round(settings.viewRange()))));
        if (data instanceof DisplayHologramData display) {
            display.setBillboard(settings.billboard());
            float scale = settings.scale();
            display.setScale(new Vector3f(scale, scale, scale));
        }
        if (data instanceof TextHologramData text) {
            List<List<String>> pages = MirrorText.pages(hologram, renderer, capabilities(), format(), ctx.logger());
            text.setText(pages.isEmpty() ? List.of() : pages.get(0));
            text.setTextAlignment(settings.alignment());
            text.setTextShadow(settings.shadow());
            text.setSeeThrough(settings.seeThrough());
            // Atrium resolves placeholders itself; never let FancyHolograms refresh every tick.
            text.setTextUpdateInterval(-1);
            Integer argb = settings.backgroundArgb();
            text.setBackground(argb == null ? null : Color.fromARGB(argb));
        }
    }

    private static boolean moved(Location current, StoredLocation wanted) {
        if (current == null || current.getWorld() == null) {
            return true;
        }
        StoredLocation stored;
        try {
            stored = StoredLocation.of(current);
        } catch (RuntimeException e) {
            return true;
        }
        return !stored.equals(wanted);
    }

    private String fingerprint(de.oliver.fancyholograms.api.hologram.Hologram mirror) {
        try {
            HologramData data = mirror.getData();
            Location location = data.getLocation();
            if (location == null || location.getWorld() == null || !(data instanceof TextHologramData text)) {
                return "";
            }
            return Reconcile.fingerprint(StoredLocation.of(location), List.of(List.copyOf(text.getText())));
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot read FancyHolograms mirror '{}'; it will be rewritten", mirror.getName(), e);
            return "";
        }
    }
}
