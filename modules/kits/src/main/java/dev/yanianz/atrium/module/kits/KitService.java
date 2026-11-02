package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.kits.KitClaimEvent;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Kit definitions in memory plus the claim flow:
 * <ol>
 *   <li>per-target in-flight guard (double clicks and command spam never give twice);</li>
 *   <li>on the target's thread: availability, {@link KitClaimEvent}, space check, then the cooldown/one-time use
 *       is reserved in the user component;</li>
 *   <li>the cost is withdrawn (one atomic economy transaction) before anything is given;</li>
 *   <li>on the target's thread the items are given; if that is impossible the cost is refunded and the
 *       reservation undone. Usage is saved immediately so it survives a restart.</li>
 * </ol>
 */
final class KitService {
    enum Outcome { GIVEN, REFUSED, FAILED }

    private final ModuleContext ctx;
    private final KitStore store;
    private final Supplier<KitsConfig> config;
    private final Map<String, Kit> kits = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    KitService(ModuleContext ctx, KitStore store, Supplier<KitsConfig> config) {
        this.ctx = ctx;
        this.store = store;
        this.config = config;
    }

    void load(List<Kit> loaded) {
        kits.clear();
        loaded.forEach(k -> kits.put(k.name(), k));
    }

    Optional<Kit> kit(String name) {
        return Optional.ofNullable(kits.get(name.toLowerCase(Locale.ROOT)));
    }

    List<Kit> kits() {
        return kits.values().stream().sorted(java.util.Comparator.comparing(Kit::name)).toList();
    }

    void put(Kit kit) {
        kits.put(kit.name(), kit);
    }

    void remove(String name) {
        kits.remove(name);
    }

    /** Re-reads one kit after a version conflict so the next edit starts from what is stored. */
    void refresh(String name) {
        store.loadKit(name).whenComplete((stored, failure) -> {
            if (failure != null) {
                ctx.logger().error("Could not reload kit {} after a conflicting edit", name, failure);
            } else if (stored.isPresent()) {
                kits.put(stored.get().name(), stored.get());
            } else {
                kits.remove(name);
            }
        });
    }

    KitStore store() {
        return store;
    }

    boolean dropOverflow() {
        return !"REFUSE".equalsIgnoreCase(config.get().overflowMode.trim());
    }

    KitUsage.Availability availability(Player player, Kit kit, @Nullable CommandSender bypassHolder) {
        KitUsage usage = ctx.users().user(player).get(store.usage);
        CommandSender holder = bypassHolder == null ? player : bypassHolder;
        return KitUsage.check(usage.claim(kit.name()), kit.cooldownSeconds(), kit.oneTime(), kit.maxUses(),
                System.currentTimeMillis(), holder.hasPermission("atrium.kit.bypass.cooldown"),
                holder.hasPermission("atrium.kit.bypass.onetime"), holder.hasPermission("atrium.kit.bypass.maxuses"));
    }

    /**
     * Claims a kit for {@code target}. Limits (cooldown, one-time) always apply to the target; bypass and cost-bypass
     * permissions are those of {@code sender} (the player who asked). {@code payer} pays the cost, null = free.
     * {@code notify} receives the result messages (null = only the target is told).
     */
    CompletableFuture<Outcome> claim(Player target, Kit kit, @Nullable CommandSender notify, CommandSender sender,
            @Nullable UUID payer, boolean firstJoin) {
        UUID uuid = target.getUniqueId();
        if (!inFlight.add(uuid)) {
            tell(notify == null ? target : notify, "busy");
            return CompletableFuture.completedFuture(Outcome.REFUSED);
        }
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        result.whenComplete((o, t) -> inFlight.remove(uuid));
        ctx.scheduler().entity(target, () -> {
            try {
                reserve(target, kit, notify, sender, payer, firstJoin, result);
            } catch (RuntimeException e) {
                ctx.logger().error("Kit {} claim for {} failed", kit.name(), target.getName(), e);
                tell(notify == null ? target : notify, "failed");
                result.complete(Outcome.FAILED);
            }
        }, () -> result.complete(Outcome.REFUSED));
        return result;
    }

