package dev.yanianz.atrium.module.inspect;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;
import org.bukkit.permissions.PermissionDefault;

/**
 * Read-only staff diagnostics: blocks, entities, items, permission nodes, command ownership, experience,
 * AFK state, recently seen players and operators. No task is ever scheduled, so an idle server does no
 * work for this module.
 */
public final class InspectModule implements AtriumModule {
    private ModuleContext ctx;
    private ConfigHandle<InspectConfig> config;
    private InspectCommands commands;

    @Override
    public String id() {
        return "inspect";
    }

    @Override
    public String description() {
        return "Read-only staff diagnostics: block, entity and item info, permissions, commands, exp and lists";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("afk", "vanish");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.config = context.config(InspectConfig.class, InspectConfig::new);
        this.commands = new InspectCommands(context, config);
        commands.reload();
        commands.register();

        context.permission(InspectCommands.PERM_BLOCKINFO, PermissionDefault.TRUE,
                "Inspect the block you look at");
        context.permission(InspectCommands.PERM_BLOCKNBT, PermissionDefault.OP,
                "Print block state and stored data");
        context.permission(InspectCommands.PERM_ENTITYINFO, PermissionDefault.TRUE,
                "Inspect the entity you look at");
        context.permission(InspectCommands.PERM_ENTITYNBT, PermissionDefault.OP, "Print entity SNBT data");
        context.permission(InspectCommands.PERM_ITEMINFO, PermissionDefault.TRUE, "Inspect your held item");
        context.permission(InspectCommands.PERM_ITEMNBT, PermissionDefault.TRUE, "Print held item components");
        context.permission(InspectCommands.PERM_CHECKPERM, PermissionDefault.OP, "Check permission nodes");
        context.permission(InspectCommands.PERM_CHECKPERM_OTHERS, PermissionDefault.OP,
                "Check permission nodes of other players");
        context.permission(InspectCommands.PERM_CHECKCOMMAND, PermissionDefault.OP,
                "Show which plugin owns a command");
        context.permission(InspectCommands.PERM_CHECKEXP, PermissionDefault.TRUE, "Show your own experience");
        context.permission(InspectCommands.PERM_CHECKEXP_OTHERS, PermissionDefault.OP,
                "Show the experience of other players");
        context.permission(InspectCommands.PERM_AFKCHECK, PermissionDefault.OP, "List AFK players");
        context.permission(InspectCommands.PERM_AFKCHECK_OTHERS, PermissionDefault.OP,
                "Check the AFK state of another player");
        context.permission(InspectCommands.PERM_LASTONLINE, PermissionDefault.OP,
                "List the most recently seen players");
        context.permission(InspectCommands.PERM_OPS, PermissionDefault.OP, "List server operators");
        context.permission(InspectCommands.PERM_CHECKACCOUNT, PermissionDefault.OP,
                "Show the accounts of a player");
        context.permission(InspectCommands.PERM_CHECKACCOUNT_OTHERS, PermissionDefault.OP,
                "Show the accounts of another player");
        context.permission(InspectCommands.PERM_CHECKACCOUNT_IP, PermissionDefault.OP,
                "See and search IP addresses");
        context.permission(InspectCommands.PERM_HASPERMISSION, PermissionDefault.TRUE,
                "Check whether you have a permission node");
        context.permission(InspectCommands.PERM_HASPERMISSION_OTHERS, PermissionDefault.OP,
                "Check permissions of other players");
        context.permission(InspectCommands.PERM_PERMREG, PermissionDefault.OP,
                "List registered permission nodes");
        context.permission(StatsCommands.PERM_STATS, PermissionDefault.TRUE,
                "Open your own statistics menu");
        context.permission(StatsCommands.PERM_STATS_OTHERS, PermissionDefault.OP,
                "Open the statistics menu of another player");
        context.permission(StatsCommands.PERM_STATSEDIT, PermissionDefault.OP,
                "Set, add or take Bukkit statistics of a player");
        context.permission(AdminCommands.PERM_UTIL, PermissionDefault.OP,
                "Dump commands, permissions or materials to a file");
        context.permission(AdminCommands.PERM_SAMEIP, PermissionDefault.OP,
                "Group online players that share an address");
        context.permission(AdminCommands.PERM_SAMEIP_IP, PermissionDefault.OP,
                "See the unmasked address in /sameip");
        context.permission(AdminCommands.PERM_REMOVEUSER, PermissionDefault.OP,
                "Delete every Atrium row of an account or clean duplicate names");
        context.permission(AdminCommands.PERM_RESETDBFIELDS, PermissionDefault.OP,
                "Reset one Atrium dataset of an offline account");
    }

    @Override
    public void reload() {
        if (commands != null) {
            commands.reload();
        }
    }

    @Override
    public void disable() {
        if (ctx != null) {
            ctx.logger().debug("Inspect module disabled");
        }
    }
}
