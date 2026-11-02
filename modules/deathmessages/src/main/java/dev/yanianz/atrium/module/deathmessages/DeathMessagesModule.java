package dev.yanianz.atrium.module.deathmessages;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;

/**
 * Replaces the vanilla death message with a configurable template chosen from the damage source, limits the
 * broadcast by world/radius and online count, suppresses repeated messages, hides vanished players and
 * stores kill/death counters.
 *
 * <p>Vanilla is suppressed with a null death message and the component is sent by this module so the radius,
 * vanish checks and per-world switch can be applied per viewer.
 */
public final class DeathMessagesModule implements AtriumModule, Listener {
    static final String PERM_STATS = "atrium.deathstats";
    static final String PERM_OTHERS = "atrium.deathstats.others";
    static final String PERM_KILLTOP = "atrium.killtop";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<DeathMessagesConfig> config;
    private DeathStore store;
    private final DamageHistory history = new DamageHistory(8);
    private final DeathSpamGuard spam = new DeathSpamGuard();

    @Override
    public String id() {
        return "deathmessages";
    }

    @Override
    public String description() {
        return "Custom death messages per cause, radius broadcast and kill statistics";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(DeathMessagesConfig.class, DeathMessagesConfig::new);
        if (config.get().killtopPageSize < 1 || config.get().killtopPageSize > 100) {
            throw new IllegalArgumentException("deathmessages.yml: killtop-page-size must be 1 through 100");
        }
        ctx.migrate(DeathStore.MIGRATIONS);
        this.store = new DeathStore(ctx.database());
        ctx.listen(this);

        ctx.permission(PERM_STATS, PermissionDefault.TRUE, "Show your own kill and death counters");
        ctx.permission(PERM_OTHERS, PermissionDefault.TRUE, "Show the kill and death counters of another player");
        ctx.permission(PERM_KILLTOP, PermissionDefault.TRUE, "Show the kill leaderboard");

        ctx.command(CommandSpec.of(Cmds.literal("deathstats", PERM_STATS)
                .executes(this::statsSelf)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::statsOther))
                .build(), "Show kill and death counters"));
        ctx.command(CommandSpec.of(Cmds.literal("killtop", PERM_KILLTOP)
                .executes(c -> killtop(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .suggests(Cmds.suggest(() -> List.of("1", "2", "3")))
                        .executes(c -> killtop(c, IntegerArgumentType.getInteger(c, "page"))))
                .build(), "Players with the most kills: /killtop [page]", "ktop"));
    }

    // ---- damage history ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        DeathMessagesConfig cfg = config.get();
        if (cfg.historyWindowSeconds <= 0 || !cfg.enabledIn(worldOf(victim))) {
            return;
        }
        Entity attacker = null;
        String block = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            attacker = byEntity.getDamager();
            if (attacker instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                attacker = shooter;
            }
        } else if (event instanceof EntityDamageByBlockEvent byBlock && byBlock.getDamager() != null) {
            block = byBlock.getDamager().getType().name().toLowerCase(Locale.ROOT);
        }
        if (attacker != null && attacker.getUniqueId().equals(victim.getUniqueId())) {
            return; // self damage explains nothing about a later death
        }
        long now = System.currentTimeMillis();
        history.record(victim.getUniqueId(), new DamageHistory.Hit(
                attacker == null ? null : attacker.getUniqueId(),
                attacker == null ? null : attacker.getName(),
                block,
                event.getCause().name().toLowerCase(Locale.ROOT),
                now), now, cfg.historyWindowMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        history.clear(id);
        spam.clearFor(id + "|");
    }

    // ---- death message ----

    // HIGHEST, not MONITOR: this handler changes the event (removes the vanilla message).
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        DeathMessagesConfig cfg = config.get();
        Player victim = event.getPlayer();
        Location place = victim.getLocation().clone();
        String world = place.getWorld() == null ? "" : place.getWorld().getName();
        long now = System.currentTimeMillis();
        Optional<DamageHistory.Hit> lastHit = cfg.historyWindowSeconds > 0
                ? history.latest(victim.getUniqueId(), now, cfg.historyWindowMillis())
                : Optional.empty();
        history.clear(victim.getUniqueId());
        if (!cfg.enabledIn(world)) {
            return;
        }
        // Suppress vanilla first: everything below is sent by this module so each viewer can be filtered.
        event.deathMessage(null);

        Component victimName = victim.displayName();
        if (cfg.sendLocationToVictim) {
            ctx.messages().send(victim, "deathmessages.location", Msg.location("death", place));
        }

        DamageSource source = event.getDamageSource();
        Player killer = killerOf(source, victim);
        Entity causing = source == null ? null : source.getCausingEntity();
        Entity direct = source == null ? null : source.getDirectEntity();
        if (killer == null && cfg.lastDamageAsKiller && lastHit.isPresent() && lastHit.get().attacker() != null) {
            Player fromHistory = Bukkit.getPlayer(lastHit.get().attacker());
            if (fromHistory != null && !fromHistory.getUniqueId().equals(victim.getUniqueId())) {
                killer = fromHistory;
            }
        }
        final Player reportedKiller = killer;
        // A player causing entity is either the killer (player template) or the victim (self damage).
        // Only mobs contribute a mob.<type> key.
        Entity mobSource = killer == null && !(causing instanceof Player) ? causing : null;
        String typeKey = source == null ? "generic" : source.getDamageType().getKey().getKey();
        String directKey = direct == null ? null : direct.getType().getKey().getKey();
        String causingKey = mobSource == null ? null : mobSource.getType().getKey().getKey();

        store.recordDeath(victim.getUniqueId(), killer == null ? null : killer.getUniqueId())
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ctx.logger().warn("Saving death counters for {} failed", victim.getUniqueId(), error);
                    }
                });

        CompletableFuture<KillerSnapshot> snapshot = killedKillerSnapshot(reportedKiller, mobSource, direct);

        boolean spamAllowed = spam.allow(victim.getUniqueId() + "|" + typeKey + "|"
                        + (reportedKiller == null ? "-" : reportedKiller.getUniqueId()),
                now, cfg.spamWindowMillis(), cfg.spamMax);
        snapshot.thenAccept(values -> {
            DeathTemplateSelector.Damage damage = new DeathTemplateSelector.Damage(
                    typeKey, directKey, causingKey, reportedKiller != null);
            List<String> candidates = DeathTemplateSelector.candidates(damage);
            String template = cfg.templateFor(candidates);
            Component lastWeapon = lastHit.isPresent() && reportedKiller != null
                    && reportedKiller.getUniqueId().equals(lastHit.get().attacker())
                    ? values.weapon() : Component.text("-");
            Component message = ctx.messages().parseTrusted(template,
                    Msg.component("victim", victimName),
                    Msg.component("killer", values.name()),
                    Msg.component("weapon", values.weapon()),
                    Msg.unparsed("lastkiller", lastHit.map(DamageHistory.Hit::attackerName).orElse("-")),
                    Msg.component("lastweapon", lastWeapon),
                    Msg.unparsed("lastblock", lastHit.map(DamageHistory.Hit::block).orElse("-")),
                    Msg.unparsed("lastcause", lastHit.map(DamageHistory.Hit::cause).orElse("-")),
                    Msg.unparsed("world", world),
                    Msg.number("x", place.getBlockX()),
                    Msg.number("y", place.getBlockY()),
                    Msg.number("z", place.getBlockZ()));
            broadcast(victim, reportedKiller, message, place, world, cfg, spamAllowed);
        }).exceptionally(error -> {
            ctx.logger().error("Building the death message of {} failed", victim.getName(), error);
            return null;
        });
    }

    private Player killerOf(DamageSource source, Player victim) {
        if (source == null) {
            return null;
        }
        Entity causing = source.getCausingEntity();
        if (causing instanceof Player player && !player.getUniqueId().equals(victim.getUniqueId())) {
            return player;
        }
        return null;
    }

    private CompletableFuture<KillerSnapshot> killedKillerSnapshot(Player killer, Entity mobSource, Entity direct) {
        if (killer == null) {
            return CompletableFuture.completedFuture(new KillerSnapshot(mobName(mobSource, direct), fists()));
        }
        return ctx.scheduler().supplyOnEntity(killer, () -> playerSnapshot(killer))
                .exceptionally(error -> {
                    ctx.logger().debug("Could not read the killer snapshot of {} at death time",
                            killer.getUniqueId(), error);
                    return new KillerSnapshot(Component.text(killer.getName()), fists());
                });
    }

    private KillerSnapshot playerSnapshot(Player killer) {
        ItemStack hand = killer.getInventory().getItemInMainHand();
        Component name = killer.displayName();
        Component weapon = hand.isEmpty()
                ? fists()
                : DeathComponents.withoutEvents(hand.displayName());
        return new KillerSnapshot(name, weapon);
    }

    private Component fists() {
        String text = config.get().fistsText;
        return ctx.messages().parseTrusted(text == null || text.isBlank() ? "<gray>fists" : text);
    }

    private Component mobName(Entity causing, Entity direct) {
        Entity entity = causing != null ? causing : direct;
        if (entity == null) {
            return Component.empty();
        }
        return Component.translatable(entity.getType().translationKey());
    }

    private void broadcast(Player victim, Player killer, Component message, Location place, String world,
            DeathMessagesConfig cfg, boolean spamAllowed) {
        // Vanilla logs every death message; keep that audit trail in the server log.
        Bukkit.getConsoleSender().sendMessage(message);
        if (!spamAllowed) {
            // A repeated message stays visible to the victim only, so the death is not silent for them.
            victim.sendMessage(message);
            return;
        }
        int online = Bukkit.getOnlinePlayers().size();
        if (cfg.hideBelowPlayers > 0 && online < cfg.hideBelowPlayers) {
            // Too few players for a broadcast; the victim still sees their own message.
            victim.sendMessage(message);
            return;
        }
        List<Player> viewers = List.copyOf(Bukkit.getOnlinePlayers());
        for (Player viewer : viewers) {
            ctx.scheduler().entity(viewer, () -> {
                boolean sameWorld = world.equals(worldOf(viewer));
                double distance = Double.MAX_VALUE;
                if (sameWorld) {
                    try {
                        distance = viewer.getLocation().distanceSquared(place);
                    } catch (IllegalArgumentException unloadedWorld) {
                        // The death world was replaced between the event and this task; treat it as out of range.
                        ctx.logger().debug("Death broadcast distance unavailable in world {}", world, unloadedWorld);
                    }
                }
                if (!DeathBroadcast.receives(cfg.broadcastRadius, sameWorld, distance)) {
                    return;
                }
                if (!canSee(viewer, victim) || (killer != null && !canSee(viewer, killer))) {
                    return;
                }
                viewer.sendMessage(message);
            }, null);
        }
    }

    private static String worldOf(Player player) {
        return player.getWorld() == null ? "" : player.getWorld().getName();
    }

    /** Vanished players stay hidden even in a death broadcast. */
    private boolean canSee(Player viewer, Player target) {
        Optional<VanishService> vanish = ctx.services().get(VanishService.class);
        return vanish.map(service -> service.canSee(viewer, target)).orElse(true);
    }

    private record KillerSnapshot(Component name, Component weapon) {}

    // ---- /killtop ----

    private int killtop(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        int size = Math.clamp(config.get().killtopPageSize, 1, 100);
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            reply(sender, "deathmessages.killtop-empty");
            return Cmds.OK;
        }
        store.topByKills(size, (int) offset).whenComplete((entries, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading the kill leaderboard failed", error);
                reply(sender, "core.error");
                return;
            }
            if (entries.isEmpty()) {
                reply(sender, "deathmessages.killtop-empty");
                return;
            }
            reply(sender, "deathmessages.killtop-header", Msg.number("page", page));
            long rank = offset;
            for (DeathStore.TopEntry entry : entries) {
                rank++;
                reply(sender, "deathmessages.killtop-entry",
                        Msg.unparsed("rank", Long.toString(rank)),
                        Msg.unparsed("player", entry.name()),
                        Msg.number("kills", entry.stats().kills()),
                        Msg.number("deaths", entry.stats().deaths()),
                        Msg.unparsed("ratio", entry.stats().ratioText()));
            }
        });
        return Cmds.OK;
    }

    // ---- /deathstats ----

    private int statsSelf(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            ctx.messages().send(sender, "core.player-only");
            return 0;
        }
        show(sender, player.getUniqueId(), player.getName());
        return Cmds.OK;
    }

    private int statsOther(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender sender = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");
        if (sender instanceof Player player && player.getName().equalsIgnoreCase(name)) {
            show(sender, player.getUniqueId(), player.getName());
            return Cmds.OK;
        }
        if (!sender.hasPermission(PERM_OTHERS)) {
            throw cmds.fail(context.getSource(), "deathmessages.no-permission",
                    Msg.unparsed("permission", PERM_OTHERS));
        }
        ctx.users().resolveAccount(name, false).whenComplete((found, error) -> {
            if (error != null) {
                ctx.logger().warn("Resolving {} for /deathstats failed", name, error);
                reply(sender, "core.error");
                return;
            }
            if (found.isEmpty()) {
                reply(sender, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            PlayerRef ref = found.get();
            show(sender, ref.uuid(), ref.name());
        });
        return Cmds.OK;
    }

    private void show(CommandSender sender, UUID uuid, String name) {
        store.stats(uuid).whenComplete((stats, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading death counters of {} failed", uuid, error);
                reply(sender, "core.error");
                return;
            }
            TagResolver player = Msg.unparsed("player", name);
            reply(sender, "deathmessages.stats-header", player);
            reply(sender, "deathmessages.stats-kills", Msg.number("kills", stats.kills()));
            reply(sender, "deathmessages.stats-deaths", Msg.number("deaths", stats.deaths()));
            reply(sender, "deathmessages.stats-ratio", Msg.unparsed("ratio", stats.ratioText()));
        });
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(recipient, key, tags);
        }
    }
}
