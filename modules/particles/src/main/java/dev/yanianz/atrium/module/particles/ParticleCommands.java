package dev.yanianz.atrium.module.particles;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.message.Theme;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.particles.shape.Style;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** All /particles commands. */
final class ParticleCommands {
    private final ModuleContext ctx;
    private final ParticlesModule module;
    private final ConfigHandle<ParticlesConfig> config;
    private final ParticleMenu menu;
    private final Cmds cmds;

    ParticleCommands(ModuleContext ctx, ParticlesModule module, ConfigHandle<ParticlesConfig> config,
            ParticleMenu menu) {
        this.ctx = ctx;
        this.module = module;
        this.config = config;
        this.menu = menu;
        this.cmds = ctx.commands();
    }

    void register() {
        var root = Cmds.literal("particles", ParticlesModule.PERM_BASE).executes(this::openMenu);
        root.then(Cmds.literal("off").executes(this::off));
        root.then(Cmds.literal("list").executes(this::list));
        root.then(Cmds.literal("hideothers", ParticlesModule.PERM_HIDE_OTHERS).executes(this::hideOthers));
        root.then(Cmds.literal("toggle").executes(this::toggleHidden));

        var preset = Cmds.literal("preset", ParticlesModule.PERM_PRESET)
                .executes(this::presetUsage)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(presetSuggestions())
                        .executes(this::preset));
        root.then(preset);

