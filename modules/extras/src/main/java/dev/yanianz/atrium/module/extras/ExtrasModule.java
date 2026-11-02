package dev.yanianz.atrium.module.extras;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.permissions.PermissionDefault;

/**
 * Small utilities that do not deserve their own module: a read-only shulker box preview ({@code /near}
 * lives in the player module). The preview only ever opens on the viewer's thread.
 */
public final class ExtrasModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<ExtrasConfig> config;
    private ShulkerPreview preview;

    @Override
    public String id() {
        return "extras";
    }

    @Override
    public String description() {
        return "Read-only shulker box preview (sneak and right-click the air while holding a box)";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(ExtrasConfig.class, ExtrasConfig::new);

        ctx.permission("atrium.shulker", PermissionDefault.TRUE, "Preview the contents of a held shulker box");

        this.preview = new ShulkerPreview(ctx, () -> config.get());
        ctx.listen(preview);
    }

    @Override
    public void disable() {
        // Open previews are menus of this module: the menu service closes them on disable.
        preview = null;
    }
}
