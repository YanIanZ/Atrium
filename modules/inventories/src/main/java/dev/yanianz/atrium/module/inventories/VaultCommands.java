package dev.yanianz.atrium.module.inventories;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

/**
 * {@code /pv}: personal vaults. The number of vaults comes from {@code atrium.pv.<n>} and the size of each
 * vault from {@code atrium.pv.size.<rows>}; both are read on every call, so a rank change applies immediately.
 */
final class VaultCommands {
    static final String PERM_USE = "atrium.pv";
    static final String VAULT_LIMIT = "atrium.pv";
    static final String SIZE_LIMIT = "atrium.pv.size";

    private final ModuleContext ctx;
    private final VaultStore store;
    private final VaultMenu menu;
    private final Supplier<InventoriesConfig> config;

    VaultCommands(ModuleContext ctx, VaultStore store, VaultMenu menu, Supplier<InventoriesConfig> config) {
        this.ctx = ctx;
        this.store = store;
        this.menu = menu;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("pv", PERM_USE)
                .executes(this::openDefault)
                .then(Commands.argument("vault", IntegerArgumentType.integer(1, VaultLayout.MAX_VAULTS))
                        .suggests(vaultNumbers())
                        .executes(this::open))
                .then(Cmds.literal("list").executes(this::list))
                .build(), "Open one of your personal vaults", "vault", "playervault", "vaults"));
    }

    /** Vaults this player may use; {@code atrium.pv.<n>} raises the configured default. */
    static int vaultCount(Player player, InventoriesConfig config) {
        return VaultLayout.vaultCount(Permissions.limit(player, VAULT_LIMIT,
                Math.max(0, config.defaultVaultCount)));
    }

    static int vaultRows(Player player, InventoriesConfig config) {
        return VaultLayout.rows(Permissions.limit(player, SIZE_LIMIT, Math.max(1, config.defaultVaultRows)));
    }

    private int openDefault(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        if (!config.get().vaultsEnabled) {
            ctx.messages().send(player, "inventories.vault-disabled");
            return Cmds.OK;
        }
        int count = vaultCount(player, config.get());
        int vault = VaultLayout.defaultVault(count);
        if (vault < 1) {
            ctx.messages().send(player, "inventories.vault-none");
            return Cmds.OK;
        }
        menu.open(player, vault);
        return Cmds.OK;
    }

    private int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        if (!config.get().vaultsEnabled) {
            ctx.messages().send(player, "inventories.vault-disabled");
            return Cmds.OK;
        }
        int vault = IntegerArgumentType.getInteger(c, "vault");
        int count = vaultCount(player, config.get());
        if (!VaultLayout.validVault(vault, count)) {
            ctx.messages().send(player, "inventories.vault-limit", Msg.number("vault", vault),
                    Msg.number("limit", count));
            return Cmds.OK;
        }
        menu.open(player, vault);
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        int count = vaultCount(player, config.get());
        if (count < 1) {
            ctx.messages().send(player, "inventories.vault-none");
            return Cmds.OK;
        }
        int rows = vaultRows(player, config.get());
        store.list(player.getUniqueId()).whenComplete((vaults, error) -> {
            Runnable report = () -> {
                if (error != null) {
                    ctx.logger().warn("Reading the vault list of {} failed", player.getUniqueId(), error);
                    ctx.messages().send(player, "inventories.failed");
                    return;
                }
                ctx.messages().send(player, "inventories.vault-list-header", Msg.number("limit", count),
                        Msg.number("rows", rows));
                if (vaults.isEmpty()) {
                    ctx.messages().send(player, "inventories.vault-list-empty");
                    return;
                }
                List<Component> entries = new ArrayList<>();
                for (int vault : vaults) {
                    entries.add(ctx.messages().render(player, "inventories.vault-list-entry",
                                    Msg.number("vault", vault))
                            .clickEvent(ClickEvent.runCommand("/pv " + vault))
                            .hoverEvent(HoverEvent.showText(ctx.messages().render(player,
                                    "inventories.vault-list-hover", Msg.number("vault", vault)))));
                }
                player.sendMessage(ctx.messages().render(player, "inventories.vault-list",
                        Msg.component("vaults",
                                Component.join(JoinConfiguration.separator(Component.space()), entries))));
            };
            try {
                ctx.scheduler().entity(player, report, null);
            } catch (RuntimeException e) {
                ctx.logger().debug("Could not report the vault list to {}", player.getName(), e);
            }
        });
        return Cmds.OK;
    }

    /** Suggests only the vault numbers the sender may actually open. */
    private com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> vaultNumbers() {
        return (c, builder) -> {
            if (c.getSource().getSender() instanceof Player player) {
                int count = Math.min(VaultLayout.MAX_VAULTS, vaultCount(player, config.get()));
                for (int i = 1; i <= count; i++) {
                    String value = Integer.toString(i);
                    if (value.startsWith(builder.getRemainingLowerCase())) {
                        builder.suggest(value);
                    }
                }
            }
            return builder.buildFuture();
        };
    }
}
