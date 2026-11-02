package dev.yanianz.atrium.module.localeeditor;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /editlocale} (browser), {@code set}, {@code reset}, {@code preview} and {@code search}.
 *
 * <p>Arguments are validated before any file is touched: the locale and module must be a single safe path segment and
 * the key a dotted key. The text is validated as MiniMessage with the core theme tags. File work happens on the I/O
 * executor; every answer is sent on the sender's own thread.
 */
final class LocaleEditorCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<LocaleEditorConfig> config;
    private final LangStore store;
    private final LocaleEditorMenu menu;

    LocaleEditorCommands(ModuleContext ctx, ConfigHandle<LocaleEditorConfig> config, LangStore store) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.store = store;
        this.menu = new LocaleEditorMenu(ctx, config, store);
    }

    void register() {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmds.literal("editlocale", LocaleEditorModule.PERM);
        root.executes(this::open);
        root.then(Cmds.literal("set").executes(usage("usage-set")).then(editChain(this::set, true)));
        root.then(Cmds.literal("reset").executes(usage("usage-reset")).then(editChain(this::reset, false)));
        root.then(Cmds.literal("preview").executes(usage("usage-preview")).then(editChain(this::preview, false)));
        root.then(Cmds.literal("search").executes(usage("usage-search"))
                .then(Commands.argument("query", StringArgumentType.greedyString()).executes(this::search)));
        ctx.command(CommandSpec.of(root.build(),
                "Browse, set, reset, preview and search Atrium's language files", "editlang"));
    }

    /** {@code <locale> <module> <key> [text]}; the trailing greedy text only exists for {@code set}. */
    private ArgumentBuilder<CommandSourceStack, ?> editChain(
            com.mojang.brigadier.Command<CommandSourceStack> handler, boolean withText) {
        var key = Commands.argument("key", StringArgumentType.word()).suggests(keys());
        if (withText) {
            key.executes(usage("usage-set"))
                    .then(Commands.argument("text", StringArgumentType.greedyString()).executes(handler));
        } else {
            key.executes(handler);
        }
        return Commands.argument("locale", StringArgumentType.word()).suggests(locales())
                .then(Commands.argument("module", StringArgumentType.word()).suggests(modules())
                        .then(key));
    }

    private com.mojang.brigadier.Command<CommandSourceStack> usage(String key) {
        return c -> {
            ctx.messages().send(c.getSource().getSender(), "localeeditor." + key);
            return Cmds.OK;
        };
    }

    // ---- /editlocale ----

    private int open(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!(sender instanceof Player viewer)) {
            ctx.messages().send(sender, "localeeditor.usage");
            return Cmds.OK;
        }
        menu.openLocales(viewer);
        return Cmds.OK;
    }

    // ---- /editlocale set ----

    private int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String locale = locale(c);
        String module = module(c);
        String key = key(c);
        String text = StringArgumentType.getString(c, "text");
        Optional<String> problem = TemplateValidator.validate(text, ctx.messages().miniMessage(),
                TemplateValidator.known(ctx.messages()));
        if (problem.isPresent()) {
            ctx.messages().send(sender, "localeeditor.invalid-template", Msg.unparsed("error", problem.get()));
            return Cmds.OK;
        }
        ctx.scheduler().supplyIo(() -> store.set(locale, module, key, text)).whenComplete((edit, error) -> {
            if (error != null) {
                LocaleReport.report(ctx, sender, error, "Saving " + locale + "/" + module + "." + key, key);
                return;
            }
            onSender(sender, () -> {
                sendKeyLine(sender, edit.created() ? "localeeditor.created" : "localeeditor.updated",
                        locale, module, key);
                sendPreview(sender, locale, module, key, edit.value());
            });
        });
        return Cmds.OK;
    }

    // ---- /editlocale reset ----

    private int reset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String locale = locale(c);
        String module = module(c);
        String key = key(c);
        ctx.scheduler().supplyIo(() -> store.reset(locale, module, key)).whenComplete((edit, error) -> {
            if (error != null) {
                LocaleReport.report(ctx, sender, error, "Resetting " + locale + "/" + module + "." + key, key);
                return;
            }
            onSender(sender, () -> {
                if (edit.isEmpty()) {
                    ctx.messages().send(sender, "localeeditor.no-default",
                            Msg.unparsed("locale", locale), Msg.unparsed("module", module),
                            Msg.unparsed("key", key));
                    return;
                }
                sendKeyLine(sender, "localeeditor.reset-done", locale, module, key);
                sendPreview(sender, locale, module, key, edit.get().value());
            });
        });
        return Cmds.OK;
    }

    // ---- /editlocale preview ----

    private int preview(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String locale = locale(c);
        String module = module(c);
        String key = key(c);
        ctx.scheduler().supplyIo(() -> store.current(locale, module, key)).whenComplete((value, error) -> {
            if (error != null) {
                LocaleReport.report(ctx, sender, error, "Previewing " + locale + "/" + module + "." + key, key);
                return;
            }
            onSender(sender, () -> {
                if (value.isEmpty()) {
                    ctx.messages().send(sender, "localeeditor.key-missing", Msg.unparsed("locale", locale),
                            Msg.unparsed("module", module), Msg.unparsed("key", key));
                    return;
                }
                sendPreview(sender, locale, module, key, value.get());
            });
        });
        return Cmds.OK;
    }

    // ---- /editlocale search ----

    private int search(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String query = StringArgumentType.getString(c, "query").strip();
        int limit = LocaleEditorConfig.searchLimit(config.get());
        ctx.scheduler().supplyIo(() -> store.search(query, limit)).whenComplete((hits, error) -> {
            if (error != null) {
                LocaleReport.report(ctx, sender, error, "Searching the language files for " + query, "");
                return;
            }
            onSender(sender, () -> renderSearch(sender, query, hits, limit));
        });
        return Cmds.OK;
    }

    private void renderSearch(CommandSender sender, String query, List<LangStore.SearchHit> hits, int limit) {
        if (hits.isEmpty()) {
            ctx.messages().send(sender, "localeeditor.search-empty", Msg.unparsed("query", query));
            return;
        }
        int previewLength = LocaleEditorConfig.previewLength(config.get());
        ctx.messages().send(sender, "localeeditor.search-header", Msg.unparsed("query", query),
                Msg.number("count", hits.size()));
        for (LangStore.SearchHit hit : hits) {
            Component line = ctx.messages().render(sender, "localeeditor.search-entry",
                    Msg.unparsed("locale", hit.locale()), Msg.unparsed("module", hit.module()),
                    Msg.unparsed("key", hit.key()),
                    Msg.unparsed("value", LocaleEditorMenu.shorten(hit.value(), previewLength)));
            line = line.clickEvent(ClickEvent.suggestCommand(
                            "/editlocale set " + hit.locale() + " " + hit.module() + " " + hit.key() + " "))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "localeeditor.search-hover",
                            Msg.unparsed("key", hit.key()), Msg.unparsed("value", hit.value()))));
            sender.sendMessage(line);
        }
        if (hits.size() >= limit) {
            ctx.messages().send(sender, "localeeditor.search-more", Msg.number("limit", limit));
        }
    }

    // ---- helpers ----

    private void sendKeyLine(CommandSender sender, String messageKey, String locale, String module, String key) {
        ctx.messages().send(sender, messageKey, Msg.unparsed("locale", locale), Msg.unparsed("module", module),
                Msg.unparsed("key", key));
    }

    private void sendPreview(CommandSender sender, String locale, String module, String key, String text) {
        ctx.messages().send(sender, "localeeditor.preview-header", Msg.unparsed("locale", locale),
                Msg.unparsed("module", module), Msg.unparsed("key", key));
        try {
            Component rendered = TemplateValidator.render(text, ctx.messages().miniMessage(),
                    TemplateValidator.known(ctx.messages()));
            sender.sendMessage(rendered);
        } catch (RuntimeException e) {
            ctx.messages().send(sender, "localeeditor.preview-invalid", Msg.unparsed("error",
                    String.valueOf(e.getMessage())));
        }
    }

    private String locale(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "locale");
        try {
            return KeyPath.locale(raw);
        } catch (KeyPath.InvalidKeyException e) {
            throw cmds.fail(c.getSource(), "localeeditor.invalid-locale", Msg.unparsed("input", raw));
        }
    }

    private String module(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "module");
        if (!KeyPath.validName(raw)) {
            throw cmds.fail(c.getSource(), "localeeditor.invalid-module", Msg.unparsed("input", raw));
        }
        return raw;
    }

    private String key(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "key");
        try {
            return KeyPath.join(KeyPath.parse(raw));
        } catch (KeyPath.InvalidKeyException e) {
            throw cmds.fail(c.getSource(), "localeeditor.invalid-key", Msg.unparsed("input", raw));
        }
    }

    // ---- tab completion ----

    private SuggestionProvider<CommandSourceStack> locales() {
        return (c, builder) -> ctx.scheduler().supplyIo(store::localeNames).thenApply(names -> {
            suggest(builder, names);
            return builder.build();
        }).exceptionally(error -> {
            ctx.logger().debug("Locale suggestions failed", error);
            return builder.build();
        });
    }

    private SuggestionProvider<CommandSourceStack> modules() {
        return (c, builder) -> {
            String raw = StringArgumentType.getString(c, "locale");
            String locale;
            try {
                locale = KeyPath.locale(raw);
            } catch (KeyPath.InvalidKeyException e) {
                return builder.buildFuture();
            }
            return ctx.scheduler().supplyIo(() -> store.moduleNames(locale)).thenApply(names -> {
                suggest(builder, names);
                return builder.build();
            }).exceptionally(error -> {
                ctx.logger().debug("Module suggestions for {} failed", locale, error);
                return builder.build();
            });
        };
    }

    private SuggestionProvider<CommandSourceStack> keys() {
        return (c, builder) -> {
            String locale;
            String module;
            try {
                locale = KeyPath.locale(StringArgumentType.getString(c, "locale"));
                module = StringArgumentType.getString(c, "module");
            } catch (IllegalArgumentException e) {
                return builder.buildFuture();
            }
            if (!KeyPath.validName(module)) {
                return builder.buildFuture();
            }
            return ctx.scheduler().supplyIo(() -> store.load(locale, module).entries()).thenApply(entries -> {
                suggest(builder, entries.stream().map(LangDocument.Entry::key).toList());
                return builder.build();
            }).exceptionally(error -> {
                ctx.logger().debug("Key suggestions for {}/{} failed", locale, module, error);
                return builder.build();
            });
        };
    }

    private static void suggest(com.mojang.brigadier.suggestion.SuggestionsBuilder builder, List<String> values) {
        String remaining = builder.getRemainingLowerCase();
        for (String value : values) {
            if (value.toLowerCase(java.util.Locale.ROOT).startsWith(remaining)) {
                builder.suggest(value);
            }
        }
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }
}
