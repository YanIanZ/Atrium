package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.itemtools.ItemFrameEditEvent;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;
import org.bukkit.inventory.meta.WritableBookMeta;
import org.bukkit.util.RayTraceResult;

/** /itemcmdata, /hideflags, /glow, /condense, /dispose, /giveall, /getbook, /itemframe and /repaircost. */
final class ItemToolsCommands implements Listener, TaskHandle {
    /** A confirmed /giveall: only the fingerprint and the expiry, the item is rebuilt from the command. */
    private record GiveAllConfirmation(String fingerprint, long expires) { }

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<ItemToolsConfig> config;
    private final ItemSupport support;
    private final GiveAllQueue giveAllQueue;
    private final DisposeGui dispose;
    private final DonateService donate;
    /** Sender key (UUID or "console") -> pending /giveall confirmation. */
    private final Map<String, GiveAllConfirmation> giveAllConfirmations = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    ItemToolsCommands(ModuleContext ctx, ConfigHandle<ItemToolsConfig> config, ItemSupport support,
            GiveAllQueue giveAllQueue) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.support = support;
        this.giveAllQueue = giveAllQueue;
        this.dispose = new DisposeGui(ctx);
        this.donate = new DonateService(ctx, config);
    }

    void register() {
        ctx.track(this);
        ctx.track(donate);
        ctx.listen(this);
        ctx.listen(donate);
        registerItemcmdata();
        registerHideflags();
        registerGlow();
        registerCondense();
        registerDispose();
        registerGiveall();
        registerGetbook();
        registerItemframe();
        registerRepaircost();
        donate.register();
    }

    private void register(LiteralArgumentBuilder<CommandSourceStack> node, String description) {
        support.register(node, description);
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) throws CommandSyntaxException {
        return support.usage(c, key);
    }

    private void onPlayer(Player player, Runnable action) {
        support.onPlayer(player, action);
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        support.reply(sender, key, tags);
    }

    private void replyNow(Player player, String key, TagResolver... tags) {
        support.replyNow(player, key, tags);
    }

    private void sendTo(Player player, String key, TagResolver... tags) {
        reply(player, key, tags);
    }

    private void registerItemcmdata() {
        register(Cmds.literal("itemcmdata", "atrium.itemcmdata")
                .executes(c -> usage(c, "usage.itemcmdata"))
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("clear")))
                        .executes(this::itemcmdata)),
                "Set or clear the custom model data of the held item");
    }

    private int itemcmdata(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String input = StringArgumentType.getString(c, "value");
        Integer value = null;
        if (!input.equalsIgnoreCase("clear")) {
            try {
                value = parseNonNegative(input, "custom model data");
            } catch (IllegalArgumentException e) {
                throw cmds.fail(c.getSource(), "itemtools.usage.itemcmdata");
            }
        }
        Integer applied = value;
        onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                replyNow(player, "empty-hand");
                return;
            }
            ItemStack updated = item.clone();
            if (applied == null) {
                updated.resetData(DataComponentTypes.CUSTOM_MODEL_DATA);
            } else {
                updated.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                        CustomModelData.customModelData().addFloat(applied));
            }
            player.getInventory().setItemInMainHand(updated);
            if (applied == null) {
                replyNow(player, "itemcmdata-cleared");
            } else {
                replyNow(player, "itemcmdata-set", Msg.number("value", applied));
            }
        });
        return Cmds.OK;
    }

    private void registerHideflags() {
        register(Cmds.literal("hideflags", "atrium.hideflags")
                        .executes(c -> hideflags(c, null))
                        .then(Cmds.literal("on").executes(c -> hideflags(c, true)))
                        .then(Cmds.literal("off").executes(c -> hideflags(c, false))),
                "Hide or show the extra tooltip lines of the held item");
    }

    private int hideflags(CommandContext<CommandSourceStack> c, Boolean wanted) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                replyNow(player, "empty-hand");
                return;
            }
            boolean on = wanted == null ? !hasHiddenTooltip(item) : wanted;
            ItemStack updated = item.clone();
            if (on) {
                updated.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                        .hideTooltip(false)
                        .hiddenComponents(ItemValues.hiddenComponents()));
            } else {
                updated.resetData(DataComponentTypes.TOOLTIP_DISPLAY);
            }
            player.getInventory().setItemInMainHand(updated);
            replyNow(player, on ? "hideflags-on" : "hideflags-off");
        });
        return Cmds.OK;
    }

    private static boolean hasHiddenTooltip(ItemStack item) {
        TooltipDisplay display = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        return display != null && !display.hiddenComponents().isEmpty();
    }

    private void registerGlow() {
        register(Cmds.literal("glow", "atrium.glow")
                        .executes(c -> glow(c, null))
                        .then(Cmds.literal("on").executes(c -> glow(c, true)))
                        .then(Cmds.literal("off").executes(c -> glow(c, false))),
                "Force the enchantment glint of the held item on or off");
    }

    private int glow(CommandContext<CommandSourceStack> c, Boolean wanted) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                replyNow(player, "empty-hand");
                return;
            }
            ItemStack updated = item.clone();
            ItemMeta meta = updated.getItemMeta();
            if (meta == null) {
                replyNow(player, "failed");
                return;
            }
            Boolean current = meta.hasEnchantmentGlintOverride() ? meta.getEnchantmentGlintOverride() : null;
            boolean on = wanted == null ? (current == null || !current) : wanted;
            meta.setEnchantmentGlintOverride(on);
            if (!updated.setItemMeta(meta)) {
                replyNow(player, "failed");
                return;
            }
            player.getInventory().setItemInMainHand(updated);
            replyNow(player, on ? "glow-on" : "glow-off");
        });
        return Cmds.OK;
    }

    private void registerCondense() {
        register(Cmds.literal("condense", "atrium.condense")
                        .executes(c -> condense(c, null))
                        .then(Commands.argument("item", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> CondenseTable.inputs().stream()
                                        .map(material -> material.getKey().getKey()).toList()))
                                .executes(c -> condense(c, StringArgumentType.getString(c, "item")))),
                "Condense nuggets, ingots and loose materials using an exact whitelist");
    }

    private int condense(CommandContext<CommandSourceStack> c, String itemName) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Material selected = null;
        if (itemName != null) {
            selected = Material.matchMaterial(itemName);
            if (selected == null || CondenseTable.recipeFor(selected).isEmpty()) {
                throw cmds.fail(c.getSource(), "itemtools.condense-invalid",
                        Msg.unparsed("item", itemName));
            }
        }
        Material chosen = selected;
        onPlayer(player, () -> condenseNow(player, chosen));
        return Cmds.OK;
    }

    private record CondenseResult(int used, int produced, int dropped) { }

    private void condenseNow(Player player, Material selected) {
        int used = 0;
        int produced = 0;
        int dropped = 0;
        if (selected != null) {
            var recipe = CondenseTable.recipeFor(selected).orElse(null);
            if (recipe == null) {
                replyNow(player, "condense-invalid", Msg.unparsed("item", selected.getKey().getKey()));
                return;
            }
            CondenseResult result = condenseOnce(player, recipe);
            used += result.used();
            produced += result.produced();
            dropped += result.dropped();
        } else {
            for (var recipe : CondenseTable.recipes()) {
                CondenseResult result = condenseOnce(player, recipe);
                used += result.used();
                produced += result.produced();
                dropped += result.dropped();
            }
        }
        if (used == 0) {
            replyNow(player, "condense-none");
            return;
        }
        replyNow(player, "condense-done", Msg.number("used", used), Msg.number("produced", produced));
        if (dropped > 0) {
            replyNow(player, "condense-dropped", Msg.number("dropped", dropped));
        }
    }

    private CondenseResult condenseOnce(Player player, CondenseTable.Recipe recipe) {
        Inventory inventory = player.getInventory();
        List<Integer> slots = new ArrayList<>();
        int available = 0;
        boolean plainOnly = config.get().condenseSkipCustomised;
        for (int slot = 0, storage = storageSlots(player); slot < storage; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getType() != recipe.from()) {
                continue;
            }
            if (plainOnly && !ItemValues.plain(stack)) {
                continue;
            }
            slots.add(slot);
            available += stack.getAmount();
        }
        CondenseTable.Plan plan = CondenseTable.plan(available, recipe.ratio());
        if (plan.consumed() == 0) {
            return new CondenseResult(0, 0, 0);
        }

        int remaining = plan.consumed();
        for (int slot : slots) {
            if (remaining <= 0) {
                break;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.isEmpty() || stack.getType() != recipe.from()) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            stack.setAmount(stack.getAmount() - take);
            inventory.setItem(slot, stack.getAmount() <= 0 ? null : stack);
            remaining -= take;
        }

        int removed = plan.consumed() - remaining;
        if (remaining > 0) {
            ctx.logger().warn("Condense removed {} fewer {} than planned for {}; the inventory changed unexpectedly",
                    remaining, recipe.from(), player.getUniqueId());
        }
        // A complete group is the only thing that can become output. Refund any partial group so no item
        // is ever consumed without a matching result.
        int complete = (removed / recipe.ratio()) * recipe.ratio();
        int refund = removed - complete;
        if (refund > 0) {
            ItemValues.giveOrDrop(player, ItemValues.stacks(recipe.from(), refund));
        }
        int produced = complete / recipe.ratio();
        if (produced == 0) {
            return new CondenseResult(complete, 0, 0);
        }
        int dropped = ItemValues.giveOrDrop(player, ItemValues.stacks(recipe.to(), produced));
        return new CondenseResult(complete, produced, dropped);
    }

    private void registerDispose() {
        register(Cmds.literal("dispose", "atrium.dispose").executes(c -> {
            Player player = cmds.player(c.getSource());
            onPlayer(player, () -> dispose.open(player));
            return Cmds.OK;
        }), "Open a trash menu; everything left in it is deleted when you close it");
    }

    private void registerGiveall() {
        var amount = Commands.argument("amount", IntegerArgumentType.integer(1));
        var offline = Cmds.literal("offline", "atrium.giveall.offline");
        var hand = Cmds.literal("hand", "atrium.giveall")
                .executes(c -> giveallHand(c, 1, false))
                .then(amount
                        .executes(c -> giveallHand(c, IntegerArgumentType.getInteger(c, "amount"), false))
                        .then(offline.executes(c -> giveallHand(c,
                                IntegerArgumentType.getInteger(c, "amount"), true))))
                .then(offline.executes(c -> giveallHand(c, 1, true)));
        var from = Cmds.literal("from", "atrium.giveall")
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(c -> giveallFrom(c, 1, false))
                        .then(amount
                                .executes(c -> giveallFrom(c, IntegerArgumentType.getInteger(c, "amount"), false))
                                .then(offline.executes(c -> giveallFrom(c,
                                        IntegerArgumentType.getInteger(c, "amount"), true)))));
        var item = Commands.argument("item", StringArgumentType.word()).suggests(itemSuggestions());
        register(Cmds.literal("giveall", "atrium.giveall")
                        .executes(c -> usage(c, "usage.giveall"))
                        .then(hand)
                        .then(from)
                        .then(item
                                .executes(c -> giveallItem(c, 1, false))
                                .then(amount
                                        .executes(c -> giveallItem(c, IntegerArgumentType.getInteger(c, "amount"),
                                                false))
                                        .then(offline.executes(c -> giveallItem(c,
                                                IntegerArgumentType.getInteger(c, "amount"), true))))
                                .then(offline.executes(c -> giveallItem(c, 1, true)))),
                "Give an item to every online player, or queue it for offline players");
    }

    private static SuggestionProvider<CommandSourceStack> itemSuggestions() {
        return Cmds.suggest(ItemValues::itemNames);
    }

    private int giveallItem(CommandContext<CommandSourceStack> c, int amount, boolean offline)
            throws CommandSyntaxException {
        String itemName = StringArgumentType.getString(c, "item");
        Material material = Material.matchMaterial(itemName);
        if (material == null) {
            throw cmds.fail(c.getSource(), "itemtools.giveall-invalid-item", Msg.unparsed("item", itemName));
        }
        giveallTemplate(c.getSource().getSender(), new ItemStack(material), amount, offline);
        return Cmds.OK;
    }

    /** {@code /giveall hand}: copies the sender's own held stack (read on the sender's thread). */
    private int giveallHand(CommandContext<CommandSourceStack> c, int amount, boolean offline)
            throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        giveallTemplate(player, player.getInventory().getItemInMainHand().clone(), amount, offline);
        return Cmds.OK;
    }

    /** {@code /giveall from <player>}: copies another player's held stack on their own thread. */
    private int giveallFrom(CommandContext<CommandSourceStack> c, int amount, boolean offline)
            throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(target, () -> target.getInventory().getItemInMainHand().clone())
                .whenComplete((item, error) -> {
                    if (error != null) {
                        if (!isRetired(error)) {
                            support.failed(sender, "Reading the hand of " + target.getName(), error);
                        }
                        return;
                    }
                    giveallTemplate(sender, item, amount, offline);
                });
        return Cmds.OK;
    }

    /** Validates, asks for confirmation when needed, then hands the stack (or the queue) on. */
    private void giveallTemplate(CommandSender sender, ItemStack template, int amount, boolean offline) {
        if (template == null || template.isEmpty()) {
            reply(sender, "giveall-empty-hand");
            return;
        }
        int maximum = config.get().maxGiveallAmount();
        if (amount < 1 || amount > maximum) {
            reply(sender, "giveall-invalid-amount", Msg.number("max", maximum));
            return;
        }
        if (offline && !sender.hasPermission("atrium.giveall.offline")) {
            reply(sender, "giveall-no-offline");
            return;
        }
        String fingerprint;
        try {
            fingerprint = Base64.getEncoder()
                    .encodeToString(ItemStack.serializeItemsAsBytes(ItemValues.copies(template, amount)));
        } catch (RuntimeException e) {
            support.failed(sender, "Fingerprinting the /giveall item", e);
            return;
        }
        String key = sender instanceof Player player ? player.getUniqueId().toString() : "console";
        int seconds = config.get().confirmationSeconds();
        if (amount > config.get().confirmAbove()) {
            long now = System.nanoTime();
            GiveAllConfirmation old = giveAllConfirmations.remove(key);
            if (old == null || !old.fingerprint().equals(fingerprint) || now - old.expires() >= 0) {
                GiveAllConfirmation pending = new GiveAllConfirmation(fingerprint,
                        now + TimeUnit.SECONDS.toNanos(seconds));
                giveAllConfirmations.put(key, pending);
                ctx.track(ctx.scheduler().globalLater(() -> giveAllConfirmations.remove(key, pending), seconds * 20L));
                reply(sender, "giveall-confirm", Msg.number("amount", amount),
                        Msg.component("item", template.effectiveName()), Msg.number("seconds", seconds),
                        Msg.component("scope", scope(sender, offline)));
                return;
            }
        } else {
            giveAllConfirmations.remove(key);
        }
        executeGiveall(sender, template, amount, offline);
    }

    private Component scope(CommandSender sender, boolean offline) {
        return ctx.messages().render(sender, offline ? "itemtools.giveall-scope-offline"
                : "itemtools.giveall-scope-online");
    }

    private void executeGiveall(CommandSender sender, ItemStack template, int amount, boolean offline) {
        List<ItemStack> perPlayer = ItemValues.copies(template, amount);
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        List<CompletableFuture<Boolean>> futures = players.stream()
                .map(player -> ctx.scheduler().supplyOnEntity(player, () -> {
                    if (cancelled || !player.isOnline()) {
                        return false;
                    }
                    ItemValues.giveOrDrop(player, copyOf(perPlayer));
                    return true;
                }).exceptionally(error -> {
                    if (!isRetired(error)) {
                        ctx.logger().warn("Giving the /giveall item to {} failed", player.getUniqueId(), error);
                    }
                    return false;
                }))
                .toList();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Giveall task failed", error);
            }
            long delivered = futures.stream().filter(CompletableFuture::join).count();
            if (players.isEmpty()) {
                reply(sender, "giveall-none");
            } else {
                reply(sender, "giveall-sent", Msg.number("players", delivered), Msg.number("amount", amount),
                        Msg.component("item", template.effectiveName()));
            }
            if (offline) {
                queueOffline(sender, template, amount, players);
            }
        });
    }

    /** Fresh copies for one recipient: never share a stack instance between two players. */
    private static List<ItemStack> copyOf(List<ItemStack> stacks) {
        return stacks.stream().map(ItemStack::clone).toList();
    }

    private void queueOffline(CommandSender sender, ItemStack template, int amount, List<Player> online) {
        Set<UUID> onlineIds = online.stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
        int limit = config.get().offlineLimit();
        var known = ctx.users().knownAccounts(limit + onlineIds.size());
        List<UUID> ids = known.stream().map(dev.yanianz.atrium.api.user.PlayerRef::uuid).toList();
        List<UUID> targets = GiveAllTargets.offlineRecipients(ids, onlineIds, limit);
        if (targets.isEmpty()) {
            reply(sender, "giveall-offline-none");
            return;
        }
        byte[] blob;
        try {
            blob = ItemStack.serializeItemsAsBytes(ItemValues.copies(template, amount));
        } catch (RuntimeException e) {
            support.failed(sender, "Serializing the /giveall item for offline players", e);
            return;
        }
        giveAllQueue.enqueue(targets, blob).whenComplete((count, error) -> {
            if (error != null) {
                support.failed(sender, "Queuing the /giveall item for offline players", error);
                return;
            }
            reply(sender, "giveall-queued", Msg.number("players", count), Msg.number("amount", amount),
                    Msg.component("item", template.effectiveName()));
        });
    }

    private static boolean isRetired(Throwable error) {
        return error instanceof Scheduler.EntityRetiredException
                || error.getCause() instanceof Scheduler.EntityRetiredException;
    }

    private void registerGetbook() {
        register(Cmds.literal("getbook", "atrium.getbook").executes(this::getbook),
                "Copy the written book in your hand into an empty book and quill from your inventory");
    }

    @SuppressWarnings("deprecation")
    private int getbook(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        onPlayer(player, () -> {
            // Everything below runs in one tick on the player's thread: the hand and the empty book are read
            // and replaced without a gap, so the copy always consumes exactly one empty book and quill.
            Inventory inventory = player.getInventory();
            ItemStack hand = player.getInventory().getItemInMainHand();
            BookMeta book = hand.getType() == Material.WRITTEN_BOOK && hand.getItemMeta() instanceof BookMeta meta
                    ? meta : null;
            String author = book == null ? null : book.getAuthor();
            boolean own = author != null && author.equalsIgnoreCase(player.getName());
            int blank = BookCopy.firstBlank(storageSlots(player), slot -> isBlankWritableBook(inventory.getItem(slot)));
            switch (BookCopy.decide(book != null, own, player.hasPermission("atrium.getbook.others"), blank)) {
                case NOT_BOOK -> replyNow(player, "getbook-not-book");
                case DENIED -> replyNow(player, "getbook-denied");
                case NO_BLANK_BOOK -> replyNow(player, "getbook-no-blank");
                case COPY -> copyBook(player, inventory, book, blank);
            }
        });
        return Cmds.OK;
    }

    /** Writes the pages into one empty book and quill from {@code slot}; never creates a book from nothing. */
    @SuppressWarnings("deprecation")
    private void copyBook(Player player, Inventory inventory, BookMeta book, int slot) {
        ItemStack blank = inventory.getItem(slot);
        if (!isBlankWritableBook(blank)) {
            replyNow(player, "getbook-no-blank");
            return;
        }
        ItemStack copy = blank.asOne();
        if (!(copy.getItemMeta() instanceof WritableBookMeta writable)) {
            replyNow(player, "failed");
            return;
        }
        writable.setPages(book.getPages());
        if (!copy.setItemMeta(writable)) {
            replyNow(player, "failed");
            return;
        }
        if (blank.getAmount() <= 1) {
            inventory.setItem(slot, copy); // replaced in place: one empty book became the copy
            replyNow(player, "getbook-given");
            return;
        }
        ItemStack rest = blank.clone();
        rest.setAmount(blank.getAmount() - 1);
        inventory.setItem(slot, rest);
        int dropped = ItemValues.giveOrDrop(player, List.of(copy));
        replyNow(player, dropped > 0 ? "getbook-dropped" : "getbook-given");
    }

    /** A book and quill without any written text. */
    @SuppressWarnings("deprecation")
    private static boolean isBlankWritableBook(ItemStack item) {
        if (item == null || item.isEmpty() || item.getType() != Material.WRITABLE_BOOK) {
            return false;
        }
        if (!(item.getItemMeta() instanceof WritableBookMeta meta)) {
            return false;
        }
        return meta.getPages().stream().allMatch(page -> page == null || page.isEmpty());
    }

    private void registerItemframe() {
        var root = Cmds.literal("itemframe", "atrium.itemframe").executes(c -> usage(c, "usage.itemframe"));
        for (FrameAction action : FrameAction.values()) {
            root.then(Cmds.literal(action.id(), "atrium.itemframe." + action.id())
                    .executes(c -> itemframe(c, action)));
        }
        register(root, "Change the item frame you are looking at");
    }

    private int itemframe(CommandContext<CommandSourceStack> c, FrameAction action) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        onPlayer(player, () -> itemframeNow(player, action));
        return Cmds.OK;
    }

    private void itemframeNow(Player player, FrameAction action) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), config.get().frameRange(),
                FluidCollisionMode.NEVER, true, 0.1, entity -> entity instanceof ItemFrame);
        if (hit == null || !(hit.getHitEntity() instanceof ItemFrame frame)) {
            replyNow(player, "frame-none");
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(frame)) {
            applyFrame(player, frame, action);
        } else {
            // The frame may belong to another region; do the change and the event on its own thread.
            ctx.scheduler().region(frame.getLocation(), () -> applyFrame(player, frame, action));
        }
    }

    private void applyFrame(Player player, ItemFrame frame, FrameAction action) {
        if (cancelled) {
            return;
        }
        if (!frame.isValid() || !Bukkit.isOwnedByCurrentRegion(frame)) {
            sendTo(player, "frame-gone");
            return;
        }
        ItemFrameEditEvent event = new ItemFrameEditEvent(player, frame, action.eventAction());
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            sendTo(player, "frame-protected");
            return;
        }
        action.apply(frame);
        sendTo(player, "frame-updated", Msg.unparsed("action", action.id()));
    }

    private void registerRepaircost() {
        ctx.command(CommandSpec.of(Cmds.literal("repaircost", "atrium.repaircost")
                        .executes(this::showRepaircost)
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, Integer.MAX_VALUE))
                                .executes(this::setRepaircost))
                        .build(),
                "Show or set the repair cost component of the held item", "anvilrepaircost"));
    }

    private int showRepaircost(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                replyNow(player, "empty-hand");
                return;
            }
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Repairable repairable)) {
                replyNow(player, "repaircost-unsupported");
                return;
            }
            replyNow(player, "repaircost-show", Msg.number("value", repairable.hasRepairCost()
                    ? repairable.getRepairCost() : 0));
        });
        return Cmds.OK;
    }

    private int setRepaircost(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        int value = IntegerArgumentType.getInteger(c, "value");
        onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                replyNow(player, "empty-hand");
                return;
            }
            ItemStack updated = item.clone();
            ItemMeta meta = updated.getItemMeta();
            if (!(meta instanceof Repairable repairable)) {
                replyNow(player, "repaircost-unsupported");
                return;
            }
            repairable.setRepairCost(value);
            if (!updated.setItemMeta(repairable)) {
                replyNow(player, "failed");
                return;
            }
            player.getInventory().setItemInMainHand(updated);
            replyNow(player, "repaircost-set", Msg.number("value", value));
        });
        return Cmds.OK;
    }

    private static int parseNonNegative(String input, String what) {
        int value;
        try {
            value = Integer.parseInt(input);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(what + " must be a whole number", e);
        }
        if (value < 0) {
            throw new IllegalArgumentException(what + " cannot be negative");
        }
        return value;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        giveAllConfirmations.remove(event.getPlayer().getUniqueId().toString());
    }

    @Override
    public void cancel() {
        cancelled = true;
        giveAllConfirmations.clear();
        donate.cancel();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * The storage part (hotbar + main inventory) of the live player inventory: 36 on a vanilla server. Armour
     * and off hand follow it and are never touched by /condense or /getbook. Player's thread.
     */
    private static int storageSlots(Player player) {
        return player.getInventory().getStorageContents().length;
    }
}
