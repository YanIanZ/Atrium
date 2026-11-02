package dev.yanianz.atrium.module.localeeditor;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/localeeditor.yml */
@ConfigSerializable
public class LocaleEditorConfig {
    @Comment("Rows of the /editlocale browser menus (3-6). The bottom row is the navigation bar.")
    public int menuRows = 5;

    @Comment("How many results /editlocale search shows at once (5-200). The search itself stops at this limit.")
    public int searchResults = 40;

    @Comment("Reload the edited message bundle right after a successful write, so players see the new text immediately.")
    public boolean reloadAfterWrite = true;

    @Comment("Maximum characters of a message value shown in menus and search results (20-200). Full values stay in the hover.")
    public int previewLength = 70;

    static int rows(LocaleEditorConfig config) {
        return Math.clamp(config.menuRows, 3, 6);
    }

    static int searchLimit(LocaleEditorConfig config) {
        return Math.clamp(config.searchResults, 5, 200);
    }

    static int previewLength(LocaleEditorConfig config) {
        return Math.clamp(config.previewLength, 20, 200);
    }
}
