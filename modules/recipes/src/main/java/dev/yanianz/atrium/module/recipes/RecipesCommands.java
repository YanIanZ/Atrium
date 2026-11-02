package dev.yanianz.atrium.module.recipes;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** {@code /customrecipe list|reload|give <id> [player]}. */
final class RecipesCommands {
    private final RecipesModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;

    RecipesCommands(RecipesModule module) {
        this.module = module;
        this.ctx = module.context();
        this.cmds = ctx.commands();
    }

    void registerRoot() {
        ctx.command(CommandSpec.of(Cmds.literal("customrecipe", RecipesModule.PERM_RECIPE)
                .executes(this::usage)
                .then(Cmds.literal("list", RecipesModule.PERM_LIST).executes(this::list))
                .then(Cmds.literal("reload", RecipesModule.PERM_RELOAD).executes(this::reload))
                .then(Cmds.literal("give", RecipesModule.PERM_GIVE)
                        .executes(this::usage)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> module.registrar().active().keySet()))
                                .executes(c -> give(c, null))
                                .then(Commands.argument("player", ArgumentTypes.player())
                                        .executes(c -> give(c, cmds.argPlayer(c, "player"))))))
                .build(), "List, reload or give custom recipes"));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "recipes.usage");
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        var active = module.registrar().active();
        if (active.isEmpty()) {
            ctx.messages().send(sender, "recipes.list-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "recipes.list-header", Msg.number("count", active.size()));
        active.keySet().stream().sorted().forEach(id -> {
            RecipeSpec spec = active.get(id);
            ctx.messages().send(sender, "recipes.list-entry",
                    Msg.unparsed("id", id),
                    Msg.unparsed("type", spec.kind().configName()),
                    Msg.unparsed("material", spec.result().material().name()),
                    Msg.number("amount", spec.result().amount()));
        });
        return Cmds.OK;
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        module.reloadAsync().whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Reloading the custom recipes failed; the previous recipes stay active", error);
                reply(sender, "recipes.reload-failed");
                return;
            }
            reply(sender, "recipes.reloaded",
                    Msg.number("count", result.parsed()),
                    Msg.number("registered", result.registered()),
                    Msg.number("problems", result.problems()));
        });
        return Cmds.OK;
    }

    private int give(CommandContext<CommandSourceStack> c, @Nullable Player explicitTarget)
            throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String raw = StringArgumentType.getString(c, "id");
        String id = RecipeId.normalize(raw);
        RecipeSpec spec = module.registrar().spec(id);
        if (spec == null) {
            throw cmds.fail(c.getSource(), "recipes.unknown", Msg.unparsed("id", raw));
        }
        Player target = explicitTarget == null ? cmds.player(c.getSource()) : explicitTarget;
        if (!target.equals(sender) && !sender.hasPermission(RecipesModule.PERM_GIVE_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others",
                    Msg.unparsed("permission", RecipesModule.PERM_GIVE_OTHERS));
        }
        ctx.scheduler().entity(target, () -> giveNow(sender, target, id, spec),
                () -> reply(sender, "recipes.give-target-left", Msg.unparsed("id", id)));
        return Cmds.OK;
    }

    /** Runs on the target's thread; both inventories and drops are owned by that thread. */
    private void giveNow(CommandSender sender, Player target, String id, RecipeSpec spec) {
        ItemStack item;
        try {
            item = module.registrar().result(spec);
        } catch (RecipeBuildException e) {
            ctx.logger().error("Building the result of recipe {} failed", id, e);
            ctx.messages().send(target, "recipes.give-failed");
            if (!target.equals(sender)) {
                reply(sender, "recipes.give-failed");
            }
            return;
        }
        int amount = item.getAmount();
        Material material = item.getType();
        // addItem mutates the argument on a partial merge; pass a copy so the message keeps the real amount.
        var leftover = target.getInventory().addItem(item.clone());
        for (ItemStack stack : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), stack);
        }
        ctx.messages().send(target, "recipes.given",
                Msg.number("amount", amount), Msg.unparsed("material", material.name()),
                Msg.unparsed("id", id));
        if (!leftover.isEmpty()) {
            ctx.messages().send(target, "recipes.given-dropped");
        }
        if (!target.equals(sender)) {
            reply(sender, "recipes.given-other",
                    Msg.unparsed("player", target.getName()),
                    Msg.number("amount", amount), Msg.unparsed("material", material.name()),
                    Msg.unparsed("id", id));
        }
    }

    /** Replies on the recipient's own thread so the message uses their locale. */
    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline()) {
                    ctx.messages().send(player, key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(recipient, key, tags);
        }
    }
}
