package dev.yanianz.atrium.module.discord;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;
import org.bukkit.permissions.PermissionDefault;

/**
 * Built-in Discord webhook relay. No DiscordSRV dependency: HTTP is sent by a bounded per-webhook queue on the
 * core I/O executor, so a slow Discord endpoint can never block a region thread.
 *
 * <p>Vanish and moderation are soft dependencies: they are enabled first when present so the relay sees their
 * state, but they are not required.
 */
public final class DiscordModule implements AtriumModule {
    private ModuleContext ctx;
    private DiscordRelay relay;

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public String description() {
        return "Built-in Discord webhook relay for chat, staff chat, joins, deaths and punishments";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("chat", "moderation", "vanish");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        ConfigHandle<DiscordConfig> config = ctx.config(DiscordConfig.class, DiscordConfig::new);
        this.relay = new DiscordRelay(ctx, config);
        relay.start();
        ctx.listen(new DiscordListener(ctx, relay, config));
        new DiscordCommands(ctx, relay).register();

        ctx.permission("atrium.discord.status", PermissionDefault.OP, "Show Discord relay queues and errors");
        ctx.permission("atrium.discord.test", PermissionDefault.OP, "Send a test message through a Discord webhook");
    }

    @Override
    public void reload() {
        if (relay != null) {
            relay.rebuild();
        }
    }

    @Override
    public void disable() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
        ctx = null;
    }
}
