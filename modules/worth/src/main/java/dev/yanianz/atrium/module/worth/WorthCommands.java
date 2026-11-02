package dev.yanianz.atrium.module.worth;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

final class WorthCommands {
    private final ModuleContext ctx;
    private final WorthModule module;
    private final SellService sell;
    private final SellGui gui;
    private final WorthStore store;

    WorthCommands(ModuleContext ctx, WorthModule module, SellService sell, SellGui gui, WorthStore store) {
        this.ctx = ctx;
        this.module = module;
        this.sell = sell;
        this.gui = gui;
        this.store = store;
    }

    void register() {
        var materials = Cmds.suggest(this::pricedMaterials);
        ctx.command(CommandSpec.of(Cmds.literal("worth", "atrium.worth")
                .executes(c -> worthHand(c))
                .then(Cmds.literal("hand").executes(this::worthHand))
                .then(Cmds.literal("all").executes(this::worthAll))
                .then(Commands.argument("material", StringArgumentType.word()).suggests(materials).executes(this::worthMaterial))
                .build(), "Show what the held item, your whole inventory or a material is worth"));

        LiteralArgumentBuilder<CommandSourceStack> sellCmd = Cmds.literal("sell", "atrium.sell")
                .executes(c -> usage(c, "worth.usage-sell"));
        for (SellPlanner.Mode mode : List.of(SellPlanner.Mode.HAND, SellPlanner.Mode.SAME, SellPlanner.Mode.BLOCKS)) {
            sellCmd.then(Cmds.literal(mode.name().toLowerCase(Locale.ROOT))
                    .executes(c -> sell(c, mode, null, 0))
                    .then(Commands.argument("amount", IntegerArgumentType.integer(1)).suggests(amounts())
                            .executes(c -> sell(c, mode, null, IntegerArgumentType.getInteger(c, "amount")))));
        }
        sellCmd.then(Cmds.literal("all").executes(c -> sell(c, SellPlanner.Mode.ALL, null, 0)));
        sellCmd.then(Commands.argument("material", StringArgumentType.word()).suggests(materials)
                .executes(c -> sellMaterial(c, 0))
                .then(Commands.argument("amount", IntegerArgumentType.integer(1)).suggests(amounts())
                        .executes(c -> sellMaterial(c, IntegerArgumentType.getInteger(c, "amount")))));
        ctx.command(CommandSpec.of(sellCmd.build(), "Sell the held item, matching items, blocks, a material or everything"));

        ctx.command(CommandSpec.of(Cmds.literal("sellgui", "atrium.sellgui")
                .executes(c -> {
                    Player player = ctx.commands().player(c.getSource());
                    gui.open(player);
                    return Cmds.OK;
                }).build(), "Open a chest that sells its contents when you close it"));

        ctx.command(CommandSpec.of(Cmds.literal("setworth", "atrium.setworth")
                .executes(c -> usage(c, "worth.usage-setworth"))
                .then(Commands.argument("price", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("0", "1", "10", "100"))).executes(this::setWorth))
                .build(), "Set the sell price of the held item (0 makes it unsellable)"));

        ctx.command(CommandSpec.of(Cmds.literal("selllog", "atrium.selllog")
                .executes(this::sellLog).build(), "Show your ten most recent sales"));
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    private static com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> amounts() {
        return Cmds.suggest(() -> List.of("1", "16", "32", "64"));
    }

    private Set<String> pricedMaterials() {
        Set<String> names = new TreeSet<>();
        for (PriceEntry entry : module.prices().entries()) {
            if (entry.price().signum() > 0) names.add(entry.material());
        }
        return names;
    }

