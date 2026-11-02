package dev.yanianz.atrium.core;

import dev.yanianz.atrium.core.command.CommandService;
import dev.yanianz.atrium.core.docs.DocsGenerator;
import dev.yanianz.atrium.core.docs.HelpIndex;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.MenuService;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * {@code /atrium help [page|module]} (clickable chat listing) and {@code /atrium menu} (the same as a
 * menu). Only commands the sender may use are listed: the root literal and every sub-node are checked
 * with Brigadier's own requirement ({@code node.canUse(source)}), the same check the server uses.
 */
final class HelpCommand {
    static final int PER_PAGE = 10;
    private static final int MENU_USAGES = 8;
    private static final Map<String, Material> ICONS = icons();

    private final AtriumPlugin plugin;
    private final Messages messages;

    HelpCommand(AtriumPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Commands the source may use, grouped by owning module. */
    HelpIndex index(CommandSourceStack source) {
        CommandService service = plugin.commandService();
        Set<String> disabled = service.disabled();
        List<HelpIndex.Command> commands = new ArrayList<>();
        for (CommandService.Entry entry : service.entries()) {
            var spec = entry.spec();
            var node = spec.node();
            if (DocsGenerator.isDisabled(spec.name(), disabled) || !node.canUse(source)) {
                continue;
            }
            List<String> usages = DocsGenerator.usages(node, child -> child.canUse(source));
            if (usages.isEmpty()) {
                continue;
            }
            commands.add(new HelpIndex.Command(entry.owner(), spec.name(), spec.description(), usages));
        }
        return new HelpIndex(commands);
    }

    Set<String> visibleModules(CommandSourceStack source) {
        return index(source).byModule().keySet();
    }

    // ------------------------------------------------------------------ chat

    void sendPage(CommandSourceStack source, int page) {
        Audience sender = source.getSender();
        HelpIndex index = index(source);
        if (index.isEmpty()) {
            messages.send(sender, "core.help.empty");
            return;
        }
        HelpIndex.Page result = index.page(page, PER_PAGE);
        Map<String, String> descriptions = moduleDescriptions();
        sender.sendMessage(messages.render(sender, "core.help.header",
                Msg.number("page", result.page() + 1), Msg.number("pages", result.pages())));
        for (HelpIndex.Line line : result.lines()) {
            switch (line) {
                case HelpIndex.Heading heading -> sender.sendMessage(messages.render(sender, "core.help.module",
                        Msg.unparsed("module", heading.module()),
                        Msg.unparsed("description", descriptions.getOrDefault(heading.module(), ""))));
                case HelpIndex.Entry entry -> sender.sendMessage(entry(sender, entry.command()));
            }
        }
        sender.sendMessage(footer(sender, result));
    }

    void sendModule(CommandSourceStack source, String module) {
        Audience sender = source.getSender();
        List<HelpIndex.Command> commands = index(source).module(module);
        if (commands.isEmpty()) {
            messages.send(sender, "core.help.unknown-module", Msg.unparsed("module", module));
            return;
        }
        String id = commands.getFirst().module();
        sender.sendMessage(messages.render(sender, "core.help.module-header", Msg.unparsed("module", id),
                Msg.unparsed("description", moduleDescriptions().getOrDefault(id, ""))));
        for (HelpIndex.Command command : commands) {
            sendCommand(sender, command);
        }
    }

    /** A command with every usable usage line, each clickable. */
    private void sendCommand(Audience sender, HelpIndex.Command command) {
        sender.sendMessage(entry(sender, command));
        for (String usage : command.usages()) {
            sender.sendMessage(messages.render(sender, "core.help.usage", Msg.unparsed("usage", usage))
                    .clickEvent(ClickEvent.suggestCommand(HelpIndex.pasteText(usage)))
                    .hoverEvent(HoverEvent.showText(messages.render(sender, "core.help.click-to-paste",
                            Msg.unparsed("command", usage)))));
        }
    }

    private Component entry(Audience sender, HelpIndex.Command command) {
        List<Component> usageLines = command.usages().stream()
                .limit(MENU_USAGES)
                .map(u -> messages.render(sender, "core.help.usage", Msg.unparsed("usage", u)))
                .toList();
        Component hover = messages.render(sender, "core.help.hover", Msg.unparsed("command", "/" + command.name()),
                Msg.component("usages", Component.join(JoinConfiguration.newlines(), usageLines)));
        return messages.render(sender, "core.help.entry", Msg.unparsed("command", "/" + command.name()),
                        Msg.unparsed("description", command.description()))
                .clickEvent(ClickEvent.suggestCommand("/" + command.name() + " "))
                .hoverEvent(HoverEvent.showText(hover));
    }

    private Component footer(Audience sender, HelpIndex.Page page) {
        Component previous = page.page() > 0
                ? messages.render(sender, "core.help.previous")
                        .clickEvent(ClickEvent.runCommand("/atrium help " + page.page()))
                : messages.render(sender, "core.help.previous-disabled");
        Component next = page.page() + 1 < page.pages()
                ? messages.render(sender, "core.help.next")
                        .clickEvent(ClickEvent.runCommand("/atrium help " + (page.page() + 2)))
                : messages.render(sender, "core.help.next-disabled");
        return messages.render(sender, "core.help.footer", Msg.component("previous", previous),
                Msg.component("next", next), Msg.number("page", page.page() + 1), Msg.number("pages", page.pages()));
    }

    // ------------------------------------------------------------------ menu

    void openMenu(Player player, CommandSourceStack source) {
        MenuService menus = plugin.menus();
        Map<String, String> descriptions = moduleDescriptions();
        menus.open(player, PagedMenu.builder(menus, messages.render(player, "core.help.menu-title"))
                .content(viewer -> {
                    List<Button> buttons = new ArrayList<>();
                    index(source).byModule().forEach((module, commands) -> buttons.add(Button.of(
                            ItemBuilder.of(ICONS.getOrDefault(module, Material.BOOK))
                                    .name(messages.render(viewer, "core.help.menu-module-name", Msg.unparsed("module", module)))
                                    .lore(messages.render(viewer, "core.help.menu-module-lore",
                                            Msg.unparsed("description", descriptions.getOrDefault(module, "")),
                                            Msg.number("count", commands.size())))
                                    .hideDetails()
                                    .build(),
                            click -> click.open(moduleMenu(menus, source, module)))));
                    return buttons;
                })
                .build());
    }

    private PagedMenu moduleMenu(MenuService menus, CommandSourceStack source, String module) {
        return PagedMenu.builder(menus, messages.render(source.getSender(), "core.help.menu-module-title",
                        Msg.unparsed("module", module)))
                .content(viewer -> index(source).module(module).stream().map(command -> {
                    List<Component> lore = new ArrayList<>();
                    lore.add(messages.render(viewer, "core.help.menu-command-lore",
                            Msg.unparsed("description", command.description())));
                    command.usages().stream().limit(MENU_USAGES).forEach(u ->
                            lore.add(messages.render(viewer, "core.help.usage", Msg.unparsed("usage", u))));
                    lore.add(messages.render(viewer, "core.help.menu-command-hint"));
                    return Button.of(ItemBuilder.of(Material.PAPER)
                                    .name(messages.render(viewer, "core.help.menu-command-name",
                                            Msg.unparsed("command", "/" + command.name())))
                                    .lore(lore)
                                    .build(),
                            click -> {
                                click.close();
                                sendCommand(click.player(), command);
                            });
                }).toList())
                .build();
    }

    // ------------------------------------------------------------------ data

    private Map<String, String> moduleDescriptions() {
        Map<String, String> map = new HashMap<>();
        for (AtriumModule module : plugin.moduleManager().modules()) {
            map.put(module.id(), module.description());
        }
        map.putIfAbsent("core", "Atrium");
        return map;
    }

    private static Map<String, Material> icons() {
        Map<String, Material> map = new HashMap<>();
        String[][] pairs = {
                {"core", "NETHER_STAR"}, {"actions", "COMMAND_BLOCK"}, {"afk", "CLOCK"}, {"aliases", "NAME_TAG"},
                {"armorstand", "ARMOR_STAND"}, {"bossbar", "DRAGON_HEAD"}, {"chat", "WRITABLE_BOOK"},
                {"chatbubbles", "WHITE_BANNER"}, {"commandrules", "REPEATING_COMMAND_BLOCK"},
                {"customtext", "OAK_SIGN"}, {"deathmessages", "SKELETON_SKULL"}, {"dialogs", "BOOK"},
                {"discord", "AMETHYST_SHARD"}, {"economy", "GOLD_INGOT"}, {"elytra", "ELYTRA"},
                {"extras", "CHEST"}, {"holograms", "GLOW_ITEM_FRAME"}, {"homes", "RED_BED"},
                {"importer", "HOPPER"}, {"inspect", "SPYGLASS"}, {"integrations", "REDSTONE"},
                {"interactive", "LEVER"}, {"inventories", "ENDER_CHEST"}, {"itemtools", "ANVIL"},
                {"kits", "SHULKER_BOX"}, {"mail", "PAPER"}, {"maps", "FILLED_MAP"}, {"messaging", "FEATHER"},
                {"mobs", "ZOMBIE_HEAD"}, {"moderation", "IRON_SWORD"}, {"nick", "NAME_TAG"},
                {"npcs", "VILLAGER_SPAWN_EGG"}, {"performance", "COMPARATOR"}, {"placeholders", "KNOWLEDGE_BOOK"},
                {"player", "PLAYER_HEAD"}, {"playerplus", "GOLDEN_APPLE"}, {"playtime", "CLOCK"},
                {"portals", "END_PORTAL_FRAME"}, {"proxy", "ENDER_EYE"}, {"ranks", "DIAMOND"},
                {"recipes", "CRAFTING_TABLE"}, {"rtp", "COMPASS"}, {"scoreboard", "ITEM_FRAME"},
                {"search", "SPYGLASS"}, {"seating", "OAK_STAIRS"}, {"seen", "ENDER_EYE"}, {"signs", "OAK_SIGN"},
                {"skins", "LEATHER_CHESTPLATE"}, {"tablist", "LECTERN"}, {"teleport", "ENDER_PEARL"},
                {"vanish", "GLASS"}, {"warps", "LODESTONE"}, {"worldrules", "GRASS_BLOCK"},
                {"worldtools", "WOODEN_AXE"}, {"worth", "EMERALD"}};
        for (String[] pair : pairs) {
            Material material = Material.matchMaterial(pair[1]);
            if (material != null && material.isItem()) {
                map.put(pair[0], material);
            }
        }
        return Map.copyOf(map);
    }
}
