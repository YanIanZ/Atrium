package dev.yanianz.atrium.module.aliases;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Custom commands from config, registered as real Brigadier commands that run action lines, plus command
 * replacement rules.
 */
public final class AliasesModule implements AtriumModule, Listener {
    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<AliasesConfig> config;
    private ActionService actions;
    /** Live definitions by command name; the command tree registered at startup looks them up on every use. */
    private volatile Map<String, AliasSpec> live = Map.of();
    private volatile CommandRewriter rewriter = CommandRewriter.EMPTY;

    @Override
    public String id() {
        return "aliases";
    }

    @Override
    public String description() {
        return "Custom commands and command replacements";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("actions");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(AliasesConfig.class, AliasesConfig::new);
        this.actions = ctx.services().require(ActionService.class);
        Map<String, AliasSpec> specs = load(config.get());
        live = specs;
        for (AliasSpec spec : specs.values()) {
            ctx.permission(spec.permission(), PermissionDefault.getByName(spec.permissionDefault()),
                    "Use the custom command /" + spec.name());
            ctx.command(new CommandSpec(build(spec), spec.description(), spec.aliases()));
        }
        loadRewriter(config.get());
        ctx.listen(this);
        ctx.logger().info("Loaded {} custom commands and {} command replacements", specs.size(), rewriter.size());
    }

    @Override
    public void reload() {
        Map<String, AliasSpec> registered = live;
        Map<String, AliasSpec> fresh = load(config.get());
        Map<String, AliasSpec> next = new LinkedHashMap<>();
        boolean restartNeeded = false;
        for (AliasSpec old : registered.values()) {
            AliasSpec updated = fresh.get(old.name());
            if (updated == null) {
                restartNeeded = true;
                next.put(old.name(), old); // keep the registered command working until the restart
            } else if (!updated.structure().equals(old.structure())) {
                restartNeeded = true;
                next.put(old.name(), old);
            } else {
                next.put(old.name(), updated);
            }
        }
        for (String name : fresh.keySet()) {
            restartNeeded |= !registered.containsKey(name);
        }
        live = Map.copyOf(next);
        loadRewriter(config.get());
        if (restartNeeded) {
            ctx.logger().warn("Custom commands were added, removed or changed in name, aliases or arguments: "
                    + "restart the server to register them. Actions and texts of existing commands were reloaded.");
        }
    }

    private Map<String, AliasSpec> load(AliasesConfig cfg) {
        Map<String, AliasSpec> specs = new LinkedHashMap<>();
        Set<String> labels = new java.util.HashSet<>();
        for (AliasesConfig.AliasDefinition def : cfg.commands == null ? List.<AliasesConfig.AliasDefinition>of() : cfg.commands) {
            AliasSpec spec;
            try {
                spec = AliasSpec.from(def, actions::validate);
            } catch (IllegalArgumentException e) {
                ctx.logger().warn("Custom command '{}' skipped: {}", def.name, e.getMessage());
                continue;
            }
            if (!labels.add(spec.name()) || spec.aliases().stream().anyMatch(a -> !labels.add(a))) {
                ctx.logger().warn("Custom command '{}' skipped: its name or an alias is used twice", spec.name());
                continue;
            }
            specs.put(spec.name(), spec);
        }
        return Map.copyOf(specs);
    }

    private void loadRewriter(AliasesConfig cfg) {
        List<String> problems = new ArrayList<>();
        rewriter = CommandRewriter.of(cfg.replacements, problems);
        problems.forEach(p -> ctx.logger().warn("Command replacement skipped: {}", p));
    }

