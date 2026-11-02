package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * Item commands and item cooldowns, stored as item data components.
 *
 * <p>Only an admin can attach lines ({@code atrium.attachedcmd}, default op) and every line is validated with
 * the {@link ActionService} before it is stored, so an item can never run a command a player supplied: the
 * item only carries admin-written action lines. Running an item uses the holder as the player context and
 * the {@code actions} module decides what is allowed.
 *
 * <p>Cooldowns use the same item data ({@code seconds} + {@code id}) and are enforced before the lines run,
 * in memory per player. The same id on two copies of an item shares one cooldown. A {@link RateGate}
 * swallows the second half of a double click.
 */
final class AttachedCommands implements Listener {
    private final ItemSupport support;
    private final ConfigHandle<ItemToolsConfig> config;
    private final Map<AttachedTrigger, NamespacedKey> lineKeys = new EnumMap<>(AttachedTrigger.class);
    private final NamespacedKey cooldownSecondsKey;
    private final NamespacedKey cooldownIdKey;
    private final RateGate gate = new RateGate();
    private final ItemCooldownTracker cooldowns = new ItemCooldownTracker();
    private volatile boolean warnedNoActions;

    AttachedCommands(ItemSupport support, ConfigHandle<ItemToolsConfig> config) {
        this.support = support;
        this.config = config;
        var plugin = support.ctx().plugin();
        for (AttachedTrigger trigger : AttachedTrigger.values()) {
            lineKeys.put(trigger, new NamespacedKey(plugin, "attached_" + trigger.id()));
        }
        this.cooldownSecondsKey = new NamespacedKey(plugin, "cooldown_seconds");
        this.cooldownIdKey = new NamespacedKey(plugin, "cooldown_id");
    }

