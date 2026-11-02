package dev.yanianz.atrium.module.player;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.permissions.PermissionDefault;

/** Part-two commands. Deferred work is guarded by the module lifetime. */
final class InventoryCommands implements Listener, TaskHandle {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PlayerConfig> config;
    private final Map<CommandSender, Confirmation> confirmations = new ConcurrentHashMap<>();
    private volatile boolean cancelled;
    private record Confirmation(UUID target, Material item, int amount, long expires) { }

    InventoryCommands(ModuleContext ctx, ConfigHandle<PlayerConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
    }

    void register() {
        ctx.track(this);
        ctx.listen(this);
        for (String name : List.of("enderchest", "invsee", "workbench", "anvil", "grindstone", "stonecutter",
                "loom", "cartographytable", "smithingtable", "clear", "near", "skull", "more", "itemname", "lore"))
            ctx.permission("atrium." + name, PermissionDefault.OP, "Use /" + name);
        for (String node : List.of("enderchest.others", "enderchest.others.modify", "invsee.others", "invsee.modify",
                "clear.others", "itemname.color", "more.clone", "more.oversize"))
            ctx.permission("atrium." + node, PermissionDefault.OP, "Player utility permission: " + node);
        ctx.permission("atrium.invsee.modify.offline", PermissionDefault.OP,
                "Edit the last saved inventory of an offline player");
        ctx.permission("atrium.near.radius.unlimited", PermissionDefault.FALSE, "Remove the near radius limit");
        ctx.permission("atrium.near.radius." + Math.max(1, config.get().nearRadius), PermissionDefault.FALSE,
                "Numeric near radius allowance");
        new InventoryViewer(ctx).register();
        menu("workbench", p -> MenuType.CRAFTING.builder().checkReachable(false).build(p).open());
        menu("anvil", p -> MenuType.ANVIL.builder().checkReachable(false).build(p).open());
        menu("grindstone", p -> MenuType.GRINDSTONE.builder().checkReachable(false).build(p).open());
        menu("stonecutter", p -> MenuType.STONECUTTER.builder().checkReachable(false).build(p).open());
        menu("loom", p -> MenuType.LOOM.builder().checkReachable(false).build(p).open());
        menu("cartographytable", p -> MenuType.CARTOGRAPHY_TABLE.builder().checkReachable(false).build(p).open());
        menu("smithingtable", p -> MenuType.SMITHING.builder().checkReachable(false).build(p).open());
        registerClear();
        register(Cmds.literal("near", "atrium.near").executes(this::near)
                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                        .suggests(Cmds.suggest(() -> List.of("50", "100", "200"))).executes(this::near)));
        register(Cmds.literal("skull", "atrium.skull")
                .executes(c -> usage(c, "skull"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users())).executes(this::skull)));
        registerMore();
        register(Cmds.literal("itemname", "atrium.itemname")
                .executes(c -> usage(c, "itemname"))
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(c -> edit(c, "name"))));
        var lore = Cmds.literal("lore", "atrium.lore").executes(c -> usage(c, "lore"));
        lore.then(Commands.literal("clear").executes(c -> edit(c, "clear")));
        lore.then(Commands.literal("add").executes(c -> usage(c, "lore")).then(Commands.argument("text", StringArgumentType.greedyString())
                .executes(c -> edit(c, "add"))));
        lore.then(Commands.literal("insert").executes(c -> usage(c, "lore"))
                .then(loreLine().then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> edit(c, "insert")))));
        lore.then(Commands.literal("set").executes(c -> usage(c, "lore"))
                .then(loreLine().then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(c -> edit(c, "set")))));
        lore.then(Commands.literal("remove").executes(c -> usage(c, "lore"))
                .then(loreLine().executes(c -> edit(c, "remove"))));
        register(lore, "itemlore");
    }

    /** A lore line number (1-based) or {@code *} for every line. */
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> loreLine() {
        return Commands.argument("line", StringArgumentType.word())
                .suggests(Cmds.suggest(() -> List.of("1", "2", "3", "*")));
    }

    private void register(LiteralArgumentBuilder<CommandSourceStack> node) {
        register(node, new String[0]);
    }

    private void register(LiteralArgumentBuilder<CommandSourceStack> node, String... aliases) {
        ctx.command(CommandSpec.of(node.build(), CommandDescriptions.of(node.getLiteral()), aliases));
    }

    private int usage(CommandContext<CommandSourceStack> c, String command) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "player.usage." + command);
    }

    private void registerMore() {
        var root = Cmds.literal("more", "atrium.more").executes(c -> more(c, null, false));
        for (String alias : List.of("clone", "-clone")) {
            root.then(Commands.literal(alias).requires(Cmds.permission("atrium.more.clone"))
                    .executes(c -> more(c, null, true)));
        }
        root.then(Commands.argument("amount", IntegerArgumentType.integer(1, 99))
                .suggests(Cmds.suggest(() -> List.of("1", "8", "16", "32", "64")))
                .executes(c -> more(c, IntegerArgumentType.getInteger(c, "amount"), false)));
        register(root);
    }

    private int more(CommandContext<CommandSourceStack> c, Integer amount, boolean clone)
            throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        onPlayer(player, () -> {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.isEmpty()) { reply(player, "empty-hand"); return; }
            if (clone) {
                ItemStack copy = hand.clone();
                var leftover = player.getInventory().addItem(copy);
                int leftoverAmount = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
                int added = copy.getAmount() - leftoverAmount;
                if (added <= 0) { reply(player, "inventory-full"); return; }
                reply(player, "more-clone", Msg.number("count", added));
                return;
            }
            int oversizeLimit = Math.clamp(config.get().maxOversizeStack, 1, 99);
            int limit = player.hasPermission("atrium.more.oversize")
                    ? Math.max(hand.getMaxStackSize(), oversizeLimit)
                    : hand.getMaxStackSize();
            int target = amount == null ? limit : amount;
            if (target > limit) {
                reply(player, "more-limit", Msg.number("max", limit));
                return;
            }
            hand.setAmount(target);
            player.getInventory().setItemInMainHand(hand);
            reply(player, "more", Msg.number("count", target));
        });
        return Cmds.OK;
    }

    private void menu(String name, Consumer<Player> action) {
        register(Cmds.literal(name, "atrium." + name).executes(c -> {
            Player player = cmds.player(c.getSource());
            onPlayer(player, () -> action.accept(player));
            return Cmds.OK;
        }));
    }

    private void onPlayer(Player player, Runnable action) {
        ctx.scheduler().entity(player, () -> {
            if (cancelled) return;
            try { action.run(); }
            catch (RuntimeException e) { failed(player, "Player utility", e); }
        }, () -> reply(player, "target-left"));
    }

    private void failed(CommandSender sender, String operation, Throwable error) {
        ctx.logger().error("{} failed", operation, error);
        reply(sender, "failed");
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (cancelled) return;
        if (sender instanceof Player p) ctx.scheduler().entity(p, () -> {
            if (!cancelled) ctx.messages().send(p, "player." + key, tags);
        }, null);
        else ctx.messages().send(sender, "player." + key, tags);
    }

    private int edit(CommandContext<CommandSourceStack> c, String operation) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String input = Cmds.hasArgument(c, "text") ? StringArgumentType.getString(c, "text") : "";
        String line = Cmds.hasArgument(c, "line") ? StringArgumentType.getString(c, "line") : "";
        if (input.length() > Math.clamp(config.get().maxItemTextLength, 1, 8192))
            throw cmds.fail(c.getSource(), "player.text-too-long");
        onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) { reply(player, "empty-hand"); return; }
            var meta = item.getItemMeta();
            var text = InventoryValues.text(input, player.hasPermission("atrium.itemname.color"));
            if (operation.equals("name")) meta.displayName(text);
            else {
                try { meta.lore(InventoryValues.lore(meta.lore(), operation, line, text)); }
                catch (IllegalArgumentException e) { reply(player, "invalid-lore-line"); return; }
            }
            if (!item.setItemMeta(meta)) { reply(player, "failed"); return; }
            player.getInventory().setItemInMainHand(item);
            reply(player, "item-updated");
        });
        return Cmds.OK;
    }

    private void registerClear() {
        register(Cmds.literal("clear", "atrium.clear").executes(this::clear)
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::clear)
                        .then(Commands.argument("item", StringArgumentType.word())
                                .suggests(Cmds.suggest(InventoryCommands::itemNames)).executes(this::clear)
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("all", "1", "16", "32", "64")))
                                        .executes(this::clear)))));
    }

    /** "all" plus every item key, for /clear tab completion. */
    private static List<String> itemNames() {
        List<String> names = new java.util.ArrayList<>();
        names.add("all");
        for (Material material : Material.values()) {
            if (material.isItem() && !material.isAir() && !material.isLegacy()) {
                names.add(material.getKey().getKey());
            }
        }
        return names;
    }

    private int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", "atrium.clear.others");
        CommandSender sender = c.getSource().getSender();
        String itemName = Cmds.hasArgument(c, "item") ? StringArgumentType.getString(c, "item") : "all";
        Material material = itemName.equalsIgnoreCase("all") ? null : Material.matchMaterial(itemName);
        if (material == Material.AIR || (material == null && !itemName.equalsIgnoreCase("all"))
                || (material != null && !material.isItem())) throw cmds.fail(c.getSource(), "player.invalid-item");
        int amount;
        try { amount = InventoryValues.amount(Cmds.hasArgument(c, "amount") ? StringArgumentType.getString(c, "amount") : "all"); }
        catch (IllegalArgumentException e) { throw cmds.fail(c.getSource(), "player.invalid-amount"); }
        if (!sender.equals(target)) {
            long now = System.nanoTime();
            Confirmation old = confirmations.remove(sender);
            if (old == null || !old.target().equals(target.getUniqueId()) || old.item() != material
                    || old.amount() != amount || now - old.expires() >= 0) {
                int seconds = Math.clamp(config.get().clearConfirmationSeconds, 1, 300);
                var pending = new Confirmation(target.getUniqueId(), material, amount,
                        now + TimeUnit.SECONDS.toNanos(seconds));
                confirmations.put(sender, pending);
                ctx.scheduler().globalLater(() -> confirmations.remove(sender, pending), seconds * 20L);
                reply(sender, "clear-confirm", Msg.unparsed("player", target.getName()), Msg.number("seconds", seconds));
                return Cmds.OK;
            }
        }
        ctx.scheduler().entity(target, () -> {
            if (cancelled) return;
            try {
                int removed = 0;
                var inventory = target.getInventory();
                for (int slot = 0; slot < inventory.getSize() && removed < amount; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (stack == null || stack.isEmpty() || (material != null && stack.getType() != material)) continue;
                    int take = Math.min(stack.getAmount(), amount - removed);
                    stack.setAmount(stack.getAmount() - take);
                    inventory.setItem(slot, stack.isEmpty() ? null : stack);
                    removed += take;
                }
                reply(sender, "cleared", Msg.unparsed("player", target.getName()), Msg.number("count", removed));
                if (!sender.equals(target)) reply(target, "cleared-target", Msg.number("count", removed));
            } catch (RuntimeException e) { failed(sender, "Clear inventory", e); }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    private record Nearby(Player player, String name, UUID world, double x, double y, double z) { }

    private int near(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player viewer = cmds.player(c.getSource());
        int requested = Cmds.hasArgument(c, "radius") ? IntegerArgumentType.getInteger(c, "radius") : Math.max(1, config.get().nearRadius);
        onPlayer(viewer, () -> {
            int limit = Permissions.limit(viewer, "atrium.near.radius", Math.max(1, config.get().nearRadius));
            int radius = Math.min(requested, limit);
            var origin = viewer.getLocation();
            UUID world = origin.getWorld().getUID();
            var futures = Bukkit.getOnlinePlayers().stream().filter(p -> !p.equals(viewer)).map(p ->
                    ctx.scheduler().supplyOnEntity(p, () -> {
                        if (cancelled || !p.isOnline()) return null;
                        var loc = p.getLocation();
                        return new Nearby(p, p.getName(), loc.getWorld().getUID(), loc.getX(), loc.getY(), loc.getZ());
                    }).exceptionally(error -> {
                        if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)
                                && !(error.getCause() instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException))
                            ctx.logger().warn("Nearby player lookup failed", error);
                        return null;
                    })).toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).orTimeout(5, TimeUnit.SECONDS)
                    .whenComplete((unused, error) -> {
                        if (error != null) { failed(viewer, "Near lookup", error); return; }
                        onPlayer(viewer, () -> {
                            var results = new java.util.ArrayList<String>();
                            for (var future : futures) {
                                Nearby p = future.join();
                                if (p == null || !p.world().equals(world) || !viewer.canSee(p.player())) continue;
                                double distance = Math.sqrt(Math.pow(p.x() - origin.getX(), 2)
                                        + Math.pow(p.y() - origin.getY(), 2) + Math.pow(p.z() - origin.getZ(), 2));
                                if (distance <= radius) results.add(p.name() + " (" + String.format(Locale.ROOT, "%.1f", distance) + "m)");
                            }
                            results.sort(String::compareToIgnoreCase);
                            reply(viewer, results.isEmpty() ? "near-empty" : "near-result",
                                    Msg.number("radius", radius), Msg.unparsed("players", String.join(", ", results)));
                        });
                    });
        });
        return Cmds.OK;
    }

    private int skull(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw cmds.fail(c.getSource(), "player.invalid-profile");
        // Paper profile API: complete(true) blocks on the I/O executor only, never on a game thread.
        ctx.scheduler().supplyIo(() -> {
                    var lookup = Bukkit.createProfile(name);
                    lookup.complete(true);
                    return lookup;
                })
                .whenComplete((profile, error) -> {
                    if (cancelled) return;
                    if (error != null) { failed(player, "Resolve skull profile " + name, error); return; }
                    onPlayer(player, () -> {
                        if (!profile.isComplete()) { reply(player, "profile-not-found"); return; }
                        // Reserve no slot across the network lookup. Check current capacity before adding.
                        if (player.getInventory().firstEmpty() < 0) { reply(player, "inventory-full"); return; }
                        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
                        SkullMeta meta = (SkullMeta) skull.getItemMeta();
                        meta.setPlayerProfile(profile);
                        skull.setItemMeta(meta);
                        var remaining = player.getInventory().addItem(skull);
                        reply(player, remaining.isEmpty() ? "skull-given" : "inventory-full", Msg.unparsed("player", name));
                    });
                });
        return Cmds.OK;
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { confirmations.remove(event.getPlayer()); }
    @Override public void cancel() { cancelled = true; confirmations.clear(); }
    @Override public boolean isCancelled() { return cancelled; }
}
