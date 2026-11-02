package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.module.ModuleContext;
import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.DecentHologramsAPI;
import eu.decentsoftware.holograms.api.holograms.HologramLine;
import eu.decentsoftware.holograms.api.holograms.HologramPage;
import eu.decentsoftware.holograms.event.DecentHologramsReloadEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.jspecify.annotations.Nullable;

/**
 * Mirrors Atrium holograms into DecentHolograms. This class is only loaded when DecentHolograms is enabled,
 * so a server without it never resolves the DecentHolograms API.
 *
 * <p>Threading: DH 2.8 has no Folia support; DH 2.10+ added it. Atrium refuses to select DH on Folia below
 * 2.10. On a supported server every call is made on the global region thread (the main thread on Paper),
 * which is where DecentHolograms' own scheduler expects hologram mutations.
 */
final class DecentHologramsEngine extends MirroredEngine {
    private DecentHologramsEngine(ModuleContext ctx, Supplier<HologramsConfig> config, TextRenderer renderer) {
        super(ctx, config, renderer, true);
    }

    static DecentHologramsEngine create(ModuleContext ctx, Supplier<HologramsConfig> config, TextRenderer renderer) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            throw new EngineException("DecentHolograms is not enabled");
        }
        if (!DecentHologramsAPI.isRunning()) {
            throw new EngineException("the DecentHolograms API is not running");
        }
        return new DecentHologramsEngine(ctx, config, renderer);
    }

    @Override
    public EngineKind kind() {
        return EngineKind.DECENTHOLOGRAMS;
    }

    @Override
    public EngineCapabilities capabilities() {
        return EngineCapabilities.decentHolograms();
    }

    @Override
    protected MirrorText.Format format() {
        return MirrorText.Format.LEGACY;
    }

    // ---- hook operations (global thread) ----

    @Override
    protected void createExternal(Hologram hologram) {
        Optional<Location> location = hologram.location().toLocation();
        if (location.isEmpty()) {
            return;
        }
        String name = EngineNames.externalName(hologram.name());
        eu.decentsoftware.holograms.api.holograms.Hologram mirror = DHAPI.getHologram(name);
        if (mirror == null) {
            mirror = DHAPI.createHologram(name, location.get(), false);
            mirror.setSaveToFile(false);
            mirror.setDefaultVisibleState(false);
            // DHAPI.createHologram shows the hologram to everyone; the viewer tracker decides again.
            mirror.hideAll();
        }
        sync(mirror, hologram, location.get());
    }

    @Override
    protected void updateExternal(Hologram hologram) {
        Optional<Location> location = hologram.location().toLocation();
        if (location.isEmpty()) {
            return;
        }
        String name = EngineNames.externalName(hologram.name());
        eu.decentsoftware.holograms.api.holograms.Hologram mirror = DHAPI.getHologram(name);
        if (mirror == null) {
            createExternal(hologram);
            return;
        }
        sync(mirror, hologram, location.get());
    }

    @Override
    protected void deleteExternal(String externalName) {
        if (!EngineNames.validExternalName(externalName)) {
            return;
        }
        eu.decentsoftware.holograms.api.holograms.Hologram mirror = DHAPI.getHologram(externalName);
        if (mirror != null) {
            mirror.destroy();
        }
    }

    @Override
    protected List<Prefixed> listPrefixed() {
        List<Prefixed> found = new ArrayList<>();
        for (eu.decentsoftware.holograms.api.holograms.Hologram mirror
                : DecentHologramsAPI.get().getHologramManager().getHolograms()) {
            String name = mirror.getName();
            if (EngineNames.isManaged(name)) {
                found.add(new Prefixed(name, mirror.isSaveToFile(), () -> fingerprint(mirror)));
            }
        }
        return found;
    }

    @Override
    protected @Nullable Boolean persistentOf(String externalName) {
        if (!EngineNames.validExternalName(externalName)) {
            return null;
        }
        eu.decentsoftware.holograms.api.holograms.Hologram mirror = DHAPI.getHologram(externalName);
        return mirror == null ? null : mirror.isSaveToFile();
    }

    @Override
    protected List<Listener> hookListeners() {
        return List.of(new ReloadListener());
    }

    /** {@code /dh reload} destroys every hologram that is not saved to a file, including Atrium's mirrors. */
    final class ReloadListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onReload(DecentHologramsReloadEvent event) {
            externalReloaded();
        }
    }

    @Override
    protected void showExternal(Player viewer, Hologram hologram, int page) {
        eu.decentsoftware.holograms.api.holograms.Hologram mirror =
                DHAPI.getHologram(EngineNames.externalName(hologram.name()));
        if (mirror == null) {
            return;
        }
        int count = Math.max(1, mirror.size());
        // Default visibility is off, so DecentHolograms only shows the player after they are marked.
        mirror.setShowPlayer(viewer);
        mirror.show(viewer, Pages.clamp(page, count));
    }

    @Override
    protected void hideExternal(Player viewer, Hologram hologram) {
        eu.decentsoftware.holograms.api.holograms.Hologram mirror =
                DHAPI.getHologram(EngineNames.externalName(hologram.name()));
        if (mirror != null) {
            mirror.hide(viewer);
            mirror.removeShowPlayer(viewer);
        }
    }

    // ---- conversion ----

    private void sync(eu.decentsoftware.holograms.api.holograms.Hologram mirror, Hologram hologram,
            Location location) {
        mirror.setLocation(location);
        HologramSettings settings = hologram.settings();
        mirror.setAlwaysFacePlayer(settings.billboard() != Display.Billboard.FIXED);
        if (settings.billboard() == Display.Billboard.FIXED) {
            mirror.setFacing(hologram.location().yaw());
        }
        mirror.setDefaultVisibleState(false);

        List<List<String>> pages = MirrorText.pages(hologram, renderer, capabilities(), format(), config.get().fillerChar(),
                ctx.logger());
        if (pages.isEmpty()) {
            pages = List.of(List.of());
        }
        while (mirror.size() > pages.size()) {
            DHAPI.removeHologramPage(mirror, mirror.size() - 1);
        }
        while (mirror.size() < pages.size()) {
            DHAPI.addHologramPage(mirror);
        }
        for (int index = 0; index < pages.size(); index++) {
            HologramPage page = DHAPI.getHologramPage(mirror, index);
            if (page == null) {
                continue;
            }
            List<String> wanted = pages.get(index);
            while (page.size() > wanted.size()) {
                page.removeLine(page.size() - 1);
            }
            for (int line = 0; line < wanted.size(); line++) {
                if (line < page.size()) {
                    page.setLine(line, wanted.get(line));
                } else {
                    DHAPI.addHologramLine(page, wanted.get(line));
                }
            }
        }
        mirror.realignLines();
        mirror.updateAll(true);
    }

    /** Content of an existing mirror, in the same canonical form as the desired fingerprint. */
    private String fingerprint(eu.decentsoftware.holograms.api.holograms.Hologram mirror) {
        try {
            Location location = mirror.getLocation();
            if (location == null || location.getWorld() == null) {
                return "";
            }
            List<List<String>> pages = new ArrayList<>();
            for (HologramPage page : mirror.getPages()) {
                List<String> lines = new ArrayList<>(page.size());
                for (HologramLine line : page.getLines()) {
                    lines.add(line.getContent());
                }
                pages.add(List.copyOf(lines));
            }
            return Reconcile.fingerprint(StoredLocation.of(location), pages);
        } catch (RuntimeException e) {
            // Unreadable mirror: force an update instead of leaving a possibly stale hologram.
            ctx.logger().warn("Cannot read DecentHolograms mirror '{}'; it will be rewritten", mirror.getName(), e);
            return "";
        }
    }
}