        var admin = Cmds.literal("admin", ParticlesModule.PERM_ADMIN);
        admin.then(Cmds.literal("set").executes(this::adminSetUsage)
                .then(Commands.argument("player", StringArgumentType.word()).suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::adminSetUsage)
                        .then(Commands.argument("style", StringArgumentType.word()).suggests(styleSuggestions())
                                .executes(this::adminSet)
                                .then(Commands.argument("particle", StringArgumentType.word())
                                        .suggests(particleSuggestions())
                                        .executes(this::adminSet)
                                        .then(Commands.argument("colour", StringArgumentType.word())
                                                .suggests(colourSuggestions())
                                                .executes(this::adminSet))))));
        admin.then(Cmds.literal("clear").executes(this::adminClearUsage)
                .then(Commands.argument("player", StringArgumentType.word()).suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::adminClear)));
        admin.then(Cmds.literal("reload").executes(this::adminReload));
        root.then(admin);

        for (Style style : Style.values()) {
            root.then(styleNode(style));
        }

        ctx.command(CommandSpec.of(root.build(),
                "Particle cosmetics: open the menu or use /particles <style> [particle] [colour]"));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> styleNode(Style style) {
        var node = Cmds.literal(style.key(), style.permission()).executes(c -> apply(c, style));
        node.then(Commands.argument("particle", StringArgumentType.word())
                .suggests(particleSuggestions())
                .executes(c -> apply(c, style))
                .then(Commands.argument("colour", StringArgumentType.word())
                        .suggests(colourSuggestions())
                        .executes(c -> apply(c, style))));
        return node;
    }

    // ---- player commands ----

    private int openMenu(CommandContext<CommandSourceStack> c) {
        if (!(c.getSource().getSender() instanceof Player player)) {
            send(c.getSource().getSender(), "particles.usage");
            return Cmds.OK;
        }
        menu.openRoot(player);
        return Cmds.OK;
    }

    private int off(CommandContext<CommandSourceStack> c) {
        Player player = playerOrNull(c.getSource().getSender());
        if (player != null) {
            module.clearSelf(player);
        }
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        Player player = playerOrNull(c.getSource().getSender());
        if (player != null) {
            module.listSelf(player);
        }
        return Cmds.OK;
    }

    private int hideOthers(CommandContext<CommandSourceStack> c) {
        Player player = playerOrNull(c.getSource().getSender());
        if (player != null) {
            module.toggleHideOthers(player);
        }
        return Cmds.OK;
    }

    private int toggleHidden(CommandContext<CommandSourceStack> c) {
        Player player = playerOrNull(c.getSource().getSender());
        if (player != null) {
            module.toggleHidden(player);
        }
        return Cmds.OK;
    }

    private int presetUsage(CommandContext<CommandSourceStack> c) {
        send(c.getSource().getSender(), "particles.usage-preset");
        return Cmds.OK;
    }

    private int preset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        module.applyPreset(player, name);
        return Cmds.OK;
    }

    private int apply(CommandContext<CommandSourceStack> c, Style style) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Particle particle = particleArgument(c, style);
        ParticleColours colours = colourArgument(c, style);
        module.applySelf(player, module.effectFor(style, particle, colours));
        return Cmds.OK;
    }

    // ---- admin ----

    private int adminSetUsage(CommandContext<CommandSourceStack> c) {
        send(c.getSource().getSender(), "particles.usage-admin-set");
        return Cmds.OK;
    }

    private int adminClearUsage(CommandContext<CommandSourceStack> c) {
        send(c.getSource().getSender(), "particles.usage-admin-clear");
        return Cmds.OK;
    }

    private int adminSet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String styleName = StringArgumentType.getString(c, "style");
        Style style = Style.byName(styleName);
        if (style == null) {
            throw cmds.fail(c.getSource(), "particles.unknown-style", Msg.unparsed("style", styleName));
        }
        Particle particle = particleArgument(c, style);
        ParticleColours colours = colourArgument(c, style);
        ParticleEffect effect = module.effectFor(style, particle, colours);
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            runOnPlayer(online, () -> {
                module.applyAdmin(online, effect);
                send(sender, "particles.admin-set",
                        Msg.unparsed("player", online.getName()),
                        Msg.unparsed("style", style.key()),
                        Msg.unparsed("particle", ParticleNames.key(particle)));
            }, () -> sendLater(sender, "particles.target-left", Msg.unparsed("player", name)));
            return Cmds.OK;
        }
        resolveOffline(name, sender, uuid -> module.applyAdminOffline(uuid, effect)
                .thenAccept(ok -> sendLater(sender, ok ? "particles.admin-set" : "core.user-not-found",
                        Msg.unparsed("player", name),
                        Msg.unparsed("name", name),
                        Msg.unparsed("style", style.key()),
                        Msg.unparsed("particle", ParticleNames.key(particle))))
                .exceptionally(t -> {
                    ctx.logger().warn("Setting particles of '{}' failed", name, t);
                    sendLater(sender, "particles.admin-failed");
                    return null;
                }));
        return Cmds.OK;
    }

    private int adminClear(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            runOnPlayer(online, () -> {
                module.clearAdmin(online);
                send(sender, "particles.admin-cleared", Msg.unparsed("player", online.getName()));
            }, () -> sendLater(sender, "particles.target-left", Msg.unparsed("player", name)));
            return Cmds.OK;
        }
        resolveOffline(name, sender, uuid -> module.clearAdminOffline(uuid)
                .thenAccept(ok -> sendLater(sender, ok ? "particles.admin-cleared" : "core.user-not-found",
                        Msg.unparsed("player", name), Msg.unparsed("name", name)))
                .exceptionally(t -> {
                    ctx.logger().warn("Clearing particles of '{}' failed", name, t);
                    sendLater(sender, "particles.admin-failed");
                    return null;
                }));
        return Cmds.OK;
    }

    private int adminReload(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        try {
            config.reload();
            module.reload();
            send(c.getSource().getSender(), "particles.admin-reloaded");
        } catch (Exception e) {
            ctx.logger().warn("Reloading the particles module failed", e);
            throw cmds.fail(c.getSource(), "particles.admin-reload-failed");
        }
        return Cmds.OK;
    }

    private void resolveOffline(String name, CommandSender sender,
            java.util.function.Consumer<UUID> action) {
        ctx.users().resolveAccount(name, true).thenCompose(found -> {
            if (found.isEmpty()) {
                sendLater(sender, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.completedFuture(null);
            }
            action.accept(found.get().uuid());
            return CompletableFuture.completedFuture(null);
        }).exceptionally(t -> {
            ctx.logger().warn("Resolving particle target '{}' failed", name, t);
            sendLater(sender, "particles.admin-failed");
            return null;
        });
    }

    private void runOnPlayer(Player target, Runnable action, Runnable retired) {
        if (Bukkit.isOwnedByCurrentRegion(target)) {
            action.run();
            return;
        }
        try {
            ctx.scheduler().entity(target, action, retired);
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling a particle command for {} failed", target.getName(), e);
            retired.run();
        }
    }

    // ---- arguments ----

    private Particle particleArgument(CommandContext<CommandSourceStack> c, Style style)
            throws CommandSyntaxException {
        if (!Cmds.hasArgument(c, "particle")) {
            return module.defaultParticle(style);
        }
        String raw = StringArgumentType.getString(c, "particle");
        Particle particle = ParticleNames.resolve(raw);
        if (particle == null) {
            throw cmds.fail(c.getSource(), "particles.unknown-particle", Msg.unparsed("particle", raw));
        }
        if (!ParticleData.supported(particle)) {
            throw cmds.fail(c.getSource(), "particles.unsupported-particle", Msg.unparsed("particle", raw));
        }
        return particle;
    }

    private ParticleColours colourArgument(CommandContext<CommandSourceStack> c, Style style)
            throws CommandSyntaxException {
        if (!Cmds.hasArgument(c, "colour")) {
            return module.colours(style, null);
        }
        String raw = StringArgumentType.getString(c, "colour");
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (ParticleColours.Mode.byName(cleaned) == null && Theme.parseColor(raw) == null) {
            throw cmds.fail(c.getSource(), "particles.unknown-colour", Msg.unparsed("colour", raw));
        }
        return module.colours(style, raw);
    }

    private SuggestionProvider<CommandSourceStack> particleSuggestions() {
        return (c, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            CommandSender sender = c.getSource().getSender();
            for (Particle particle : Particle.values()) {
                String key = ParticleNames.key(particle);
                if (ParticleData.supported(particle) && key.startsWith(remaining)
                        && sender.hasPermission(ParticleNames.permission(particle))) {
                    builder.suggest(key);
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> colourSuggestions() {
        return (c, builder) -> {
            String remaining = builder.getRemainingLowerCase();
            for (String colour : colourNames()) {
                if (colour.startsWith(remaining)) {
                    builder.suggest(colour);
                }
            }
            return builder.buildFuture();
        };
    }

    private List<String> colourNames() {
        List<String> names = new ArrayList<>(config.get().fixedColours);
        names.add("rainbow");
        names.add("gradient");
        return names;
    }

    private static SuggestionProvider<CommandSourceStack> styleSuggestions() {
        return Cmds.suggest(() -> java.util.Arrays.stream(Style.values()).map(Style::key).toList());
    }

    private SuggestionProvider<CommandSourceStack> presetSuggestions() {
        return Cmds.suggest(() -> {
            if (config.get().presets == null) {
                return List.of();
            }
            return config.get().presets.keySet().stream().filter(java.util.Objects::nonNull).toList();
        });
    }

    // ---- helpers ----

    private Player playerOrNull(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        send(sender, "core.player-only");
        return null;
    }

    private void send(CommandSender sender, String key, TagResolver... tags) {
        ctx.messages().send(sender, key, tags);
    }

    private void sendLater(CommandSender sender, String key, TagResolver... tags) {
        ctx.scheduler().global(() -> {
            if (sender instanceof Player player && !player.isOnline()) {
                return;
            }
            send(sender, key, tags);
        });
    }
}