    private int worthHand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            throw ctx.commands().fail(c.getSource(), "worth.empty-hand");
        }
        Component name = hand.effectiveName();
        Optional<BigDecimal> unit = module.prices().unitPrice(module.reader().read(hand));
        if (unit.isEmpty()) {
            ctx.messages().send(player, "worth.unsellable", Msg.component("item", name));
        } else {
            ctx.messages().send(player, "worth.hand", Msg.component("item", name),
                    Msg.number("amount", hand.getAmount()),
                    Msg.unparsed("unit", sell.format(unit.get())),
                    Msg.unparsed("total", sell.format(unit.get().multiply(BigDecimal.valueOf(hand.getAmount())))));
        }
        return Cmds.OK;
    }

    private int worthAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        SellPlanner.Plan plan = sell.preview(player, SellPlanner.Mode.ALL, null);
        ctx.messages().send(player, "worth.all", Msg.number("items", plan.items()),
                Msg.unparsed("total", sell.format(plan.total())));
        return Cmds.OK;
    }

    private int worthMaterial(CommandContext<CommandSourceStack> c) {
        String material = StringArgumentType.getString(c, "material").toLowerCase(Locale.ROOT);
        CommandSender sender = c.getSource().getSender();
        PriceTable prices = module.prices();
        Optional<PriceEntry> plain = prices.plain(material);
        long special = prices.forMaterial(material).stream().filter(e -> !e.isPlain()).count();
        if (plain.isPresent() && plain.get().price().signum() > 0) {
            ctx.messages().send(sender, "worth.material", Msg.unparsed("material", material),
                    Msg.unparsed("price", sell.format(plain.get().price())));
        } else {
            ctx.messages().send(sender, "worth.material-none", Msg.unparsed("material", material));
        }
        if (special > 0) {
            ctx.messages().send(sender, "worth.material-special", Msg.number("count", special));
        }
        return Cmds.OK;
    }

    private int sell(CommandContext<CommandSourceStack> c, SellPlanner.Mode mode, String material, int amount)
            throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        if (mode == SellPlanner.Mode.HAND && player.getInventory().getItemInMainHand().isEmpty()) {
            throw ctx.commands().fail(c.getSource(), "worth.empty-hand");
        }
        if (mode == SellPlanner.Mode.SAME && player.getInventory().getItemInMainHand().isEmpty()) {
            throw ctx.commands().fail(c.getSource(), "worth.empty-hand");
        }
        sell.sell(player, mode, material, amount);
        return Cmds.OK;
    }

    private int sellMaterial(CommandContext<CommandSourceStack> c, int amount) throws CommandSyntaxException {
        String material = StringArgumentType.getString(c, "material").toLowerCase(Locale.ROOT);
        if (!WorthModule.isItemMaterial(material)) {
            throw ctx.commands().fail(c.getSource(), "worth.unknown-material", Msg.unparsed("material", material));
        }
        return sell(c, SellPlanner.Mode.MATERIAL, material, amount);
    }

    private int setWorth(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        BigDecimal price;
        try {
            price = WorthFile.price(StringArgumentType.getString(c, "price"));
        } catch (IllegalArgumentException e) {
            throw ctx.commands().fail(c.getSource(), "worth.invalid-price");
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            throw ctx.commands().fail(c.getSource(), "worth.empty-hand");
        }
        ItemFacts facts = module.reader().read(hand);
        PriceEntry entry = entryFor(facts, price, module.prices().entries());
        Component name = hand.effectiveName();
        ctx.scheduler().supplyIo(() -> {
            module.saveEntry(entry);
            return null;
        }).whenComplete((v, failure) -> ctx.scheduler().entity(player, () -> {
            if (failure != null) {
                ctx.logger().error("Could not save worth entry {}", entry.id(), failure);
                ctx.messages().send(player, "worth.failed");
            } else {
                ctx.messages().send(player, "worth.set", Msg.component("item", name), Msg.unparsed("id", entry.id()),
                        Msg.unparsed("price", sell.format(price)));
                if (!entry.isPlain()) {
                    ctx.messages().send(player, "worth.set-exact");
                }
            }
        }, null));
        return Cmds.OK;
    }

    /** An entry matching exactly this item's identifying data; reuses the id of an identical existing entry. */
    static PriceEntry entryFor(ItemFacts facts, BigDecimal price, List<PriceEntry> existing) {
        Set<PriceEntry.Attribute> accept = EnumSet.noneOf(PriceEntry.Attribute.class);
        if (!facts.lore().isEmpty()) accept.add(PriceEntry.Attribute.LORE);
        if (!facts.otherComponents().isEmpty()) accept.add(PriceEntry.Attribute.COMPONENTS);
        PriceEntry candidate = new PriceEntry("?", facts.material(), price, facts.itemModel(),
                facts.customModelData().isEmpty() ? null : facts.customModelData(), facts.name(),
                facts.enchantments().isEmpty() ? null : facts.enchantments(), accept);
        for (PriceEntry e : existing) {
            if (sameIdentity(e, candidate)) {
                return new PriceEntry(e.id(), e.material(), price, e.itemModel(), e.customModelData(), e.name(),
                        e.enchantments(), e.accept());
            }
        }
        String id = candidate.isPlain() ? facts.material() : WorthFile.freeId(existing, facts.material());
        return new PriceEntry(id, facts.material(), price, candidate.itemModel(), candidate.customModelData(),
                candidate.name(), candidate.enchantments(), candidate.accept());
    }

    private static boolean sameIdentity(PriceEntry a, PriceEntry b) {
        return a.material().equals(b.material()) && java.util.Objects.equals(a.itemModel(), b.itemModel())
                && java.util.Objects.equals(a.customModelData(), b.customModelData())
                && java.util.Objects.equals(a.name(), b.name())
                && java.util.Objects.equals(a.enchantments(), b.enchantments())
                && a.accept().equals(b.accept());
    }

    private int sellLog(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        store.recentSales(player.getUniqueId(), 10).whenComplete((sales, failure) -> ctx.scheduler().entity(player, () -> {
            if (failure != null) {
                ctx.logger().warn("Cannot read the sale log of {}", player.getUniqueId(), failure);
                ctx.messages().send(player, "worth.failed");
                return;
            }
            if (sales.isEmpty()) {
                ctx.messages().send(player, "worth.log-empty");
                return;
            }
            for (WorthStore.Sale sale : sales) {
                ctx.messages().send(player, "worth.log-entry",
                        Msg.unparsed("time", Instant.ofEpochMilli(sale.created()).toString()),
                        Msg.unparsed("mode", sale.mode()), Msg.number("items", sale.items()),
                        Msg.unparsed("total", sell.format(new BigDecimal(sale.total()))),
                        Msg.unparsed("detail", sale.detail()));
            }
        }, null));
        return Cmds.OK;
    }
}
