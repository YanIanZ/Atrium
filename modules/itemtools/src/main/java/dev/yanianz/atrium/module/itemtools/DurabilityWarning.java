package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

/**
 * Opt-in low-durability warning. The server owner can switch it off completely; every player then has to
 * turn it on once with {@code /durabilitywarn}. The warning is a chat line, never a change to the item, and
 * repeats at most once per configured interval for the same kind of item.
 */
final class DurabilityWarning implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<ItemToolsConfig> config;
    private final ItemToolsPrefsStore prefs;
    private final ItemSupport support;
    private final RateGate gate = new RateGate();

    DurabilityWarning(ModuleContext ctx, ConfigHandle<ItemToolsConfig> config, ItemToolsPrefsStore prefs,
            ItemSupport support) {
        this.ctx = ctx;
        this.config = config;
        this.prefs = prefs;
        this.support = support;
    }

    void register() {
        ctx.listen(this);
        support.register(Cmds.literal("durabilitywarn", "atrium.durabilitywarn")
                        .executes(this::toggle)
                        .then(Cmds.literal("on").executes(c -> set(c, true)))
                        .then(Cmds.literal("off").executes(c -> set(c, false))),
                "Turn the low-durability warning on or off for yourself");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (!config.get().durabilityWarnings) {
            return;
        }
        Player player = event.getPlayer();
        if (!optedIn(player)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.isEmpty()) {
            return;
        }
        if (!(item.getItemMeta() instanceof Damageable damageable)) {
            return;
        }
        int maximum = item.getType().getMaxDurability();
        int remaining = maximum - damageable.getDamage() - event.getDamage();
        if (!ItemDurability.shouldWarn(maximum, remaining, config.get().durabilityThreshold())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!gate.tryAcquire(player.getUniqueId(), item.getType().name(), now,
                config.get().durabilityCooldownSeconds() * 1000L)) {
            return;
        }
        ctx.messages().send(player, "itemtools.durability-warning",
                Msg.component("item", item.effectiveName()),
                Msg.number("percent", ItemDurability.percentRemaining(maximum, remaining)),
                Msg.number("remaining", remaining));
    }

    private boolean optedIn(Player player) {
        try {
            return ctx.users().user(player).get(prefs.component).durabilityWarning();
        } catch (IllegalStateException e) {
            // The component is still loading (very early login); treat it as "not opted in".
            return false;
        }
    }

    private int toggle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> setNow(player, !optedIn(player)));
        return Cmds.OK;
    }

    private int set(CommandContext<CommandSourceStack> c, boolean value) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> setNow(player, value));
        return Cmds.OK;
    }

    private void setNow(Player player, boolean value) {
        if (!config.get().durabilityWarnings) {
            support.replyNow(player, "durabilitywarn-server-off");
            return;
        }
        AtriumUser user;
        try {
            user = ctx.users().user(player);
            user.update(prefs.component, before -> before.withDurabilityWarning(value));
        } catch (IllegalStateException e) {
            ctx.logger().warn("Could not read the item preferences of {}", player.getUniqueId(), e);
            support.replyNow(player, "failed");
            return;
        }
        UUID uuid = player.getUniqueId();
        ctx.users().saveNow(uuid).whenComplete((ignored, error) -> {
            if (error != null) {
                support.failed(player, "Saving the durability warning preference", error);
                return;
            }
            support.reply(player, value ? "durabilitywarn-on" : "durabilitywarn-off");
        });
    }
}
