package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.permissions.PermissionDefault;

/**
 * Item search and world scans.
 *
 * <p>{@code /search item} reads inventories on each player's own region thread and joins the futures
 * with a timeout. Scans use a bounded chunk budget, never load chunks, and are cancellable per owner.
 */
public final class SearchModule implements AtriumModule {
    private ConfigHandle<SearchConfig> config;
    private ItemSearches searches;
    private Scans scans;

    @Override
    public String id() {
        return "search";
    }

    @Override
    public String description() {
        return "Player item search plus Folia-safe container and entity scans";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.config = ctx.config(SearchConfig.class, SearchConfig::new);
        this.scans = new Scans();
        this.searches = new ItemSearches(ctx, config::get);

        ctx.permission("atrium.search", PermissionDefault.OP, "Use /search");
        ctx.permission("atrium.search.item", PermissionDefault.OP, "Search online players' inventories");
        ctx.permission("atrium.search.results", PermissionDefault.OP, "Show /search results pages");
        ctx.permission("atrium.search.cancel", PermissionDefault.OP, "Cancel your own item search");
        ctx.permission("atrium.search.teleport", PermissionDefault.OP,
                "Click container scan coordinates to teleport");
        ctx.permission("atrium.scan", PermissionDefault.OP, "Use /scan");
        ctx.permission("atrium.scan.containers", PermissionDefault.OP, "Scan loaded containers for an item");
        ctx.permission("atrium.scan.entities", PermissionDefault.OP, "List loaded chunks with the most entities");
        ctx.permission("atrium.scan.cancel", PermissionDefault.OP, "Cancel your own scan");
        ctx.permission("atrium.scan.cancel.all", PermissionDefault.OP, "Cancel every running scan");

        new SearchCommands(ctx, config, searches, scans).register();
    }

    @Override
    public void disable() {
        if (searches != null) {
            searches.shutdown();
        }
        if (scans != null) {
            scans.shutdown();
        }
    }
}