    // ---- command replacement ----

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        rewriter.rewrite(event.getMessage(), player::hasPermission).ifPresent(event::setMessage);
    }

    // ---- Brigadier ----

    private AliasSpec current(String name) {
        return live.get(name);
    }

    private com.mojang.brigadier.tree.LiteralCommandNode<CommandSourceStack> build(AliasSpec spec) {
        String name = spec.name();
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name).requires(source -> {
            AliasSpec now = current(name);
            return now != null && source.getSender().hasPermission(now.permission());
        });
        List<AliasSpec.ArgSpec> args = spec.arguments();
        int required = spec.requiredCount();
        RequiredArgumentBuilder<CommandSourceStack, ?> next = null;
        for (int i = args.size() - 1; i >= 0; i--) {
            AliasSpec.ArgSpec arg = args.get(i);
            RequiredArgumentBuilder<CommandSourceStack, ?> node = Commands.argument(arg.name(), type(arg.type()))
                    .suggests(suggestions(name, i));
            if (i + 1 >= required) {
                node.executes(c -> execute(c, name));
            }
            if (next != null) {
                node.then(next);
            }
            next = node;
        }
        if (next != null) {
            root.then(next);
        }
        root.executes(required == 0 ? c -> execute(c, name) : c -> usage(c, name));
        return root.build();
    }

    private static ArgumentType<?> type(AliasSpec.ArgType type) {
        return switch (type) {
            case WORD, PLAYER -> StringArgumentType.word();
            case INTEGER -> IntegerArgumentType.integer();
            case NUMBER -> DoubleArgumentType.doubleArg();
            case TEXT -> StringArgumentType.greedyString();
        };
    }

    private SuggestionProvider<CommandSourceStack> suggestions(String name, int index) {
        return (c, builder) -> {
            AliasSpec spec = current(name);
            if (spec == null || index >= spec.arguments().size()) {
                return builder.buildFuture();
            }
            AliasSpec.ArgSpec arg = spec.arguments().get(index);
            String typed = builder.getRemainingLowerCase();
            List<String> values = new ArrayList<>(arg.suggestions());
            if (values.isEmpty() && arg.type() == AliasSpec.ArgType.PLAYER) {
                CommandSender sender = c.getSource().getSender();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!(sender instanceof Player viewer) || viewer.canSee(online)) {
                        values.add(online.getName());
                    }
                }
            }
            for (String value : values) {
                if (value.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(value);
                }
            }
            return builder.buildFuture();
        };
    }

    private int usage(CommandContext<CommandSourceStack> c, String name) {
        AliasSpec spec = current(name);
        if (spec != null) {
            ctx.messages().send(c.getSource().getSender(), "aliases.usage",
                    Msg.component("usage", usageComponent(spec)));
        }
        return Cmds.OK;
    }

    private net.kyori.adventure.text.Component usageComponent(AliasSpec spec) {
        // Generated usage contains <argument> markers, so it is plain text; a configured usage is trusted MiniMessage.
        return spec.usage().isBlank() ? net.kyori.adventure.text.Component.text(spec.usageLine())
                : ctx.messages().parseTrusted(spec.usage());
    }

    private int execute(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        AliasSpec spec = current(name);
        CommandSourceStack source = c.getSource();
        if (spec == null) {
            throw cmds.fail(source, "aliases.unavailable");
        }
        CommandSender sender = source.getExecutor() instanceof Player executor ? executor : source.getSender();
        if (spec.playerOnly() && !(sender instanceof Player)) {
            throw cmds.fail(source, "core.player-only");
        }
        List<String> values = new ArrayList<>();
        for (AliasSpec.ArgSpec arg : spec.arguments()) {
            if (!Cmds.hasArgument(c, arg.name())) {
                break;
            }
            values.add(switch (arg.type()) {
                case INTEGER -> Integer.toString(IntegerArgumentType.getInteger(c, arg.name()));
                case NUMBER -> BigDecimal.valueOf(DoubleArgumentType.getDouble(c, arg.name())).stripTrailingZeros().toPlainString();
                default -> StringArgumentType.getString(c, arg.name());
            });
        }
        Map<String, String> vars;
        try {
            vars = ArgumentMapper.map(spec, values);
        } catch (ArgumentMapper.InvalidArgumentException e) {
            throw cmds.fail(source, "aliases.invalid-argument", Msg.unparsed("argument", e.argument),
                    Msg.component("usage", usageComponent(spec)));
        }
        actions.runAs(sender, spec.actions(), vars).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Custom command /{} failed", name, error);
            } else if (result.status() == ActionResult.Status.INVALID) {
                ctx.logger().warn("Custom command /{} has invalid actions: {}", name, result.detail());
            }
        });
        return Cmds.OK;
    }
}
