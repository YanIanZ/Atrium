package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.permissions.PermissionDefault;

/**
 * Item-focused utilities: components, condense, trash, giveall, books, frames, donations, the saved item
 * library, mob heads, item commands/cooldowns, skull data preservation and the optional low-durability
 * warning.
 */
public final class ItemToolsModule implements AtriumModule {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private ConfigHandle<ItemToolsConfig> config;
    private ItemToolsCommands commands;
    private MobHeadCommand mobHeads;
    private OpenBookCommand openBook;

    @Override
    public String id() {
        return "itemtools";
    }

    @Override
    public String description() {
        return "Item components, condense, trash, giveall, books, frames, saved items and donations";
    }

    @Override
    public java.util.Set<String> softDependencies() {
        // Attached item commands run through the actions module when it is enabled.
        return java.util.Set.of("actions");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.config = ctx.config(ItemToolsConfig.class, ItemToolsConfig::new);
        ctx.migrate(ItemToolsSchema.MIGRATIONS);

        ctx.permission("atrium.itemcmdata", PermissionDefault.OP, "Set or clear the CustomModelData of the held item");
        ctx.permission("atrium.hideflags", PermissionDefault.OP, "Hide or show the extra tooltip lines of the held item");
        ctx.permission("atrium.glow", PermissionDefault.OP, "Force the enchantment glint of the held item");
        ctx.permission("atrium.condense", PermissionDefault.TRUE, "Condense nuggets, ingots and loose materials");
        ctx.permission("atrium.uncondense", PermissionDefault.TRUE, "Split blocks back into loose materials");
        ctx.permission("atrium.dispose", PermissionDefault.TRUE, "Open the trash menu");
        ctx.permission("atrium.giveall", PermissionDefault.OP, "Give an item to every online player");
        ctx.permission("atrium.giveall.offline", PermissionDefault.OP,
                "Also queue the /giveall item for offline players");
        ctx.permission("atrium.getbook", PermissionDefault.TRUE, "Copy your own written book into an empty book and quill");
        ctx.permission("atrium.getbook.others", PermissionDefault.OP, "Copy a written book authored by someone else");
        ctx.permission("atrium.openbook", PermissionDefault.TRUE, "Open a book without receiving an item");
        ctx.permission("atrium.openbook.others", PermissionDefault.OP, "Open a book for another player");
        ctx.permission("atrium.itemframe", PermissionDefault.TRUE, "Change the item frame you are looking at");
        ctx.permission("atrium.itemframe.invisible", PermissionDefault.TRUE, "Make the targeted item frame invisible");
        ctx.permission("atrium.itemframe.visible", PermissionDefault.TRUE, "Make the targeted item frame visible");
        ctx.permission("atrium.itemframe.fixed", PermissionDefault.TRUE, "Fix the targeted item frame in place");
        ctx.permission("atrium.itemframe.unfixed", PermissionDefault.TRUE, "Unfix the targeted item frame");
        ctx.permission("atrium.repaircost", PermissionDefault.OP, "Show or set the repair cost of the held item");
        ctx.permission("atrium.donate", PermissionDefault.TRUE, "Offer the held item to another player");
        ctx.permission("atrium.unbreakable", PermissionDefault.OP, "Make the held item unbreakable or breakable");
        ctx.permission("atrium.unbreakable.others", PermissionDefault.OP,
                "Change the unbreakable state of another player's item");
        ctx.permission("atrium.saveditems", PermissionDefault.OP, "Use the saved item library");
        ctx.permission("atrium.saveditems.give", PermissionDefault.OP, "Give saved items to other players");
        ctx.permission("atrium.mobhead", PermissionDefault.OP, "Browse and claim the configured mob heads");
        ctx.permission("atrium.mobhead.others", PermissionDefault.OP, "Give a mob head to another player");
        ctx.permission("atrium.attachedcmd", PermissionDefault.OP, "Attach admin action lines to the held item");
        ctx.permission("atrium.attachedcmd.use", PermissionDefault.TRUE, "Use an item that has attached commands");
        ctx.permission("atrium.itemcooldown", PermissionDefault.OP, "Set the cooldown metadata of the held item");
        ctx.permission("atrium.durabilitywarn", PermissionDefault.TRUE,
                "Opt in or out of the low-durability warning");

        ItemSupport support = new ItemSupport(ctx, cancelled);
        ItemToolsPrefsStore prefs = new ItemToolsPrefsStore(ctx.database());
        ctx.component(prefs.component);
        SavedItemsStore savedItems = new SavedItemsStore(ctx.database());
        GiveAllQueue giveAllQueue = new GiveAllQueue(ctx);
        ctx.listen(giveAllQueue);

        this.commands = new ItemToolsCommands(ctx, config, support, giveAllQueue);
        this.commands.register();
        new UnbreakableCommand(support).register();
        new UncondenseCommand(support, config).register();
        this.openBook = new OpenBookCommand(support, config,
                ctx.dataDirectory().resolve(config.get().bookFolderName()),
                ctx.plugin().getDataFolder().toPath().resolve("modules").resolve("customtext"));
        openBook.register();
        this.mobHeads = new MobHeadCommand(support, config);
        mobHeads.register();
        new SavedItemsCommand(support, savedItems, config).register();
        new AttachedCommands(support, config).register();
        ctx.listen(new SkullPreservation(ctx, config));
        new DurabilityWarning(ctx, config, prefs, support).register();
    }

    @Override
    public void disable() {
        cancelled.set(true);
        if (commands != null) {
            commands.cancel();
        }
    }

    @Override
    public void reload() throws Exception {
        if (config != null) {
            config.reload();
        }
        if (mobHeads != null) {
            mobHeads.reloadEntries();
        }
        if (openBook != null) {
            openBook.refresh();
        }
    }
}
