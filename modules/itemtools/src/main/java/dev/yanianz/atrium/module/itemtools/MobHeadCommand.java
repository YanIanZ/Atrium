package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /mobhead}: a paged menu of the heads configured in {@code modules/itemtools.yml}, plus
 * {@code /mobhead <id> [player]} for console and scripts. Heads are free; the permission gates the command,
 * not each entry. Nothing a player types is ever inserted into the head's data.
 */
final class MobHeadCommand {
    private static final int MENU_ROWS = 6;

    private final ItemSupport support;
    private final ConfigHandle<ItemToolsConfig> config;
    private volatile List<MobHeads.Entry> entries = List.of();

    MobHeadCommand(ItemSupport support, ConfigHandle<ItemToolsConfig> config) {
        this.support = support;
        this.config = config;
        reloadEntries();
    }

    /** Re-parses the configured list; called on enable and on module reload. */
    void reloadEntries() {
        List<String> problems = new ArrayList<>();
        entries = MobHeads.parse(config.get().mobHeads, problems::add);
        for (String problem : problems) {
            support.ctx().logger().warn("mobhead: {}", problem);
        }
    }

    void register() {
        support.register(Cmds.literal("mobhead", "atrium.mobhead")
                        .executes(this::menu)
                        .then(Cmds.literal("list", "atrium.mobhead").executes(this::menu))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> entries.stream().map(MobHeads.Entry::id).toList()))
                                .executes(c -> give(c, StringArgumentType.getString(c, "id")))
                                .then(Commands.argument("target", ArgumentTypes.player())
                                        .executes(c -> give(c, StringArgumentType.getString(c, "id"))))),
                "Browse or claim the configured mob heads");
    }

    private int menu(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player viewer = support.cmds().player(c.getSource());
        List<MobHeads.Entry> snapshot = entries;
        support.ctx().menus().open(viewer, PagedMenu.builder(support.ctx().menus(),
                        support.ctx().messages().render(viewer, "itemtools.mobhead-title"))
                .rows(MENU_ROWS)
                .content(player -> snapshot.stream().map(entry -> button(player, entry)).toList())
                .build());
        return Cmds.OK;
    }

    private Button button(Player viewer, MobHeads.Entry entry) {
        ItemStack icon = ItemBuilder.from(MobHeads.item(entry))
                .name(support.ctx().messages().render(viewer, "itemtools.mobhead-entry",
                        Msg.unparsed("head", entry.id())))
                .addLore(support.ctx().messages().render(viewer, "itemtools.mobhead-entry-lore"))
                .hideDetails()
                .build();
        return Button.of(icon, click -> claim(click.player(), entry, 1))
                .onShiftLeft(click -> claim(click.player(), entry, 64));
    }

    private int give(CommandContext<CommandSourceStack> c, String rawId) throws CommandSyntaxException {
        Player target = support.cmds().selfOrOther(c, "target", "atrium.mobhead.others");
        CommandSender sender = c.getSource().getSender();
        MobHeads.Entry entry = MobHeads.find(entries, rawId).orElse(null);
        if (entry == null) {
            throw support.cmds().fail(c.getSource(), "itemtools.mobhead-unknown", Msg.unparsed("head", rawId));
        }
        support.onPlayer(target, () -> {
            claim(target, entry, 1);
            if (!sender.equals(target)) {
                support.reply(sender, "mobhead-given-other", Msg.unparsed("head", entry.id()),
                        Msg.unparsed("player", target.getName()));
            }
        });
        return Cmds.OK;
    }

    /** Gives heads on the player's thread; overflow is dropped at their feet and reported. */
    private void claim(Player player, MobHeads.Entry entry, int amount) {
        int dropped = ItemValues.giveOrDrop(player, ItemValues.copies(MobHeads.item(entry), amount));
        support.replyNow(player, dropped > 0 ? "mobhead-given-dropped" : "mobhead-given",
                Msg.unparsed("head", entry.id()), Msg.number("amount", amount));
    }
}
