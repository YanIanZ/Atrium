package dev.yanianz.atrium.module.mobs;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.jspecify.annotations.Nullable;

/**
 * /merchant: opens a trading screen with the trades of a chosen villager profession and level, without
 * leaving a villager behind in the world. The temporary villager exists for a single tick on the target's
 * region thread; only its recipes are copied out.
 *
 * <p>{@link Villager.Profession} and the custom-merchant methods are deprecated in the current API, but no
 * non-deprecated replacement exists for building a merchant that is not backed by a live villager.
 */
@SuppressWarnings({"deprecation", "removal"})
final class MerchantCommand {
    private static final int MAX_LEVEL = 5;

    private final ModuleContext ctx;
    private final Cmds cmds;

    MerchantCommand(ModuleContext ctx, Cmds cmds) {
        this.ctx = ctx;
        this.cmds = cmds;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("merchant", MobsModule.PERM_MERCHANT)
                .executes(this::usage)
                .then(Commands.argument("profession", StringArgumentType.word())
                        .suggests(Cmds.suggest(MerchantCommand::professionNames))
                        .executes(this::open)
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, MAX_LEVEL))
                                .executes(this::open)
                                .then(Commands.argument("player", ArgumentTypes.player())
                                        .executes(this::open))))
                .build(), "Open the trades of a villager profession and level", "merchantgui"));
    }

    private static Registry<Villager.Profession> professions() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.VILLAGER_PROFESSION);
    }

    private static List<String> professionNames() {
        List<String> names = new ArrayList<>();
        for (Villager.Profession profession : professions()) {
            names.add(key(profession));
        }
        return names;
    }

    private static String key(Villager.Profession profession) {
        return profession.getKey().getKey();
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "mobs.merchant-usage");
        return Cmds.OK;
    }

    private int open(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "profession");
        Villager.Profession profession = profession(raw);
        if (profession == null || key(profession).equals("none")) {
            throw cmds.fail(c.getSource(), "mobs.merchant-unknown-profession", Msg.unparsed("profession", raw));
        }
        int level = Cmds.hasArgument(c, "level") ? IntegerArgumentType.getInteger(c, "level") : 1;
        Player target = cmds.selfOrOther(c, "player", MobsModule.PERM_MERCHANT_OTHERS);
        ctx.scheduler().supplyOnEntity(target, () -> openTradeMenu(target, profession, level))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        ctx.logger().warn("Opening a merchant for {} failed", target.getName(), error);
                        reply(target, "mobs.merchant-failed");
                        return;
                    }
                    switch (result) {
                        case OPENED -> reply(target, "mobs.merchant-opened",
                                Msg.unparsed("profession", key(profession)), Msg.number("level", level));
                        case NO_TRADES -> reply(target, "mobs.merchant-no-trades");
                        case SPAWN_FAILED -> reply(target, "mobs.merchant-failed");
                    }
                });
        return Cmds.OK;
    }

    private enum Result { OPENED, NO_TRADES, SPAWN_FAILED }

    /** Target's thread: the temporary villager is created and removed in the same tick. */
    private Result openTradeMenu(Player target, Villager.Profession profession, int level) {
        List<MerchantRecipe> recipes = recipes(target, profession, level);
        if (recipes == null) {
            return Result.SPAWN_FAILED;
        }
        if (recipes.isEmpty()) {
            return Result.NO_TRADES;
        }
        Component title = ctx.messages().render(target, "mobs.merchant-title",
                Msg.unparsed("profession", key(profession)), Msg.number("level", level));
        Merchant merchant = Bukkit.createMerchant(title);
        merchant.setRecipes(recipes);
        target.openMerchant(merchant, true);
        return Result.OPENED;
    }

    /** @return the recipes, or {@code null} when the temporary villager could not be created. */
    private @Nullable List<MerchantRecipe> recipes(Player target, Villager.Profession profession, int level) {
        World world = target.getWorld();
        Location at = target.getLocation();
        Villager villager = null;
        try {
            Entity spawned = world.spawnEntity(at, EntityType.VILLAGER, CreatureSpawnEvent.SpawnReason.CUSTOM);
            if (spawned == null) {
                return null;
            }
            if (!(spawned instanceof Villager v)) {
                spawned.remove();
                return null;
            }
            villager = v;
            v.setPersistent(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setAware(false);
            v.setProfession(profession);
            // One step per level so the screen holds the trades of levels 1..N, like the command promises.
            for (int step = 1; step <= level; step++) {
                v.setVillagerLevel(step);
                v.addTrades(2);
            }
            v.setVillagerLevel(level);
            List<MerchantRecipe> recipes = List.copyOf(v.getRecipes());
            return recipes.size() > 12 ? List.copyOf(recipes.subList(0, 12)) : recipes;
        } catch (RuntimeException e) {
            ctx.logger().warn("Creating a temporary villager for {} failed", target.getName(), e);
            return null;
        } finally {
            if (villager != null) {
                villager.remove();
            }
        }
    }

    private static Villager.@Nullable Profession profession(String raw) {
        NamespacedKey key = NamespacedKey.fromString(raw.toLowerCase(Locale.ROOT));
        return key == null ? null : professions().get(key);
    }

    private void reply(Player player, String key, TagResolver... tags) {
        ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
    }
}