    private void reserve(Player target, Kit kit, @Nullable CommandSender notify, CommandSender sender,
            @Nullable UUID payer, boolean firstJoin, CompletableFuture<Outcome> result) {
        CommandSender reply = notify == null ? target : notify;
        if (!target.isOnline() || target.isDead()) {
            tell(reply, "target-unavailable", Msg.unparsed("player", target.getName()));
            result.complete(Outcome.REFUSED);
            return;
        }
        Kit current = kits.get(kit.name());
        if (current == null) {
            tell(reply, "unknown", Msg.unparsed("kit", kit.name()));
            result.complete(Outcome.REFUSED);
            return;
        }
        KitUsage.Availability availability = availability(target, current, sender);
        if (!availability.available()) {
            switch (availability.status()) {
                case ALREADY_CLAIMED -> tell(reply, "already-claimed", Msg.unparsed("kit", current.name()));
                case MAX_USES -> tell(reply, "max-uses", Msg.unparsed("kit", current.name()),
                        Msg.number("max", current.maxUses()));
                default -> tell(reply, "cooldown", Msg.unparsed("kit", current.name()),
                        Msg.duration("time", availability.remaining()));
            }
            result.complete(Outcome.REFUSED);
            return;
        }
        BigDecimal cost = payer == null ? BigDecimal.ZERO : current.cost();
        EconomyService economy = null;
        if (cost.signum() > 0) {
            economy = ctx.services().get(EconomyService.class).orElse(null);
            if (economy == null) {
                tell(reply, "no-economy");
                result.complete(Outcome.REFUSED);
                return;
            }
        }
        ItemStack[] items;
        try {
            items = KitItems.deserialize(current.items());
        } catch (RuntimeException e) {
            ctx.logger().error("Kit {} has unreadable items", current.name(), e);
            tell(reply, "failed");
            result.complete(Outcome.FAILED);
            return;
        }
        KitClaimEvent event = new KitClaimEvent(target, current.name(), cost, firstJoin);
        if (!event.callEvent()) {
            tell(reply, "cancelled", Msg.unparsed("kit", current.name()));
            result.complete(Outcome.REFUSED);
            return;
        }
        if (!dropOverflow() && !KitGiver.fits(target.getInventory(), items)) {
            tell(reply, "no-space", Msg.unparsed("player", target.getName()));
            result.complete(Outcome.REFUSED);
            return;
        }
        // Reserve the use before any money moves; undone if anything below fails.
        AtriumUser user = ctx.users().user(target);
        KitUsage.Claim previous = user.get(store.usage).claim(current.name());
        user.update(store.usage, u -> u.claimed(current.name(), System.currentTimeMillis()));
        Runnable undo = () -> user.update(store.usage, u -> u.with(current.name(), previous));

        if (cost.signum() <= 0) {
            give(target, current, items, reply, null, null, cost, undo, result);
            return;
        }
        EconomyService eco = economy;
        UUID payerId = payer;
        CompletableFuture<TransactionResult> withdraw;
        try {
            withdraw = eco.withdraw(payerId, cost, "kit:" + current.name());
        } catch (RuntimeException e) {
            withdraw = CompletableFuture.failedFuture(e);
        }
        withdraw.whenComplete((tx, failure) -> {
            if (failure != null || tx == null || !tx.success()) {
                if (failure != null) {
                    ctx.logger().warn("Kit {} charge of {} failed", current.name(), payerId, failure);
                }
                undo.run();
                boolean poor = tx != null && tx.status() == TransactionResult.Status.INSUFFICIENT_FUNDS;
                tell(reply, poor ? "insufficient-funds" : "failed", Msg.unparsed("cost", eco.format(cost)));
                result.complete(Outcome.REFUSED);
                return;
            }
            ctx.scheduler().entity(target,
                    () -> give(target, current, items, reply, eco, payerId, tx.amount(), undo, result),
                    () -> {
                        refund(eco, payerId, tx.amount(), current.name());
                        undo.run();
                        tell(reply, "target-unavailable", Msg.unparsed("player", target.getName()));
                        result.complete(Outcome.REFUSED);
                    });
        });
    }

    private void give(Player target, Kit kit, ItemStack[] items, CommandSender reply, @Nullable EconomyService eco,
            @Nullable UUID payer, BigDecimal charged, Runnable undo, CompletableFuture<Outcome> result) {
        boolean refundNeeded = eco != null && payer != null && charged.signum() > 0;
        try {
            if (!target.isOnline() || target.isDead()) {
                if (refundNeeded) refund(eco, payer, charged, kit.name());
                undo.run();
                tell(reply, "target-unavailable", Msg.unparsed("player", target.getName()));
                result.complete(Outcome.REFUSED);
                return;
            }
            boolean drop = dropOverflow();
            if (!drop && !KitGiver.fits(target.getInventory(), items)) {
                if (refundNeeded) refund(eco, payer, charged, kit.name());
                undo.run();
                tell(reply, "no-space", Msg.unparsed("player", target.getName()));
                result.complete(Outcome.REFUSED);
                return;
            }
            // Overflow is always dropped rather than lost; with REFUSE the simulation above means there is none.
            List<ItemStack> overflow = KitGiver.give(target, items, true);
            if (!overflow.isEmpty()) {
                ctx.messages().send(target, "kits.dropped");
            }
        } catch (RuntimeException e) {
            ctx.logger().error("Giving kit {} to {} failed", kit.name(), target.getName(), e);
            if (refundNeeded) refund(eco, payer, charged, kit.name());
            undo.run();
            tell(reply, "failed");
            result.complete(Outcome.FAILED);
            return;
        }
        ctx.users().saveNow(target.getUniqueId()).whenComplete((v, failure) -> {
            if (failure != null) {
                ctx.logger().warn("Kit usage of {} could not be saved yet; it stays queued", target.getName(), failure);
            }
        });
        ctx.messages().send(target, "kits.received", Msg.unparsed("kit", kit.name()));
        if (!reply.equals(target)) {
            tell(reply, "given", Msg.unparsed("kit", kit.name()), Msg.unparsed("player", target.getName()));
        }
        if (eco != null && charged.signum() > 0 && payer != null) {
            tell(reply, "charged", Msg.unparsed("cost", eco.format(charged)));
        }
        result.complete(Outcome.GIVEN);
    }

    private void refund(EconomyService eco, UUID payer, BigDecimal amount, String kit) {
        eco.deposit(payer, amount, "kit-refund:" + kit).whenComplete((tx, failure) -> {
            if (failure != null || tx == null || !tx.success()) {
                ctx.logger().error("REFUND FAILED: {} must be refunded {} for kit {} ({})", payer, amount.toPlainString(), kit,
                        tx == null ? "exception" : tx.status(), failure);
            }
        });
    }

    /** Sends a kits message to a sender on its own thread. */
    void tell(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, "kits." + key, tags), null);
        } else {
            ctx.scheduler().global(() -> ctx.messages().send(sender, "kits." + key, tags));
        }
    }
}
