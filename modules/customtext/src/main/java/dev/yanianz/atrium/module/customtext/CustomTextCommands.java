package dev.yanianz.atrium.module.customtext;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.api.serverlist.ServerListService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * {@code /ctext <name> [page] [player]}, {@code /ctext list}, {@code /ctext reload}, plus one root command per
 * configured alias. The alias nodes read the live text map on every use; a reload therefore updates their
 * content and permission immediately.
 */
final class CustomTextCommands {
    private final CustomTextModule module;
    private final ModuleContext ctx;
    private final Cmds cmds;

    CustomTextCommands(CustomTextModule module) {
        this.module = module;
        this.ctx = module.context();
        this.cmds = module.cmds();
    }

    void registerRoot() {
        ctx.command(CommandSpec.of(Cmds.literal("ctext")
                .requires(source -> source.getSender().hasPermission(CustomTextModule.PERM_USE)
                        || source.getSender().hasPermission(CustomTextModule.PERM_LIST)
                        || source.getSender().hasPermission(CustomTextModule.PERM_RELOAD))
                .executes(this::usage)
                .then(Cmds.literal("list", CustomTextModule.PERM_LIST).executes(this::list))
                .then(Cmds.literal("reload", CustomTextModule.PERM_RELOAD).executes(this::reload))
                .then(Commands.argument("name", StringArgumentType.word())
                        .requires(source -> mayUseTexts(source.getSender()))
                        .suggests(names())
                        .executes(c -> show(c, name(c), 1, false, null))
                        .then(Commands.argument("page", IntegerArgumentType.integer())
                                .executes(c -> show(c, name(c), page(c), true, null))
                                .then(Commands.argument("player", ArgumentTypes.player())
                                        .executes(c -> show(c, name(c), page(c), true,
                                                cmds.argPlayer(c, "player"))))))
                .build(), "Show, list or reload custom text pages"));
    }

