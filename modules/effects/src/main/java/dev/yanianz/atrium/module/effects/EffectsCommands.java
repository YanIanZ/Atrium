package dev.yanianz.atrium.module.effects;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /effects}, {@code /effects test} and {@code /effects reload}. */
final class EffectsCommands {
    private final EffectsModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;

    EffectsCommands(EffectsModule module) {
        this.module = module;
        this.ctx = module.ctx();
        this.cmds = module.cmds();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("effects")
                .executes(this::list)
                .then(Commands.literal("test")
                        .requires(Cmds.permission(EffectsModule.PERM_TEST))
                        .executes(c -> usage(c.getSource(), "effects.usage.test"))
                        .then(Commands.argument("event", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> EffectKeys.suggestions(module.styles())))
                                .executes(c -> test(c, false))
                                .then(Commands.argument("player", ArgumentTypes.player())
                                        .requires(Cmds.permission(EffectsModule.PERM_TEST_OTHERS))
                                        .executes(c -> test(c, true)))))
                .then(Commands.literal("reload")
                        .requires(Cmds.permission(EffectsModule.PERM_RELOAD))
                        .executes(this::reload))
                .then(new FixedEffectCommands(module).root())
                .build(), "List event effects, preview one, or manage fixed effects"));
    }

    private int list(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (!sender.hasPermission(EffectsModule.PERM_USE)) {
            throw cmds.fail(c.getSource(), "effects.no-permission", Msg.unparsed("permission", EffectsModule.PERM_USE));
        }
        List<EffectStyle> styles = new ArrayList<>(module.styles().values());
        styles.sort(Comparator.comparing(EffectStyle::key));
        ctx.messages().send(sender, "effects.list-header", Msg.number("count", styles.size()));
        if (styles.isEmpty()) {
            ctx.messages().send(sender, "effects.list-empty");
            return Cmds.OK;
        }
        for (EffectStyle style : styles) {
            String key = style.enabled() ? "effects.list-entry" : "effects.list-entry-disabled";
            ctx.messages().send(sender, key,
                    Msg.unparsed("event", style.key()),
                    Msg.unparsed("detail", detail(style)));
        }
        return Cmds.OK;
    }

    private static String detail(EffectStyle style) {
        List<String> parts = new ArrayList<>();
        if (style.particles() != null) {
            StringBuilder particle = new StringBuilder(style.particles().shape().id())
                    .append(' ').append(style.particles().particle().name());
            var data = style.particles().data();
            if (data.dust() != null) {
                particle.append(' ').append(ParticleData.formatColour(data.dust().getColor()));
            } else if (data.colour() != null) {
                particle.append(' ').append(ParticleData.formatColour(data.colour()));
            }
            parts.add(particle.toString());
        }
        if (style.sound() != null) {
            parts.add("sound");
        }
        if (style.title() != null) {
            parts.add("title");
        }
        if (style.actionBar() != null) {
            parts.add("action bar");
        }
        if (style.firework() != null) {
            parts.add("firework");
        }
        if (!style.permission().isBlank()) {
            parts.add("permission " + style.permission());
        }
        if (style.hasCooldown()) {
            parts.add("cooldown " + style.cooldown().toSeconds() + "s");
        }
        return parts.isEmpty() ? "no effect" : String.join(", ", parts);
    }

    private int test(CommandContext<CommandSourceStack> c, boolean other) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "event");
        String key = EffectKeys.normalize(raw);
        EffectStyle style = module.styles().get(key);
        if (style == null) {
            List<String> known = new ArrayList<>(module.styles().keySet());
            known.sort(String::compareTo);
            throw cmds.fail(c.getSource(), "effects.unknown-event",
                    Msg.unparsed("event", raw),
                    Msg.unparsed("events", known.isEmpty() ? String.join(", ", EffectKeys.ALL) : String.join(", ", known)));
        }
        Player target = other ? cmds.argPlayer(c, "player") : cmds.player(c.getSource());
        module.runtime().preview(style, target);
        reply(c.getSource(), "effects.preview",
                Msg.unparsed("event", style.key()),
                Msg.unparsed("player", target.getName()));
        return Cmds.OK;
    }

    private int reload(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        try {
            int warnings = module.reloadEffects();
            ctx.messages().send(c.getSource().getSender(), "effects.reloaded",
                    Msg.number("events", module.styles().size()),
                    Msg.number("fixed", module.fixed().size()),
                    Msg.number("warnings", warnings));
            return Cmds.OK;
        } catch (Exception e) {
            module.logger().error("Reloading effects.yml failed", e);
            throw cmds.fail(c.getSource(), "effects.reload-failed");
        }
    }

    private int usage(CommandSourceStack source, String key) {
        ctx.messages().send(source.getSender(), key);
        return Cmds.OK;
    }

    private void reply(CommandSourceStack source, String key, TagResolver... resolvers) {
        CommandSender sender = source.getSender();
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, resolvers), null);
        } else {
            ctx.messages().send(sender, key, resolvers);
        }
    }

    /** Shared by the fixed commands for their own async replies. */
    static void replyOn(ModuleContext ctx, CommandSender sender, String key, TagResolver... resolvers) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, resolvers), null);
        } else {
            ctx.messages().send(sender, key, resolvers);
        }
    }
}
