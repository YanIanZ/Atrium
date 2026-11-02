package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.permissions.PermissionDefault;

/** Item-focused utilities: components, condense, trash, giveall, books, frames and donations. */
public final class ItemToolsModule implements AtriumModule {
    private ConfigHandle<ItemToolsConfig> config;
    private ItemToolsCommands commands;

    @Override
    public String id() {
        return "itemtools";
    }

    @Override
    public String description() {
        return "Item components, condense, trash, giveall, books, item frames and donations";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.config = ctx.config(ItemToolsConfig.class, ItemToolsConfig::new);

        ctx.permission("atrium.itemcmdata", PermissionDefault.OP, "Set or clear the CustomModelData of the held item");
        ctx.permission("atrium.hideflags", PermissionDefault.OP, "Hide or show the extra tooltip lines of the held item");
        ctx.permission("atrium.glow", PermissionDefault.OP, "Force the enchantment glint of the held item");
        ctx.permission("atrium.condense", PermissionDefault.TRUE, "Condense nuggets, ingots and loose materials");
        ctx.permission("atrium.dispose", PermissionDefault.TRUE, "Open the trash menu");
        ctx.permission("atrium.giveall", PermissionDefault.OP, "Give an item to every online player");
        ctx.permission("atrium.getbook", PermissionDefault.TRUE, "Copy your own written book into an empty book and quill");
        ctx.permission("atrium.getbook.others", PermissionDefault.OP, "Copy a written book authored by someone else");
        ctx.permission("atrium.itemframe", PermissionDefault.TRUE, "Change the item frame you are looking at");
        ctx.permission("atrium.itemframe.invisible", PermissionDefault.TRUE, "Make the targeted item frame invisible");
        ctx.permission("atrium.itemframe.visible", PermissionDefault.TRUE, "Make the targeted item frame visible");
        ctx.permission("atrium.itemframe.fixed", PermissionDefault.TRUE, "Fix the targeted item frame in place");
        ctx.permission("atrium.itemframe.unfixed", PermissionDefault.TRUE, "Unfix the targeted item frame");
        ctx.permission("atrium.repaircost", PermissionDefault.OP, "Show or set the repair cost of the held item");
        ctx.permission("atrium.donate", PermissionDefault.TRUE, "Offer the held item to another player");

        this.commands = new ItemToolsCommands(ctx, config);
        this.commands.register();
    }

    @Override
    public void disable() {
        if (commands != null) {
            commands.cancel();
        }
    }

    @Override
    public void reload() throws Exception {
        if (config != null) {
            config.reload();
        }
    }
}