    /** In-game editor: /editctext &lt;name&gt; page|line ... and /editctext &lt;name&gt; preview. */
    void registerEdit() {
        ctx.command(CommandSpec.of(Cmds.literal("editctext", CustomTextModule.PERM_EDIT)
                .executes(this::editUsage)
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(adminNames())
                        .executes(c -> preview(c, 1))
                        .then(Cmds.literal("preview")
                                .executes(c -> preview(c, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .suggests(pageNumbers(false))
                                        .executes(c -> preview(c, page(c)))))
                        .then(Cmds.literal("page")
                                .then(Cmds.literal("add")
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .suggests(pageNumbers(true))
                                                .executes(this::pageAdd)))
                                .then(Cmds.literal("remove")
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .suggests(pageNumbers(false))
                                                .executes(this::pageRemove))))
                        .then(Cmds.literal("line")
                                .then(Cmds.literal("add")
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .suggests(pageNumbers(false))
                                                .then(Commands.argument("line", IntegerArgumentType.integer(1))
                                                        .suggests(lineNumbers(true))
                                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                                .executes(this::lineAdd)))))
                                .then(Cmds.literal("set")
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .suggests(pageNumbers(false))
                                                .then(Commands.argument("line", IntegerArgumentType.integer(1))
                                                        .suggests(lineNumbers(false))
                                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                                .executes(this::lineSet)))))
                                .then(Cmds.literal("remove")
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .suggests(pageNumbers(false))
                                                .then(Commands.argument("line", IntegerArgumentType.integer(1))
                                                        .suggests(lineNumbers(false))
                                                        .executes(this::lineRemove))))))
                .build(), "Edit a custom text page in game", "ctextedit"));
    }

    private int editUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "customtext.edit-usage");
        return Cmds.OK;
    }

    /** {@code /setmotd}: the MOTD is owned by the performance module, so this only talks to its service. */
    void registerMotd() {
        ctx.command(CommandSpec.of(Cmds.literal("setmotd", CustomTextModule.PERM_SETMOTD)
                .executes(this::motdShow)
                .then(Cmds.literal("off").executes(this::motdOff))
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(this::motdSet))
                .build(), "Change the server-list MOTD (two lines, MiniMessage)", "motdset"));
    }

    private int motdShow(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ServerListService service = ctx.services().get(ServerListService.class).orElse(null);
        if (service == null) {
            ctx.messages().send(sender, "customtext.setmotd-unavailable");
            return Cmds.OK;
        }
        if (!service.motdEnabled() || service.motdLines().isEmpty()) {
            ctx.messages().send(sender, "customtext.setmotd-none");
            ctx.messages().send(sender, "customtext.setmotd-usage");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "customtext.setmotd-current");
        int line = 1;
        int online = org.bukkit.Bukkit.getOnlinePlayers().size();
        int max = org.bukkit.Bukkit.getMaxPlayers();
        for (String raw : service.motdLines()) {
            sender.sendMessage(ctx.messages().render(sender, "customtext.setmotd-line",
                    Msg.number("line", line++), Msg.component("text", ctx.messages().parseTrusted(raw,
                            Msg.number("online", online), Msg.number("max", max)))));
        }
        ctx.messages().send(sender, "customtext.setmotd-usage");
        return Cmds.OK;
    }

    private int motdSet(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ServerListService service = ctx.services().get(ServerListService.class).orElse(null);
        if (service == null) {
            ctx.messages().send(sender, "customtext.setmotd-unavailable");
            return Cmds.OK;
        }
        List<String> lines = MotdInput.parse(StringArgumentType.getString(c, "text"));
        if (lines.isEmpty()) {
            ctx.messages().send(sender, "customtext.setmotd-empty");
            return Cmds.OK;
        }
        service.setMotd(lines).whenComplete((ignored, error) -> module.reply(sender,
                error == null ? "customtext.setmotd-saved" : "customtext.setmotd-failed",
                Msg.number("lines", lines.size()), Msg.unparsed("motd", String.join(" / ", lines))));
        return Cmds.OK;
    }

    private int motdOff(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ServerListService service = ctx.services().get(ServerListService.class).orElse(null);
        if (service == null) {
            ctx.messages().send(sender, "customtext.setmotd-unavailable");
            return Cmds.OK;
        }
        service.setMotdEnabled(false).whenComplete((ignored, error) -> module.reply(sender,
                error == null ? "customtext.setmotd-off" : "customtext.setmotd-failed"));
        return Cmds.OK;
    }

    private int preview(CommandContext<CommandSourceStack> c, int page) throws CommandSyntaxException {
        TextDefinition text = require(c);
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        Player viewer = sender instanceof Player player ? player : null;
        module.display(sender, viewer, text, page, true);
        return Cmds.OK;
    }

    private int pageAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        TextDefinition text = require(c);
        int page = page(c);
        return save(c, text.name(), pages -> TextEditModel.addPage(pages, page), "customtext.edit-page-added",
                pages -> new TagResolver[]{Msg.number("page", page), Msg.number("pages", pages.size())});
    }

    private int pageRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        TextDefinition text = require(c);
        int page = page(c);
        return save(c, text.name(), pages -> TextEditModel.removePage(pages, page), "customtext.edit-page-removed",
                pages -> new TagResolver[]{Msg.number("page", page), Msg.number("pages", pages.size())});
    }

    private int lineAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return line(c, true);
    }

    private int lineSet(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return line(c, false);
    }

    private int lineRemove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        TextDefinition text = require(c);
        int page = page(c);
        int line = line(c);
        return save(c, text.name(), pages -> TextEditModel.removeLine(pages, page, line),
                "customtext.edit-line-removed",
                pages -> new TagResolver[]{Msg.number("page", page), Msg.number("line", line)});
    }

    private int line(CommandContext<CommandSourceStack> c, boolean add) throws CommandSyntaxException {
        TextDefinition text = require(c);
        int page = page(c);
        int line = line(c);
        String value = StringArgumentType.getString(c, "text");
        return save(c, text.name(),
                pages -> add
                        ? TextEditModel.addLine(pages, page, line, value)
                        : TextEditModel.setLine(pages, page, line, value),
                add ? "customtext.edit-line-added" : "customtext.edit-line-set",
                pages -> new TagResolver[]{Msg.number("page", page), Msg.number("line", line),
                        Msg.unparsed("text", value)});
    }

    /**
     * Runs an edit through {@link CustomTextModule#applyPages}: the file is re-read inside the module lock,
     * so the edit always builds on the current content. Validation failures arrive as a completed
     * exceptionally future and are reported with the bounds of the freshly read file.
     */
    private int save(CommandContext<CommandSourceStack> c, String name, UnaryOperator<List<List<String>>> edit,
            String key, Function<List<List<String>>, TagResolver[]> tags) {
        CommandSender sender = c.getSource().getSender();
        module.applyPages(name, edit).whenComplete((applied, error) -> {
            if (error != null) {
                reportEditFailure(sender, name, error);
                return;
            }
            if (!applied.texts().texts().containsKey(name)) {
                ctx.logger().warn("The edited custom text {} did not load after saving", name);
                module.reply(sender, "customtext.edit-reload-failed");
                return;
            }
            List<TagResolver> all = new java.util.ArrayList<>();
            all.add(Msg.unparsed("name", name));
            all.addAll(List.of(tags.apply(applied.pages())));
            module.reply(sender, key, all.toArray(TagResolver[]::new));
        });
        return Cmds.OK;
    }

    /** Reports a refused edit with its message, or logs and reports a failed write. Never silent. */
    private void reportEditFailure(CommandSender sender, String name, Throwable error) {
        Throwable cause = error;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TextEditModel.TextEditException rejected) {
            module.reply(sender, editKey(rejected.reason()), Msg.number("max", Math.max(1, rejected.max())));
            return;
        }
        ctx.logger().error("Saving the custom text {} failed", name, cause);
        module.reply(sender, "customtext.edit-save-failed");
    }

    private static String editKey(String reason) {
        return switch (reason) {
            case "last-page" -> "customtext.edit-last-page";
            case "last-line" -> "customtext.edit-last-line";
            case "line-out-of-range" -> "customtext.edit-line-range";
            default -> "customtext.edit-page-range";
        };
    }

    private TextDefinition require(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = name(c);
        TextDefinition text = module.text(raw);
        if (text == null) {
            throw cmds.fail(c.getSource(), "customtext.unknown", Msg.unparsed("name", raw));
        }
        return text;
    }

    private static int lineCount(TextDefinition text, int page) {
        return page < 1 || page > text.pageCount() ? 0 : text.pages().get(page - 1).size();
    }

    private SuggestionProvider<CommandSourceStack> adminNames() {
        return (c, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (TextDefinition text : module.texts()) {
                if (text.name().startsWith(typed)) {
                    builder.suggest(text.name());
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> pageNumbers(boolean allowEnd) {
        return (c, builder) -> {
            TextDefinition text = module.text(name(c));
            if (text == null) {
                return builder.buildFuture();
            }
            int max = text.pageCount() + (allowEnd ? 1 : 0);
            String typed = builder.getRemainingLowerCase();
            for (int i = 1; i <= max; i++) {
                String value = Integer.toString(i);
                if (value.startsWith(typed)) {
                    builder.suggest(i);
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> lineNumbers(boolean allowEnd) {
        return (c, builder) -> {
            TextDefinition text = module.text(name(c));
            if (text == null) {
                return builder.buildFuture();
            }
            int selected;
            try {
                selected = IntegerArgumentType.getInteger(c, "page");
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            int max = lineCount(text, selected) + (allowEnd ? 1 : 0);
            String typed = builder.getRemainingLowerCase();
            for (int i = 1; i <= max; i++) {
                String value = Integer.toString(i);
                if (value.startsWith(typed)) {
                    builder.suggest(i);
                }
            }
            return builder.buildFuture();
        };
    }

    private static int line(CommandContext<CommandSourceStack> c) {
        return IntegerArgumentType.getInteger(c, "line");
    }

    LiteralCommandNode<CommandSourceStack> aliasNode(String alias) {
        return Commands.literal(alias)
                .requires(source -> {
                    TextDefinition text = module.textForAlias(alias);
                    return text != null && text.maySee(source.getSender()::hasPermission);
                })
                .executes(c -> showAlias(c, alias, 1, false, null))
                .then(Commands.argument("page", IntegerArgumentType.integer())
                        .executes(c -> showAlias(c, alias, page(c), true, null))
                        .then(Commands.argument("player", ArgumentTypes.player())
                                .executes(c -> showAlias(c, alias, page(c), true, cmds.argPlayer(c, "player")))))
                .build();
    }

    private int showAlias(CommandContext<CommandSourceStack> c, String alias, int page, boolean explicitPage,
            @Nullable Player target) throws CommandSyntaxException {
        TextDefinition text = module.textForAlias(alias);
        if (text == null) {
            throw cmds.fail(c.getSource(), "customtext.unknown", Msg.unparsed("name", alias));
        }
        return show(c, text.name(), page, explicitPage, target);
    }

    private int show(CommandContext<CommandSourceStack> c, String rawName, int page, boolean explicitPage,
            @Nullable Player target) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String name = TextName.normalize(rawName);
        TextDefinition text = module.text(name);
        if (text == null) {
            throw cmds.fail(source, "customtext.unknown", Msg.unparsed("name", rawName));
        }
        if (target != null && !target.equals(sender) && !sender.hasPermission(CustomTextModule.PERM_OTHERS)) {
            throw cmds.fail(source, "core.no-permission-others",
                    Msg.unparsed("permission", CustomTextModule.PERM_OTHERS));
        }
        Player viewer = target != null ? target : (sender instanceof Player player ? player : null);
        if (viewer != null && !text.maySee(viewer::hasPermission)) {
            throw cmds.fail(source, "customtext.no-permission", Msg.unparsed("name", name));
        }
        // A cooldown only guards a plain open; clicking prev/next always works and cannot brick navigation.
        if (target == null && viewer != null && !explicitPage) {
            Duration left = module.acquireCooldown(text, viewer);
            if (left != null) {
                ctx.messages().send(viewer, "customtext.cooldown",
                        Msg.unparsed("name", name), Msg.duration("time", left));
                return Cmds.OK;
            }
        }
        module.display(sender, viewer, text, page, true);
        if (viewer != null && !viewer.equals(sender)) {
            module.reply(sender, "customtext.shown-other",
                    Msg.unparsed("name", name), Msg.unparsed("player", viewer.getName()));
        }
        return Cmds.OK;
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "customtext.usage");
        return Cmds.OK;
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<TextDefinition> all = module.allTexts();
        if (all.isEmpty()) {
            ctx.messages().send(sender, "customtext.list-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "customtext.list-header", Msg.number("count", all.size()));
        for (TextDefinition text : all) {
            Component line = ctx.messages().render(sender, "customtext.list-entry",
                    Msg.unparsed("name", text.name()), Msg.number("pages", text.pageCount()));
            if (!text.command().isEmpty()) {
                line = line.append(ctx.messages().render(sender, "customtext.list-alias",
                        Msg.unparsed("alias", text.command())));
            }
            if (text.join()) {
                line = line.append(ctx.messages().render(sender,
                        text.firstJoinOnly() ? "customtext.list-join-first" : "customtext.list-join"));
            }
            if (text.hidden()) {
                line = line.append(ctx.messages().render(sender, "customtext.list-hidden"));
            }
            if (!text.permissions().isEmpty()) {
                line = line.append(ctx.messages().render(sender, "customtext.list-permission",
                        Msg.unparsed("permission", text.permissionLabel())));
            }
            sender.sendMessage(line);
        }
        return Cmds.OK;
    }

    private int reload(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        module.reloadAsync().whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Reloading the custom texts failed", error);
                module.reply(sender, "customtext.reload-failed");
                return;
            }
            module.reply(sender, "customtext.reloaded",
                    Msg.number("count", result.texts().size()), Msg.number("failed", result.failedFiles()));
        });
        return Cmds.OK;
    }

    private boolean mayUseTexts(CommandSender sender) {
        if (sender.hasPermission(CustomTextModule.PERM_USE)) {
            return true;
        }
        // A file can grant access with its own permission even when atrium.ctext itself is not given.
        return module.allTexts().stream().anyMatch(text -> text.maySee(sender::hasPermission));
    }

    private SuggestionProvider<CommandSourceStack> names() {
        return (c, builder) -> {
            String typed = builder.getRemainingLowerCase();
            for (TextDefinition text : module.visible(c.getSource().getSender())) {
                if (text.name().startsWith(typed)) {
                    builder.suggest(text.name());
                }
            }
            return builder.buildFuture();
        };
    }

    private static String name(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "name");
    }

    private static int page(CommandContext<CommandSourceStack> c) {
        return IntegerArgumentType.getInteger(c, "page");
    }
}
