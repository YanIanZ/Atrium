package dev.yanianz.atrium.module.elytra;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** Elytra boost and launch: /elytra. */
public final class ElytraModule implements AtriumModule {
    static final String PERM_COMMAND = "atrium.elytra";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<ElytraConfig> config;
    private ElytraStore store;
    private ElytraListener listener;

    @Override
    public String id() {
        return "elytra";
    }

    @Override
    public String description() {
        return "Elytra sneak boost and double-jump launch: /elytra";
    }

    @Override
    public Set<String> softDependencies() {
        // The player module publishes FlightService; without it the launch uses its own safe fallback.
        return Set.of("player");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(ElytraConfig.class, ElytraConfig::new);
        ctx.migrate(ElytraStore.MIGRATIONS);
        this.store = new ElytraStore(ctx.database());
        ctx.component(store.component);
        this.listener = new ElytraListener(ctx, config, store);
        ctx.listen(listener);

        ctx.permission(PERM_COMMAND, PermissionDefault.TRUE, "Show and change your elytra settings (/elytra)");
        ctx.permission(ElytraListener.PERM_BOOST, PermissionDefault.TRUE, "Boost by sneaking while gliding");
        ctx.permission(ElytraListener.PERM_LAUNCH, PermissionDefault.TRUE, "Launch with a double jump while wearing an elytra");

        ctx.command(CommandSpec.of(Cmds.literal("elytra", PERM_COMMAND)
                .executes(this::status)
                .then(Cmds.literal("boost", ElytraListener.PERM_BOOST)
                        .executes(c -> toggle(c, "boost", null))
                        .then(Commands.literal("on").executes(c -> toggle(c, "boost", true)))
                        .then(Commands.literal("off").executes(c -> toggle(c, "boost", false))))
                .then(Cmds.literal("launch", ElytraListener.PERM_LAUNCH)
                        .executes(c -> toggle(c, "launch", null))
                        .then(Commands.literal("on").executes(c -> toggle(c, "launch", true)))
                        .then(Commands.literal("off").executes(c -> toggle(c, "launch", false))))
                .build(), "Show and change your elytra boost and launch settings"));
    }

    @Override
    public void disable() {
        if (listener == null) {
            return;
        }
        // Give back flight we granted; each player on their own thread.
        for (UUID id : listener.armedPlayers()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                ctx.scheduler().entity(player, () -> listener.disarm(player), null);
            }
        }
    }

    private int status(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ElytraPrefs prefs = ctx.users().user(player).get(store.component);
        ElytraConfig cfg = config.get();
        ctx.messages().send(player, "elytra.status",
                Msg.component("boost", state(player, prefs.boost(), cfg.boost.enabled, ElytraListener.PERM_BOOST)),
                Msg.component("launch", state(player, prefs.launch(), cfg.launch.enabled, ElytraListener.PERM_LAUNCH)),
                Msg.unparsed("max_speed", String.format(java.util.Locale.ROOT, "%.1f", Math.clamp(cfg.boost.maxSpeed, 0.5, 10.0) * 20.0)));
        return Cmds.OK;
    }

    private net.kyori.adventure.text.Component state(Player player, boolean on, boolean featureEnabled, String permission) {
        String key = !featureEnabled ? "elytra.state-disabled"
                : !player.hasPermission(permission) ? "elytra.state-no-permission"
                : on ? "elytra.state-on" : "elytra.state-off";
        return ctx.messages().render(player, key);
    }

    private int toggle(CommandContext<CommandSourceStack> c, String feature, Boolean wanted) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        boolean launch = feature.equals("launch");
        var user = ctx.users().user(player);
        ElytraPrefs current = user.get(store.component);
        boolean value = wanted != null ? wanted : !(launch ? current.launch() : current.boost());
        UnaryOperator<ElytraPrefs> change = p -> launch ? p.withLaunch(value) : p.withBoost(value);
        user.update(store.component, change);
        if (launch && !value) {
            listener.disarm(player); // command runs on the player's own thread
        }
        ctx.users().saveNow(player.getUniqueId()).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Saving elytra settings of {} failed", player.getName(), error);
                ctx.messages().send(player, "elytra.save-failed");
                return;
            }
            ctx.messages().send(player, value ? "elytra.enabled" : "elytra.disabled",
                    Msg.component("feature", ctx.messages().render(player, "elytra.feature-" + feature)));
        });
        return Cmds.OK;
    }
}
