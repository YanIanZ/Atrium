package dev.yanianz.atrium.module.effects;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /effects fixed create|delete|list|move|tp|edit}. */
final class FixedEffectCommands {
    private static final List<String> PROPERTIES =
            List.of("shape", "particle", "colour", "radius", "height", "points", "speed", "interval");
    private static final double DEFAULT_RADIUS = 2.0;
    private static final double DEFAULT_HEIGHT = 2.0;
    private static final int DEFAULT_POINTS = 32;
    private static final double DEFAULT_SPEED = 15.0;
    private static final int DEFAULT_INTERVAL = 4;

    private final EffectsModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final FixedEffects fixed;

    FixedEffectCommands(EffectsModule module) {
        this.module = module;
        this.ctx = module.ctx();
        this.cmds = module.cmds();
        this.fixed = module.fixed();
    }

    LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("fixed")
                .requires(Cmds.permission(EffectsModule.PERM_FIXED))
                .executes(c -> usage(c.getSource(), "effects.usage.fixed"))
                .then(create())
                .then(delete())
                .then(list())
                .then(move())
                .then(tp())
                .then(edit());
    }

    private LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("create")
                .executes(c -> usage(c.getSource(), "effects.usage.create"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> usage(c.getSource(), "effects.usage.create"))
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .suggests(Cmds.suggest(Shape::ids))
                                .executes(c -> usage(c.getSource(), "effects.usage.create"))
                                .then(Commands.argument("particle", StringArgumentType.word())
                                        .suggests(Cmds.suggest(ParticleData::supportedNames))
                                        .executes(c -> create(c, DEFAULT_RADIUS, null))
                                        .then(Commands.argument("radius",
                                                        DoubleArgumentType.doubleArg(0.1, Shapes.MAX_RADIUS))
                                                .executes(c -> create(c,
                                                        DoubleArgumentType.getDouble(c, "radius"), null))
                                                .then(Commands.argument("colour", StringArgumentType.string())
                                                        .executes(c -> create(c,
                                                                DoubleArgumentType.getDouble(c, "radius"),
                                                                StringArgumentType.getString(c, "colour"))))))));
    }

    private int create(CommandContext<CommandSourceStack> c, double radius, String colourRaw)
            throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        if (!FixedEffect.validName(name)) {
            throw cmds.fail(c.getSource(), "effects.fixed-invalid-name", Msg.unparsed("input", name));
        }
        if (fixed.find(name).isPresent()) {
            throw cmds.fail(c.getSource(), "effects.fixed-exists", Msg.unparsed("name", name));
        }
        if (fixed.size() >= Math.max(1, module.config().maxFixedEffects)) {
            throw cmds.fail(c.getSource(), "effects.fixed-limit",
                    Msg.number("max", Math.max(1, module.config().maxFixedEffects)));
        }
        Shape shape = Shape.parse(StringArgumentType.getString(c, "shape"));
        if (shape == null) {
            throw cmds.fail(c.getSource(), "effects.invalid-shape",
                    Msg.unparsed("input", StringArgumentType.getString(c, "shape")),
                    Msg.unparsed("shapes", String.join(", ", Shape.ids())));
        }
        Particle particle = requireParticle(c);
        Color colour = colour(colourRaw, c.getSource());
        requireValidData(c.getSource(), particle, colour);

        FixedEffect effect = new FixedEffect(name, StoredLocation.of(player.getLocation()), shape, particle,
                colour, radius, DEFAULT_HEIGHT, DEFAULT_POINTS, DEFAULT_SPEED, DEFAULT_INTERVAL);
        fixed.save(effect).whenComplete((ignored, error) -> {
            if (error != null) {
                module.logger().error("Could not save fixed effect '{}'", name, error);
                reply(c.getSource().getSender(), "effects.save-failed");
                return;
            }
            reply(c.getSource().getSender(), "effects.fixed-created",
                    Msg.unparsed("name", name), Msg.unparsed("detail", effect.detail()));
        });
        return Cmds.OK;
    }

    private LiteralArgumentBuilder<CommandSourceStack> delete() {
        return Commands.literal("delete")
                .executes(c -> usage(c.getSource(), "effects.usage.delete"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(this::names))
                        .executes(this::delete));
    }

    private int delete(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "name");
        FixedEffect effect = require(name, c.getSource());
        fixed.delete(effect.name()).whenComplete((deleted, error) -> {
            if (error != null) {
                module.logger().error("Could not delete fixed effect '{}'", effect.name(), error);
                reply(c.getSource().getSender(), "effects.save-failed");
                return;
            }
            if (Boolean.TRUE.equals(deleted)) {
                reply(c.getSource().getSender(), "effects.fixed-deleted", Msg.unparsed("name", effect.name()));
            } else {
                reply(c.getSource().getSender(), "effects.fixed-unknown", Msg.unparsed("name", effect.name()));
            }
        });
        return Cmds.OK;
    }

    private LiteralArgumentBuilder<CommandSourceStack> list() {
        return Commands.literal("list").executes(this::list);
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<FixedEffect> effects = new ArrayList<>(fixed.all());
        effects.sort(Comparator.comparing(FixedEffect::name, String.CASE_INSENSITIVE_ORDER));
        ctx.messages().send(sender, "effects.fixed-list-header", Msg.number("count", effects.size()));
        if (effects.isEmpty()) {
            ctx.messages().send(sender, "effects.fixed-list-empty");
            return Cmds.OK;
        }
        for (FixedEffect effect : effects) {
            StoredLocation location = effect.location();
            Component line = ctx.messages().render(sender, "effects.fixed-list-entry",
                    Msg.unparsed("name", effect.name()),
                    Msg.unparsed("detail", effect.detail()),
                    Msg.unparsed("world", location.world()),
                    Msg.number("x", location.blockX()),
                    Msg.number("y", location.blockY()),
                    Msg.number("z", location.blockZ()));
            line = line.clickEvent(ClickEvent.runCommand("/effects fixed tp " + effect.name()))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "effects.fixed-list-hover",
                            Msg.unparsed("name", effect.name()))));
            sender.sendMessage(line);
        }
        return Cmds.OK;
    }

    private LiteralArgumentBuilder<CommandSourceStack> move() {
        return Commands.literal("move")
                .executes(c -> usage(c.getSource(), "effects.usage.move"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(this::names))
                        .executes(this::move));
    }

    private int move(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        FixedEffect effect = require(StringArgumentType.getString(c, "name"), c.getSource());
        FixedEffect moved = effect.moved(StoredLocation.of(player.getLocation()));
        fixed.save(moved).whenComplete((ignored, error) -> {
            if (error != null) {
                module.logger().error("Could not move fixed effect '{}'", moved.name(), error);
                reply(c.getSource().getSender(), "effects.save-failed");
                return;
            }
            reply(c.getSource().getSender(), "effects.fixed-moved", Msg.unparsed("name", moved.name()));
        });
        return Cmds.OK;
    }

    private LiteralArgumentBuilder<CommandSourceStack> tp() {
        return Commands.literal("tp")
                .executes(c -> usage(c.getSource(), "effects.usage.tp"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(this::names))
                        .executes(this::teleport));
    }

    private int teleport(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        FixedEffect effect = require(StringArgumentType.getString(c, "name"), c.getSource());
        Optional<Location> location = effect.location().toLocation();
        if (location.isEmpty()) {
            throw cmds.fail(c.getSource(), "effects.world-missing", Msg.unparsed("name", effect.name()));
        }
        player.teleportAsync(location.get()).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success)) {
                reply(c.getSource().getSender(), "effects.fixed-teleport-failed");
                return;
            }
            reply(c.getSource().getSender(), "effects.fixed-teleported", Msg.unparsed("name", effect.name()));
        });
        return Cmds.OK;
    }

    private LiteralArgumentBuilder<CommandSourceStack> edit() {
        return Commands.literal("edit")
                .executes(c -> usage(c.getSource(), "effects.usage.edit"))
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(this::names))
                        .executes(c -> usage(c.getSource(), "effects.usage.edit"))
                        .then(Commands.argument("property", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> PROPERTIES))
                                .executes(c -> usage(c.getSource(), "effects.usage.edit"))
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(this::edit))));
    }

    private int edit(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        FixedEffect effect = require(StringArgumentType.getString(c, "name"), source);
        String property = StringArgumentType.getString(c, "property").toLowerCase(Locale.ROOT);
        String value = StringArgumentType.getString(c, "value").strip();
        FixedEffect changed = apply(source, effect, property, value);
        fixed.save(changed).whenComplete((ignored, error) -> {
            if (error != null) {
                module.logger().error("Could not edit fixed effect '{}'", changed.name(), error);
                reply(source.getSender(), "effects.save-failed");
                return;
            }
            reply(source.getSender(), "effects.fixed-edited",
                    Msg.unparsed("name", changed.name()),
                    Msg.unparsed("property", property),
                    Msg.unparsed("value", value));
        });
        return Cmds.OK;
    }

    private FixedEffect apply(CommandSourceStack source, FixedEffect effect, String property, String value)
            throws CommandSyntaxException {
        switch (property) {
            case "shape" -> {
                Shape shape = Shape.parse(value);
                if (shape == null) {
                    throw cmds.fail(source, "effects.invalid-shape",
                            Msg.unparsed("input", value),
                            Msg.unparsed("shapes", String.join(", ", Shape.ids())));
                }
                return effect.withShape(shape);
            }
            case "particle" -> {
                Particle particle = ParticleData.particle(value);
                if (particle == null) {
                    throw cmds.fail(source, "effects.invalid-particle", Msg.unparsed("input", value));
                }
                if (!ParticleData.supported(particle)) {
                    throw cmds.fail(source, "effects.particle-unsupported",
                            Msg.unparsed("particle", particle.name()),
                            Msg.unparsed("data", particle.getDataType().getSimpleName()));
                }
                requireValidData(source, particle, effect.colour());
                return effect.withParticle(particle, effect.colour());
            }
            case "colour", "color" -> {
                Color colour = value.equalsIgnoreCase("none") ? null : colour(value, source);
                if (colour == null && !ParticleData.check(effect.particle(), null, null, 1.0).valid()) {
                    throw cmds.fail(source, "effects.particle-needs-colour",
                            Msg.unparsed("particle", effect.particle().name()));
                }
                requireValidData(source, effect.particle(), colour);
                return effect.withColour(colour);
            }
            case "radius" -> effect.withRadius(boundedNumber(source, value, Shapes.MIN_RADIUS, Shapes.MAX_RADIUS));
            case "height" -> effect.withHeight(boundedNumber(source, value, 0.0, Shapes.MAX_HEIGHT));
            case "points" -> effect.withPoints(boundedInteger(source, value, Shapes.MIN_POINTS, Shapes.MAX_POINTS));
            case "speed" -> effect.withSpeed(boundedNumber(source, value, 0.0, 90.0));
            case "interval" -> effect.withInterval(boundedInteger(source, value, 1, 40));
            default -> throw cmds.fail(source, "effects.invalid-property",
                    Msg.unparsed("input", property),
                    Msg.unparsed("properties", String.join(", ", PROPERTIES)));
        }
        // Every switch arm returns or throws; this keeps the compiler happy without a nullable result.
        throw new IllegalStateException("Unhandled property " + property);
    }

    private Particle requireParticle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "particle");
        Particle particle = ParticleData.particle(raw);
        if (particle == null) {
            throw cmds.fail(c.getSource(), "effects.invalid-particle", Msg.unparsed("input", raw));
        }
        if (!ParticleData.supported(particle)) {
            throw cmds.fail(c.getSource(), "effects.particle-unsupported",
                    Msg.unparsed("particle", particle.name()),
                    Msg.unparsed("data", particle.getDataType().getSimpleName()));
        }
        return particle;
    }

    private void requireValidData(CommandSourceStack source, Particle particle, Color colour)
            throws CommandSyntaxException {
        ParticleData.Check check = ParticleData.check(particle, colour, colour, 1.0);
        if (!check.valid()) {
            throw cmds.fail(source, "effects.particle-data-required",
                    Msg.unparsed("particle", particle.name()),
                    Msg.unparsed("reason", check.error()));
        }
    }

    private Color colour(String raw, CommandSourceStack source) throws CommandSyntaxException {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Optional<Color> colour = ParticleData.colour(raw);
        if (colour.isEmpty()) {
            throw cmds.fail(source, "effects.invalid-colour", Msg.unparsed("input", raw));
        }
        return colour.get();
    }

    private double number(CommandSourceStack source, String raw) throws CommandSyntaxException {
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw cmds.fail(source, "core.invalid-number", Msg.unparsed("input", raw));
        }
    }

    private int integer(CommandSourceStack source, String raw) throws CommandSyntaxException {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw cmds.fail(source, "core.invalid-number", Msg.unparsed("input", raw));
        }
    }

    private double boundedNumber(CommandSourceStack source, String raw, double min, double max)
            throws CommandSyntaxException {
        double value = number(source, raw);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw cmds.fail(source, "effects.invalid-range",
                    Msg.unparsed("input", raw),
                    Msg.number("min", min),
                    Msg.number("max", max));
        }
        return value;
    }

    private int boundedInteger(CommandSourceStack source, String raw, int min, int max)
            throws CommandSyntaxException {
        int value = integer(source, raw);
        if (value < min || value > max) {
            throw cmds.fail(source, "effects.invalid-range",
                    Msg.unparsed("input", raw),
                    Msg.number("min", min),
                    Msg.number("max", max));
        }
        return value;
    }

    private FixedEffect require(String name, CommandSourceStack source) throws CommandSyntaxException {
        return fixed.find(name).orElseThrow(() ->
                cmds.fail(source, "effects.fixed-unknown", Msg.unparsed("name", name)));
    }

    private Collection<String> names() {
        List<String> names = new ArrayList<>();
        for (FixedEffect effect : fixed.all()) {
            names.add(effect.name());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private int usage(CommandSourceStack source, String key) {
        ctx.messages().send(source.getSender(), key);
        return Cmds.OK;
    }

    private void reply(CommandSender sender, String key, TagResolver... resolvers) {
        EffectsCommands.replyOn(ctx, sender, key, resolvers);
    }
}
