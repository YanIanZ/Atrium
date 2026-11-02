package dev.yanianz.atrium.module.itemtools;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * {@code /openbook}: opens a book without giving an item.
 *
 * <ul>
 *   <li>{@code /openbook} opens the book already in the hand.</li>
 *   <li>{@code /openbook txt <file>} reads {@code data/itemtools/<bookFolder>/<file>.txt}. Pages are split
 *       by a line containing only {@code ---}; every page is trusted MiniMessage written by an admin.</li>
 *   <li>{@code /openbook ctext <name>} reads the pages of {@code modules/customtext/<name>.yml} read-only.
 *       Its {@code permission} key, when set, is checked on the player the book is opened for.</li>
 * </ul>
 *
 * <p>File names are validated with a strict alphabet, so a name can never escape its folder. Every read
 * happens on an I/O thread; the book is opened on the target player's thread.
 */
final class OpenBookCommand {
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_-]{1,32}");

    private record CustomText(String title, String permission, List<List<String>> pages) { }

    private final ItemSupport support;
    private final ConfigHandle<ItemToolsConfig> config;
    private final Path booksFolder;
    private final Path customTextFolder;
    private volatile List<String> bookNames = List.of();
    private volatile List<String> customTextNames = List.of();

    OpenBookCommand(ItemSupport support, ConfigHandle<ItemToolsConfig> config, Path booksFolder, Path customTextFolder) {
        this.support = support;
        this.config = config;
        this.booksFolder = booksFolder;
        this.customTextFolder = customTextFolder;
        try {
            Files.createDirectories(booksFolder);
        } catch (IOException e) {
            support.ctx().logger().warn("Could not create the book folder {}", booksFolder, e);
        }
        refresh();
    }

    /** Re-reads both folders on an I/O thread; suggestions use the last snapshot. */
    void refresh() {
        support.ctx().scheduler().supplyIo(() -> {
            bookNames = listNames(booksFolder, ".txt");
            customTextNames = listNames(customTextFolder, ".yml");
            return null;
        }).exceptionally(error -> {
            support.ctx().logger().warn("Could not list the book folders", error);
            return null;
        });
    }

    private List<String> listNames(Path folder, String suffix) {
        if (folder == null || !Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(folder)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(suffix))
                    .map(name -> name.substring(0, name.length() - suffix.length())
                            .toLowerCase(Locale.ROOT))
                    .filter(name -> NAME.matcher(name).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            support.ctx().logger().warn("Could not list {}", folder, e);
            return List.of();
        }
    }

    void register() {
        var txt = Cmds.literal("txt", "atrium.openbook")
                .then(Commands.argument("file", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> bookNames))
                        .executes(c -> openText(c, StringArgumentType.getString(c, "file")))
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(c -> openText(c, StringArgumentType.getString(c, "file")))));
        var ctext = Cmds.literal("ctext", "atrium.openbook")
                .then(Commands.argument("name", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> customTextNames))
                        .executes(c -> openCustomText(c, StringArgumentType.getString(c, "name")))
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(c -> openCustomText(c, StringArgumentType.getString(c, "name")))));
        support.register(Cmds.literal("openbook", "atrium.openbook")
                        .executes(this::inHand)
                        .then(txt)
                        .then(ctext)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> {
                                    List<String> names = new ArrayList<>(bookNames);
                                    for (String name : customTextNames) {
                                        if (!names.contains(name)) {
                                            names.add(name);
                                        }
                                    }
                                    return names;
                                }))
                                .executes(c -> openAny(c, StringArgumentType.getString(c, "name")))
                                .then(Commands.argument("target", ArgumentTypes.player())
                                        .executes(c -> openAny(c, StringArgumentType.getString(c, "name"))))),
                "Open a book from your hand, a text file or a customtext page");
    }

    private int inHand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = support.cmds().player(c.getSource());
        support.onPlayer(player, () -> openHeldBook(player));
        return Cmds.OK;
    }

    @SuppressWarnings("deprecation")
    private void openHeldBook(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.WRITTEN_BOOK && hand.getType() != Material.WRITABLE_BOOK) {
            support.replyNow(player, "openbook-not-book");
            return;
        }
        player.openBook(hand);
    }

    private int openText(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        Player target = support.cmds().selfOrOther(c, "target", "atrium.openbook.others");
        CommandSender sender = c.getSource().getSender();
        String id = validName(c, name);
        support.ctx().scheduler().supplyIo(() -> readText(id)).whenComplete((pages, error) -> {
            if (error != null) {
                support.failed(sender, "Reading book " + id, error);
                return;
            }
            if (pages == null) {
                support.reply(sender, "openbook-missing", Msg.unparsed("name", id));
                return;
            }
            open(target, sender, id, pages, "");
        });
        return Cmds.OK;
    }

    private int openCustomText(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        Player target = support.cmds().selfOrOther(c, "target", "atrium.openbook.others");
        CommandSender sender = c.getSource().getSender();
        String id = validName(c, name);
        support.ctx().scheduler().supplyIo(() -> readCustomText(id)).whenComplete((text, error) -> {
            if (error != null) {
                support.failed(sender, "Reading customtext " + id, error);
                return;
            }
            if (text == null) {
                support.reply(sender, "openbook-missing", Msg.unparsed("name", id));
                return;
            }
            open(target, sender, text.title(), text.pages(), text.permission());
        });
        return Cmds.OK;
    }

    /** {@code /openbook <name>}: a text file first, then a customtext page. */
    private int openAny(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        Player target = support.cmds().selfOrOther(c, "target", "atrium.openbook.others");
        CommandSender sender = c.getSource().getSender();
        String id = validName(c, name);
        support.ctx().scheduler().supplyIo(() -> {
            List<List<String>> pages = readText(id);
            if (pages != null) {
                return new Loaded(id, pages, "");
            }
            CustomText text = readCustomText(id);
            return text == null ? null : new Loaded(text.title(), text.pages(), text.permission());
        }).whenComplete((loaded, error) -> {
            if (error != null) {
                support.failed(sender, "Reading book " + id, error);
                return;
            }
            if (loaded == null) {
                support.reply(sender, "openbook-missing", Msg.unparsed("name", id));
                return;
            }
            open(target, sender, loaded.title(), loaded.pages(), loaded.permission());
        });
        return Cmds.OK;
    }

    private record Loaded(String title, List<List<String>> pages, String permission) { }

    private String validName(CommandContext<CommandSourceStack> c, String raw) throws CommandSyntaxException {
        String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(name).matches()) {
            throw support.cmds().fail(c.getSource(), "itemtools.openbook-invalid-name");
        }
        return name;
    }

    /** Text of one book file, or {@code null} when it does not exist. Runs on an I/O thread. */
    private @Nullable List<List<String>> readText(String name) {
        Path file = booksFolder.resolve(name + ".txt");
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            List<List<String>> pages = BookPages.split(Files.readString(file, StandardCharsets.UTF_8));
            return pages;
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Pages of one customtext file, or {@code null} when it does not exist. Runs on an I/O thread. */
    private @Nullable CustomText readCustomText(String name) {
        if (customTextFolder == null) {
            return null;
        }
        Path file = customTextFolder.resolve(name + ".yml");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            ConfigurationNode root = YamlConfigurationLoader.builder().path(file).build().load();
            String title = root.node("name").getString(name);
            String permission = root.node("permission").getString("");
            List<List<String>> pages = BookPages.normalize(readPages(root.node("pages")));
            return new CustomText(title == null || title.isBlank() ? name : title,
                    permission == null ? "" : permission.strip(), pages);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot read " + file, e);
        }
    }

    /** Accepts the same page shapes the customtext module accepts: a list of lines or a list of pages. */
    private static List<List<String>> readPages(ConfigurationNode node) {
        if (node.virtual() || node.empty()) {
            return List.of();
        }
        if (!node.isList()) {
            return List.of(List.of(node.getString("")));
        }
        List<? extends ConfigurationNode> children = node.childrenList();
        boolean anyPage = children.stream().anyMatch(ConfigurationNode::isList);
        List<List<String>> pages = new ArrayList<>();
        if (!anyPage) {
            List<String> lines = new ArrayList<>(children.size());
            for (ConfigurationNode child : children) {
                if (!child.isMap()) {
                    lines.add(child.getString(""));
                }
            }
            pages.add(lines);
            return pages;
        }
        for (ConfigurationNode child : children) {
            if (!child.isList()) {
                pages.add(List.of(child.getString("")));
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (ConfigurationNode line : child.childrenList()) {
                if (!line.isMap() && !line.isList()) {
                    lines.add(line.getString(""));
                }
            }
            pages.add(lines);
        }
        return pages;
    }

    private void open(Player target, CommandSender sender, String title, List<List<String>> pages, String permission) {
        if (pages.isEmpty()) {
            support.reply(sender, "openbook-empty", Msg.unparsed("name", title));
            return;
        }
        Book book = Book.book(support.ctx().messages().parseTrusted(title),
                Component.text(config.get().bookAuthorName()),
                pages.stream()
                        .map(page -> support.ctx().messages().parseTrusted(String.join("\n", page)))
                        .toList());
        support.ctx().scheduler().entity(target, () -> {
            if (!permission.isBlank() && !target.hasPermission(permission)) {
                support.replyNow(target, "openbook-denied", Msg.unparsed("permission", permission));
                if (!sender.equals(target)) {
                    support.reply(sender, "openbook-denied", Msg.unparsed("permission", permission));
                }
                return;
            }
            target.openBook(book);
            if (!sender.equals(target)) {
                support.reply(sender, "openbook-opened-other", Msg.unparsed("player", target.getName()),
                        Msg.unparsed("name", title));
            }
        }, null);
    }
}