    void register() {
        support.ctx().listen(this);
        support.register(Cmds.literal("attachedcmd", "atrium.attachedcmd")
                        .executes(c -> support.usage(c, "usage.attachedcmd"))
                        .then(Cmds.literal("add", "atrium.attachedcmd")
                                .then(Commands.argument("trigger", StringArgumentType.word())
                                        .suggests(Cmds.suggest(AttachedCommands::triggerNames))
                                        .then(Commands.argument("line", StringArgumentType.greedyString())
                                                .executes(this::add))))
                        .then(Cmds.literal("list", "atrium.attachedcmd").executes(this::list))
                        .then(Cmds.literal("remove", "atrium.attachedcmd")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(this::remove)))
                        .then(Cmds.literal("clear", "atrium.attachedcmd").executes(this::clear)),
                "Attach admin action lines to the held item");
        support.register(Cmds.literal("itemcooldown", "atrium.itemcooldown")
                        .executes(this::showCooldown)
                        .then(Cmds.literal("clear", "atrium.itemcooldown").executes(this::clearCooldown))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, ItemCooldown.MAX_SECONDS))
                                .executes(c -> setCooldown(c, ItemCooldown.DEFAULT_ID))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(c -> setCooldown(c,
                                                StringArgumentType.getString(c, "id"))))),
                "Set or clear the cooldown metadata of the held item");
    }

    private static List<String> triggerNames() {
        List<String> names = new ArrayList<>();
        for (AttachedTrigger trigger : AttachedTrigger.values()) {
            names.add(trigger.id());
        }
        return names;
    }

    // ---- creation ----

    private int add(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        String rawTrigger = StringArgumentType.getString(c, "trigger");
        AttachedTrigger trigger = AttachedTrigger.byId(rawTrigger).orElseThrow(
                () -> support.cmds().fail(c.getSource(), "itemtools.attached-invalid-trigger",
                        Msg.unparsed("trigger", rawTrigger)));
        String line = StringArgumentType.getString(c, "line").strip();
        if (line.isEmpty()) {
            throw support.cmds().fail(c.getSource(), "itemtools.attached-invalid-line",
                    Msg.unparsed("error", "the line is empty"));
        }
        ActionService actions = support.ctx().services().get(ActionService.class).orElse(null);
        if (actions == null) {
            throw support.cmds().fail(c.getSource(), "itemtools.attached-no-actions");
        }
        List<String> errors = actions.validate(List.of(line));
        if (!errors.isEmpty()) {
            throw support.cmds().fail(c.getSource(), "itemtools.attached-invalid-line",
                    Msg.unparsed("error", errors.getFirst()));
        }
        support.onPlayer(player, () -> addNow(player, trigger, line));
        return Cmds.OK;
    }

    private void addNow(Player player, AttachedTrigger trigger, String line) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            support.replyNow(player, "empty-hand");
            return;
        }
        ItemStack updated = item.clone();
        ItemMeta meta = updated.getItemMeta();
        List<String> lines = new ArrayList<>(read(meta, trigger));
        int maximum = config.get().maxAttachedLines();
        if (lines.size() >= maximum) {
            support.replyNow(player, "attached-full", Msg.number("max", maximum));
            return;
        }
        lines.add(line);
        write(meta, trigger, lines);
        if (!updated.setItemMeta(meta)) {
            support.replyNow(player, "failed");
            return;
        }
        player.getInventory().setItemInMainHand(updated);
        support.replyNow(player, "attached-added", Msg.number("index", lines.size()),
                Msg.unparsed("trigger", trigger.id()));
    }

    private int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> dispatch(player));
        return Cmds.OK;
    }

    private void dispatch(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            support.replyNow(player, "empty-hand");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        List<String> flat = new ArrayList<>();
        for (AttachedTrigger trigger : AttachedTrigger.values()) {
            for (String line : read(meta, trigger)) {
                flat.add(trigger.id() + ": " + line);
            }
        }
        if (flat.isEmpty()) {
            support.replyNow(player, "attached-empty");
            return;
        }
        for (int i = 0; i < flat.size(); i++) {
            support.replyNow(player, "attached-line", Msg.number("index", i + 1),
                    Msg.unparsed("line", flat.get(i)));
        }
    }

    private int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        int index = IntegerArgumentType.getInteger(c, "index");
        support.onPlayer(player, () -> removeNow(player, index));
        return Cmds.OK;
    }

    private void removeNow(Player player, int index) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.isEmpty()) {
            support.replyNow(player, "empty-hand");
            return;
        }
        ItemStack updated = item.clone();
        ItemMeta meta = updated.getItemMeta();
        int remaining = index;
        for (AttachedTrigger trigger : AttachedTrigger.values()) {
            List<String> lines = new ArrayList<>(read(meta, trigger));
            if (remaining > lines.size()) {
                remaining -= lines.size();
                continue;
            }
            String removed = lines.remove(remaining - 1);
            if (lines.isEmpty()) {
                meta.getPersistentDataContainer().remove(lineKeys.get(trigger));
            } else {
                write(meta, trigger, lines);
            }
            if (!updated.setItemMeta(meta)) {
                support.replyNow(player, "failed");
                return;
            }
            player.getInventory().setItemInMainHand(updated);
            support.replyNow(player, "attached-removed", Msg.unparsed("line", removed));
            return;
        }
        support.replyNow(player, "attached-no-index", Msg.number("index", index));
    }

    private int clear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                support.replyNow(player, "empty-hand");
                return;
            }
            ItemStack updated = item.clone();
            ItemMeta meta = updated.getItemMeta();
            for (AttachedTrigger trigger : AttachedTrigger.values()) {
                meta.getPersistentDataContainer().remove(lineKeys.get(trigger));
            }
            if (!updated.setItemMeta(meta)) {
                support.replyNow(player, "failed");
                return;
            }
            player.getInventory().setItemInMainHand(updated);
            support.replyNow(player, "attached-cleared");
        });
        return Cmds.OK;
    }

    // ---- cooldown metadata ----

    private int showCooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                support.replyNow(player, "empty-hand");
                return;
            }
            ItemCooldown.Metadata metadata = cooldown(item.getItemMeta());
            if (metadata == null) {
                support.replyNow(player, "itemcooldown-none");
                return;
            }
            support.replyNow(player, "itemcooldown-show", Msg.number("seconds", metadata.seconds()),
                    Msg.unparsed("id", metadata.id()));
        });
        return Cmds.OK;
    }

    private int setCooldown(CommandContext<CommandSourceStack> c, String rawId) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        int seconds = IntegerArgumentType.getInteger(c, "seconds");
        ItemCooldown.Metadata metadata = ItemCooldown.of(seconds, rawId);
        support.onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                support.replyNow(player, "empty-hand");
                return;
            }
            ItemStack updated = item.clone();
            ItemMeta meta = updated.getItemMeta();
            if (metadata.seconds() == 0) {
                meta.getPersistentDataContainer().remove(cooldownSecondsKey);
                meta.getPersistentDataContainer().remove(cooldownIdKey);
            } else {
                meta.getPersistentDataContainer().set(cooldownSecondsKey, PersistentDataType.INTEGER,
                        metadata.seconds());
                meta.getPersistentDataContainer().set(cooldownIdKey, PersistentDataType.STRING, metadata.id());
            }
            if (!updated.setItemMeta(meta)) {
                support.replyNow(player, "failed");
                return;
            }
            player.getInventory().setItemInMainHand(updated);
            if (metadata.seconds() == 0) {
                support.replyNow(player, "itemcooldown-cleared");
            } else {
                support.replyNow(player, "itemcooldown-set", Msg.number("seconds", metadata.seconds()),
                        Msg.unparsed("id", metadata.id()));
            }
        });
        return Cmds.OK;
    }

    private int clearCooldown(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item.isEmpty()) {
                support.replyNow(player, "empty-hand");
                return;
            }
            ItemStack updated = item.clone();
            ItemMeta meta = updated.getItemMeta();
            meta.getPersistentDataContainer().remove(cooldownSecondsKey);
            meta.getPersistentDataContainer().remove(cooldownIdKey);
            if (!updated.setItemMeta(meta)) {
                support.replyNow(player, "failed");
                return;
            }
            player.getInventory().setItemInMainHand(updated);
            support.replyNow(player, "itemcooldown-cleared");
        });
        return Cmds.OK;
    }

    // ---- execution ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        AttachedTrigger trigger = trigger(event.getAction());
        if (trigger == null || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        // A command item owns the click: the block behind it must not be used or broken.
        if (run(player, trigger, item, true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        // The consume trigger fires while the item is eaten; only a cooldown stops the meal.
        if (run(event.getPlayer(), AttachedTrigger.CONSUME, event.getItem(), false)) {
            event.setCancelled(true);
        }
    }

    private static @Nullable AttachedTrigger trigger(Action action) {
        return switch (action) {
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> AttachedTrigger.RIGHT;
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> AttachedTrigger.LEFT;
            default -> null;
        };
    }

    /**
     * Runs the lines for one trigger. Returns whether the event must be cancelled: always for a blocked
     * cooldown, and otherwise only for interactions (so the consume trigger never stops the meal).
     */
    private boolean run(Player player, AttachedTrigger trigger, ItemStack item, boolean cancelWhenTriggered) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        List<String> lines = read(meta, trigger);
        if (lines.isEmpty()) {
            return false;
        }
        if (!player.hasPermission("atrium.attachedcmd.use")) {
            return false;
        }
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        ItemCooldown.Metadata cooldown = cooldown(meta);
        if (cooldown != null && !cooldowns.ready(uuid, cooldown.id(), now)) {
            support.replyNow(player, "attached-cooldown",
                    Msg.number("seconds", cooldowns.remainingSeconds(uuid, cooldown.id(), now)));
            return true;
        }
        if (!gate.tryAcquire(uuid, trigger.id(), now, config.get().attachedGateMillis())) {
            return cancelWhenTriggered;
        }
        if (cooldown != null) {
            cooldowns.start(uuid, cooldown.id(), now, cooldown.seconds());
        }
        ActionService actions = support.ctx().services().get(ActionService.class).orElse(null);
        if (actions == null) {
            if (!warnedNoActions) {
                warnedNoActions = true;
                support.ctx().logger().warn("An item has attached commands but the actions module is not enabled");
            }
            return cancelWhenTriggered;
        }
        actions.runAs(player, lines, Map.of("trigger", trigger.id())).whenComplete((result, error) -> {
            if (error != null) {
                support.ctx().logger().error("Attached commands of {} failed", player.getName(), error);
            } else if (!result.success()) {
                support.ctx().logger().warn("Attached commands of {} ended with {}: {}", player.getName(),
                        result.status(), result.detail());
            }
        });
        return cancelWhenTriggered;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        gate.forget(uuid);
        cooldowns.forget(uuid);
    }

    // ---- item data ----

    private List<String> read(ItemMeta meta, AttachedTrigger trigger) {
        List<String> lines = meta.getPersistentDataContainer().get(lineKeys.get(trigger),
                PersistentDataType.LIST.strings());
        return lines == null ? List.of() : List.copyOf(lines);
    }

    private void write(ItemMeta meta, AttachedTrigger trigger, List<String> lines) {
        meta.getPersistentDataContainer().set(lineKeys.get(trigger), PersistentDataType.LIST.strings(),
                List.copyOf(lines));
    }

    private ItemCooldown.@Nullable Metadata cooldown(ItemMeta meta) {
        PersistentDataContainer data = meta.getPersistentDataContainer();
        Integer seconds = data.get(cooldownSecondsKey, PersistentDataType.INTEGER);
        if (seconds == null || seconds <= 0) {
            return null;
        }
        String id = data.get(cooldownIdKey, PersistentDataType.STRING);
        return ItemCooldown.of(seconds, id == null ? ItemCooldown.DEFAULT_ID : id);
    }
}
