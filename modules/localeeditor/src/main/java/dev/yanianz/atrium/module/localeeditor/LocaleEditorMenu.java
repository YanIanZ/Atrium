package dev.yanianz.atrium.module.localeeditor;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * The {@code /editlocale} browser: locales → modules → keys, every page a {@link PagedMenu} on {@code ctx.menus()}
 * (display only, all clicks cancelled, closed on quit/world change/module disable).
 *
 * <p>Files are read on the I/O executor; the menu is then opened on the viewer's own thread. A click never trusts a
 * stale page: the clicked key is looked up again from the data of that page, and a key that disappeared just warns.
 */
final class LocaleEditorMenu {
    private final ModuleContext ctx;
    private final ConfigHandle<LocaleEditorConfig> config;
    private final LangStore store;

    LocaleEditorMenu(ModuleContext ctx, ConfigHandle<LocaleEditorConfig> config, LangStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
    }

    void openLocales(Player viewer) {
        ctx.scheduler().supplyIo(() -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String locale : store.localeNames()) {
                counts.put(locale, store.moduleNames(locale).size());
            }
            return counts;
        }).whenComplete((counts, error) -> {
            if (error != null) {
                failed(viewer, "Reading the language folders", error);
                return;
            }
            onViewer(viewer, () -> ctx.menus().open(viewer,
                    PagedMenu.builder(ctx.menus(), title(viewer, "localeeditor.menu.locales-title"))
                            .rows(LocaleEditorConfig.rows(config.get()))
                            .content(player -> localeButtons(player, counts))
                            .build()));
        });
    }

    private void openModules(Player viewer, String locale) {
        ctx.scheduler().supplyIo(() -> store.moduleNames(locale)).whenComplete((modules, error) -> {
            if (error != null) {
                failed(viewer, "Reading the modules of " + locale, error);
                return;
            }
            onViewer(viewer, () -> ctx.menus().push(viewer,
                    PagedMenu.builder(ctx.menus(), title(viewer, "localeeditor.menu.modules-title",
                                    Msg.unparsed("locale", locale)))
                            .rows(LocaleEditorConfig.rows(config.get()))
                            .content(player -> moduleButtons(player, locale, modules))
                            .build()));
        });
    }

    private void openKeys(Player viewer, String locale, String module) {
        ctx.scheduler().supplyIo(() -> store.load(locale, module).entries()).whenComplete((entries, error) -> {
            if (error != null) {
                failed(viewer, "Reading " + locale + "/" + module, error);
                return;
            }
            onViewer(viewer, () -> ctx.menus().push(viewer,
                    PagedMenu.builder(ctx.menus(), title(viewer, "localeeditor.menu.keys-title",
                                    Msg.unparsed("locale", locale), Msg.unparsed("module", module)))
                            .rows(LocaleEditorConfig.rows(config.get()))
                            .content(player -> keyButtons(player, locale, module, entries))
                            .build()));
        });
    }

    // ---- pages ----

    private List<Button> localeButtons(Player viewer, Map<String, Integer> counts) {
        List<Button> buttons = new ArrayList<>(counts.size());
        counts.forEach((locale, modules) -> buttons.add(Button.of(
                ItemBuilder.of(Material.KNOWLEDGE_BOOK)
                        .name(text(viewer, "localeeditor.menu.locale-name", Msg.unparsed("locale", locale)))
                        .lore(text(viewer, "localeeditor.menu.locale-lore", Msg.number("modules", modules)))
                        .hideDetails()
                        .build(),
                click -> openModules(click.player(), locale))));
        return buttons;
    }

    private List<Button> moduleButtons(Player viewer, String locale, List<String> modules) {
        List<Button> buttons = new ArrayList<>(modules.size());
        for (String module : modules) {
            buttons.add(Button.of(
                    ItemBuilder.of(Material.BOOK)
                            .name(text(viewer, "localeeditor.menu.module-name", Msg.unparsed("module", module)))
                            .lore(text(viewer, "localeeditor.menu.module-lore", Msg.unparsed("locale", locale)))
                            .hideDetails()
                            .build(),
                    click -> openKeys(click.player(), locale, module)));
        }
        return buttons;
    }

    private List<Button> keyButtons(Player viewer, String locale, String module,
            List<LangDocument.Entry> entries) {
        int previewLength = LocaleEditorConfig.previewLength(config.get());
        List<Button> buttons = new ArrayList<>(entries.size());
        for (LangDocument.Entry entry : entries) {
            String set = "/editlocale set " + locale + " " + module + " " + entry.key() + " ";
            String reset = "/editlocale reset " + locale + " " + module + " " + entry.key();
            Material material = entry.value().contains("<newline>") ? Material.WRITABLE_BOOK : Material.PAPER;
            ItemStack icon = ItemBuilder.of(material)
                    .name(text(viewer, "localeeditor.menu.key-name", Msg.unparsed("key", entry.key())))
                    .lore(List.of(
                            text(viewer, "localeeditor.menu.key-value",
                                    Msg.unparsed("value", shorten(entry.value(), previewLength))),
                            Component.empty(),
                            text(viewer, "localeeditor.menu.key-set"),
                            text(viewer, "localeeditor.menu.key-reset"),
                            text(viewer, "localeeditor.menu.key-preview")))
                    .hideDetails()
                    .build();
            buttons.add(Button.of(icon, click -> suggest(click, set, entry.key(), "localeeditor.click-set"))
                    .onShiftLeft(click -> suggest(click, reset, entry.key(), "localeeditor.click-reset"))
                    .onRight(click -> preview(click.player(), locale, module, entry)));
        }
        return buttons;
    }

    // ---- clicks ----

    private void suggest(MenuClick click, String command, String key, String messageKey) {
        Player viewer = click.player();
        click.close();
        Component line = ctx.messages().render(viewer, messageKey, Msg.unparsed("key", key))
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(ctx.messages().render(viewer, "localeeditor.click-hover",
                        Msg.unparsed("command", command))));
        viewer.sendMessage(line);
    }

    private void preview(Player viewer, String locale, String module, LangDocument.Entry entry) {
        Component rendered;
        try {
            rendered = TemplateValidator.render(entry.value(), ctx.messages().miniMessage(),
                    TemplateValidator.known(ctx.messages()));
        } catch (RuntimeException e) {
            ctx.messages().send(viewer, "localeeditor.preview-invalid",
                    Msg.unparsed("error", String.valueOf(e.getMessage())));
            return;
        }
        ctx.messages().send(viewer, "localeeditor.preview-header", Msg.unparsed("locale", locale),
                Msg.unparsed("module", module), Msg.unparsed("key", entry.key()));
        viewer.sendMessage(rendered);
        ctx.messages().send(viewer, "localeeditor.preview-raw", Msg.unparsed("text", entry.value()));
    }

    // ---- helpers ----

    private Component title(Player viewer, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        return ctx.messages().render(viewer, key, tags);
    }

    private Component text(Player viewer, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        return ctx.messages().render(viewer, key, tags);
    }

    static String shorten(String value, int limit) {
        String display = value == null ? "" : value.replace("<newline>", " / ");
        if (display.length() <= limit) {
            return display;
        }
        return display.substring(0, Math.max(0, limit - 1)) + "…";
    }

    private void onViewer(Player viewer, Runnable work) {
        ctx.scheduler().entity(viewer, work, null);
    }

    private void failed(Player viewer, String operation, Throwable error) {
        LocaleReport.report(ctx, viewer, error, operation, "");
    }
}
