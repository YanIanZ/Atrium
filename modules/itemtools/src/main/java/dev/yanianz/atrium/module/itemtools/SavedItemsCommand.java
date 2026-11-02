package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * {@code /saveditems save|get|give|remove|list}: a named library of complete items, stored as serialized
 * {@link ItemStack} bytes in the module's own table. Saving takes the exact held stack (amount included);
 * getting gives that stack {@code amount} times, so nothing about the stored item is ever reinterpreted.
 */
final class SavedItemsCommand {
    /** A serialized item larger than this is refused instead of filling the table. */
    private static final int MAX_ITEM_BYTES = 1_048_576;

    private final ItemSupport support;
    private final SavedItemsStore store;
    private final SavedItemsMenu menu;
    private final ConfigHandle<ItemToolsConfig> config;
    private volatile List<String> names = List.of();

    SavedItemsCommand(ItemSupport support, SavedItemsStore store, ConfigHandle<ItemToolsConfig> config) {
        this.support = support;
        this.store = store;
        this.config = config;
        this.menu = new SavedItemsMenu(support, store, config);
        refreshNames();
    }

    /** Refreshes the tab-completion cache on an I/O thread. */
    private void refreshNames() {
        store.list().whenComplete((entries, error) -> {
            if (error != null) {
                support.ctx().logger().warn("Could not list saved items", error);
                return;
            }
            names = entries.stream().map(SavedItemsStore.Entry::name).toList();
        });
    }

    void register() {
        support.register(Cmds.literal("saveditems", "atrium.saveditems")
                        .executes(c -> support.usage(c, "usage.saveditems"))
                        .then(Cmds.literal("save", "atrium.saveditems")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(this::save)))
                        .then(Cmds.literal("get", "atrium.saveditems")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> names))
                                        .executes(c -> get(c, StringArgumentType.getString(c, "name"), 1))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                .executes(c -> get(c, StringArgumentType.getString(c, "name"),
                                                        IntegerArgumentType.getInteger(c, "amount"))))))
                        .then(Cmds.literal("give", "atrium.saveditems.give")
                                .then(Commands.argument("target", ArgumentTypes.player())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests(Cmds.suggest(() -> names))
                                                .executes(c -> give(c, StringArgumentType.getString(c, "name"), 1))
                                                .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                                        .executes(c -> give(c,
                                                                StringArgumentType.getString(c, "name"),
                                                                IntegerArgumentType.getInteger(c, "amount")))))))
                        .then(Cmds.literal("remove", "atrium.saveditems")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> names))
                                        .executes(this::remove)))
                        .then(Cmds.literal("list", "atrium.saveditems").executes(this::list)),
                "Save, list and hand out items from the server's saved item library");
    }

    private int save(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        String name = name(c, StringArgumentType.getString(c, "name"));
        support.onPlayer(player, () -> saveNow(player, name));
        return Cmds.OK;
    }

    private void saveNow(Player player, String name) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            support.replyNow(player, "empty-hand");
            return;
        }
        ItemStack copy = hand.clone();
        byte[] bytes;
        try {
            bytes = copy.serializeAsBytes();
        } catch (RuntimeException e) {
            support.failed(player, "Serializing the held item", e);
            return;
        }
        if (bytes.length > MAX_ITEM_BYTES) {
            support.replyNow(player, "saveditems-too-large");
            return;
        }
        long created = System.currentTimeMillis();
        store.save(name, bytes, player.getUniqueId()).whenComplete((ignored, error) -> {
            if (error != null) {
                support.failed(player, "Saving item library entry " + name, error);
                return;
            }
            refreshNames();
            support.reply(player, "saveditems-saved", Msg.unparsed("name", name),
                    Msg.component("item", copy.effectiveName()));
        });
    }

    private int get(CommandContext<CommandSourceStack> c, String rawName, int copies) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        String name = name(c, rawName);
        int limit = config.get().savedItemsGiveLimit();
        if (copies < 1 || copies > limit) {
            throw support.cmds().fail(c.getSource(), "itemtools.saveditems-invalid-amount", Msg.number("max", limit));
        }
        load(name, stack -> {
            int total = total(stack, copies, limit);
            if (total <= 0) {
                support.reply(player, "saveditems-invalid-amount", Msg.number("max", limit));
                return;
            }
            support.ctx().scheduler().entity(player,
                    () -> deliver(player, player, name, stack, total), null);
        }, () -> support.reply(player, "saveditems-missing", Msg.unparsed("name", name)));
        return Cmds.OK;
    }

    private int give(CommandContext<CommandSourceStack> c, String rawName, int copies) throws CommandSyntaxException {
        Player target = support.cmds().argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        String name = name(c, rawName);
        int limit = config.get().savedItemsGiveLimit();
        if (copies < 1 || copies > limit) {
            throw support.cmds().fail(c.getSource(), "itemtools.saveditems-invalid-amount", Msg.number("max", limit));
        }
        load(name, stack -> {
            int total = total(stack, copies, limit);
            if (total <= 0) {
                support.reply(sender, "saveditems-invalid-amount", Msg.number("max", limit));
                return;
            }
            support.ctx().scheduler().entity(target,
                    () -> deliver(target, sender, name, stack, total), null);
        }, () -> support.reply(sender, "saveditems-missing", Msg.unparsed("name", name)));
        return Cmds.OK;
    }

    private int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = name(c, StringArgumentType.getString(c, "name"));
        store.delete(name).whenComplete((removed, error) -> {
            if (error != null) {
                support.failed(sender, "Deleting saved item " + name, error);
                return;
            }
            refreshNames();
            support.reply(sender, removed ? "saveditems-removed" : "saveditems-missing",
                    Msg.unparsed("name", name));
        });
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        menu.open(player);
        return Cmds.OK;
    }

    /** Loads and deserializes an entry off the game threads, then runs {@code found} with the template. */
    private void load(String name, java.util.function.Consumer<ItemStack> found, Runnable missing) {
        store.load(name).whenComplete((bytes, error) -> {
            if (error != null) {
                support.ctx().logger().warn("Could not read saved item '{}'", name, error);
                missing.run();
                return;
            }
            if (bytes.isEmpty()) {
                missing.run();
                return;
            }
            ItemStack stack;
            try {
                stack = ItemStack.deserializeBytes(bytes.get());
            } catch (RuntimeException e) {
                support.ctx().logger().error("Saved item '{}' is unreadable", name, e);
                missing.run();
                return;
            }
            if (stack == null || stack.isEmpty()) {
                missing.run();
                return;
            }
            found.accept(stack);
        });
    }

    private static int total(ItemStack stack, int copies, int limit) {
        long total = (long) stack.getAmount() * copies;
        if (copies < 1 || copies > limit || total > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) total;
    }

    /** Runs on the receiver's thread. */
    private void deliver(Player receiver, CommandSender sender, String name, ItemStack template, int total) {
        int dropped = ItemValues.giveOrDrop(receiver, ItemValues.copies(template, total));
        support.replyNow(receiver, dropped > 0 ? "saveditems-given-dropped" : "saveditems-given",
                Msg.unparsed("name", name), Msg.number("amount", total));
        if (!sender.equals(receiver)) {
            support.reply(sender, "saveditems-given-other", Msg.unparsed("name", name),
                    Msg.unparsed("player", receiver.getName()), Msg.number("amount", total));
        }
    }

    private String name(CommandContext<CommandSourceStack> c, String raw) throws CommandSyntaxException {
        try {
            return SavedItemName.normalize(raw);
        } catch (IllegalArgumentException e) {
            throw support.cmds().fail(c.getSource(), "itemtools.saveditems-invalid-name");
        }
    }
}
