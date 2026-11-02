package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /donate}: offer the held stack, then let the receiver accept or deny it.
 *
 * <p>Accepting removes the item from the giver first and only then adds it to the receiver. The offer is
 * a clone, so a changed held item is rejected instead of donating something the receiver never saw.
 */
final class DonateService implements Listener, TaskHandle {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<ItemToolsConfig> config;
    private final DonateOffers<ItemStack> offers;
    private volatile boolean cancelled;

    DonateService(ModuleContext ctx, ConfigHandle<ItemToolsConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.offers = new DonateOffers<>(config.get().maxPending());
    }

    void register() {
        var root = Cmds.literal("donate", "atrium.donate")
                .executes(c -> usage(c, "usage.donate"))
                .then(Cmds.literal("accept", "atrium.donate")
                        .executes(this::accept)
                        .then(Commands.argument("donor", ArgumentTypes.player()).executes(this::accept)))
                .then(Cmds.literal("deny", "atrium.donate")
                        .executes(this::deny)
                        .then(Commands.argument("donor", ArgumentTypes.player()).executes(this::deny)))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::offer));
        ctx.command(CommandSpec.of(root.build(), "Donate the held item to another online player"));
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) throws CommandSyntaxException {
        throw cmds.fail(c.getSource(), "itemtools." + key);
    }

    private int offer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player donor = cmds.player(c.getSource());
        Player receiver = cmds.argPlayer(c, "target");
        if (donor.getUniqueId().equals(receiver.getUniqueId())) {
            throw cmds.fail(c.getSource(), "itemtools.donate-self");
        }
        ItemStack hand = donor.getInventory().getItemInMainHand();
        if (hand.isEmpty()) {
            throw cmds.fail(c.getSource(), "itemtools.donate-no-item");
        }
        ItemStack offered = hand.clone();
        String fingerprint;
        try {
            fingerprint = Base64.getEncoder().encodeToString(offered.serializeAsBytes());
        } catch (RuntimeException e) {
            ctx.logger().error("Could not fingerprint the item donated by {}", donor.getUniqueId(), e);
            throw cmds.fail(c.getSource(), "itemtools.donate-failed");
        }
        int seconds = config.get().offerSeconds();
        long now = System.currentTimeMillis();
        DonateOffers.Status status = offers.offer(donor.getUniqueId(), receiver.getUniqueId(), offered,
                fingerprint, offered.getAmount(), now, now + seconds * 1000L);
        switch (status) {
            case PENDING -> throw cmds.fail(c.getSource(), "itemtools.donate-pending",
                    Msg.unparsed("player", receiver.getName()));
            case FULL -> throw cmds.fail(c.getSource(), "itemtools.donate-full",
                    Msg.unparsed("player", receiver.getName()), Msg.number("max", config.get().maxPending()));
            case CREATED, REFRESHED -> {
                // Expiry is also checked lazily on every access; this timer only releases memory.
                ctx.track(ctx.scheduler().globalLater(() -> offers.removeExpired(System.currentTimeMillis()),
                        seconds * 20L));
            }
        }
        TagResolver item = Msg.component("item", ItemValues.name(offered));
        reply(donor, "donate-offer-sent", item, Msg.unparsed("player", receiver.getName()),
                Msg.number("seconds", seconds));
        sendTo(receiver, "donate-offer-received", item, Msg.unparsed("player", donor.getName()),
                Msg.number("seconds", seconds));
        return Cmds.OK;
    }

    private int accept(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player receiver = cmds.player(c.getSource());
        long now = System.currentTimeMillis();
        UUID donorId;
        if (Cmds.hasArgument(c, "donor")) {
            donorId = cmds.argPlayer(c, "donor").getUniqueId();
        } else {
            List<DonateOffers.Offer<ItemStack>> pending = offers.pending(receiver.getUniqueId(), now);
            if (pending.isEmpty()) {
                throw cmds.fail(c.getSource(), "itemtools.donate-none");
            }
            if (pending.size() > 1) {
                throw cmds.fail(c.getSource(), "itemtools.donate-ambiguous");
            }
            donorId = pending.getFirst().donor();
        }
        DonateOffers.Offer<ItemStack> offer = offers.remove(receiver.getUniqueId(), donorId, now).orElse(null);
        if (offer == null) {
            throw cmds.fail(c.getSource(), "itemtools.donate-none");
        }
        Player donor = Bukkit.getPlayer(offer.donor());
        if (donor == null) {
            reply(receiver, "donate-donor-left");
            return Cmds.OK;
        }
        acceptOffer(receiver, donor, offer);
        return Cmds.OK;
    }

    private void acceptOffer(Player receiver, Player donor, DonateOffers.Offer<ItemStack> offer) {
        String donorName = donor.getName();
        String receiverName = receiver.getName();
        TagResolver item = Msg.component("item", ItemValues.name(offer.item()));
        ctx.scheduler().entity(donor, () -> {
            if (cancelled) {
                return;
            }
            if (!donor.isOnline() || !Bukkit.isOwnedByCurrentRegion(donor)) {
                sendTo(receiver, "donate-invalid");
                return;
            }
            ItemStack current = donor.getInventory().getItemInMainHand();
            if (current.isEmpty() || !current.isSimilar(offer.item()) || current.getAmount() != offer.amount()) {
                sendTo(receiver, "donate-invalid");
                return;
            }
            ItemStack taken = current.clone();
            Location donorLocation = donor.getLocation().clone();
            donor.getInventory().setItemInMainHand(null);
            if (!donor.getInventory().getItemInMainHand().isEmpty()) {
                ctx.logger().error("Could not remove the donated item from {}; nothing was transferred",
                        donor.getUniqueId());
                sendTo(receiver, "donate-failed");
                return;
            }
            ctx.scheduler().entity(receiver, () -> {
                if (!receiver.isOnline()) {
                    restore(donor.getUniqueId(), donorLocation, taken);
                    return;
                }
                int dropped = ItemValues.giveOrDrop(receiver, List.of(taken));
                ctx.messages().send(receiver, "itemtools.donate-accepted-receiver", item,
                        Msg.unparsed("player", donorName));
                if (dropped > 0) {
                    ctx.messages().send(receiver, "itemtools.donate-dropped", item);
                }
                sendTo(donor, "donate-accepted-donor", item, Msg.unparsed("player", receiverName));
            }, () -> restore(donor.getUniqueId(), donorLocation, taken));
        }, () -> sendTo(receiver, "donate-invalid"));
    }

    private int deny(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player receiver = cmds.player(c.getSource());
        long now = System.currentTimeMillis();
        UUID donorId;
        if (Cmds.hasArgument(c, "donor")) {
            donorId = cmds.argPlayer(c, "donor").getUniqueId();
        } else {
            List<DonateOffers.Offer<ItemStack>> pending = offers.pending(receiver.getUniqueId(), now);
            if (pending.isEmpty()) {
                throw cmds.fail(c.getSource(), "itemtools.donate-none");
            }
            if (pending.size() > 1) {
                throw cmds.fail(c.getSource(), "itemtools.donate-ambiguous");
            }
            donorId = pending.getFirst().donor();
        }
        DonateOffers.Offer<ItemStack> offer = offers.remove(receiver.getUniqueId(), donorId, now).orElse(null);
        if (offer == null) {
            throw cmds.fail(c.getSource(), "itemtools.donate-none");
        }
        reply(receiver, "donate-denied");
        Player donor = Bukkit.getPlayer(offer.donor());
        if (donor != null) {
            sendTo(donor, "donate-denied-donor", Msg.unparsed("player", receiver.getName()));
        }
        return Cmds.OK;
    }

    private void restore(UUID donorId, Location donorLocation, ItemStack item) {
        Player donor = Bukkit.getPlayer(donorId);
        if (donor != null) {
            ctx.scheduler().entity(donor, () -> {
                int dropped = ItemValues.giveOrDrop(donor, List.of(item));
                ctx.messages().send(donor, "itemtools.donate-restored");
                if (dropped > 0) {
                    ctx.messages().send(donor, "itemtools.donate-restored-dropped");
                }
            }, () -> dropAt(donorLocation, item));
        } else {
            dropAt(donorLocation, item);
        }
    }

    private void dropAt(Location location, ItemStack item) {
        if (location.getWorld() == null || cancelled) {
            logLost(item, null);
            return;
        }
        ctx.scheduler().region(location, () -> {
            try {
                location.getWorld().dropItem(location, item);
            } catch (RuntimeException e) {
                logLost(item, e);
            }
        });
    }

    private void logLost(ItemStack item, Throwable error) {
        String data;
        try {
            data = Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (RuntimeException e) {
            data = "unavailable";
        }
        if (error == null) {
            ctx.logger().error("Could not restore a donated item. Base64 item data for manual recovery: {}", data);
        } else {
            ctx.logger().error("Could not restore a donated item. Base64 item data for manual recovery: {}", data, error);
        }
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    ctx.messages().send(player, "itemtools." + key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(sender, "itemtools." + key, tags);
        }
    }

    private void sendTo(Player player, String key, TagResolver... tags) {
        reply(player, key, tags);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        for (DonateOffers.Offer<ItemStack> offer : offers.removePlayer(uuid)) {
            if (offer.receiver().equals(uuid)) {
                Player donor = Bukkit.getPlayer(offer.donor());
                if (donor != null) {
                    sendTo(donor, "donate-receiver-left", Msg.unparsed("player", event.getPlayer().getName()));
                }
            } else {
                Player receiver = Bukkit.getPlayer(offer.receiver());
                if (receiver != null) {
                    sendTo(receiver, "donate-donor-left", Msg.unparsed("player", event.getPlayer().getName()));
                }
            }
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        offers.clear();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }
}
