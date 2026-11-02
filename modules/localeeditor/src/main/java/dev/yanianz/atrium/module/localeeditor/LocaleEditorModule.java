package dev.yanianz.atrium.module.localeeditor;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.permissions.PermissionDefault;

/**
 * In-game editor for Atrium's language files ({@code plugins/Atrium/lang/<locale>/<module>.yml}).
 *
 * <p>{@code /editlocale} browses locales, modules and keys in menus; {@code set}, {@code reset}, {@code preview} and
 * {@code search} work from the command line. Text is admin MiniMessage: it is validated before it is stored and only
 * then rendered for the preview. Writes are atomic and serialised per file; see {@link LangStore}.
 *
 * <p>Folia-safe: the menus and the answers are delivered on the viewer's thread, all file reads and writes run on the
 * I/O executor, and the module starts no timer.
 */
public final class LocaleEditorModule implements AtriumModule {
    static final String PERM = "atrium.editlocale";

    @Override
    public String id() {
        return "localeeditor";
    }

    @Override
    public String description() {
        return "In-game editor for the language files: browse, set, reset, preview and search messages";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        ConfigHandle<LocaleEditorConfig> config = ctx.config(LocaleEditorConfig.class, LocaleEditorConfig::new);
        LangStore store = new LangStore(ctx, config);
        ctx.permission(PERM, PermissionDefault.OP, "Edit Atrium's language files in game with /editlocale");
        new LocaleEditorCommands(ctx, config, store).register();
    }
}
