package dev.yanianz.atrium.module.customtext;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Admin text pages from {@code plugins/Atrium/modules/customtext/*.yml}: {@code /ctext <name> [page] [player]},
 * one optional command alias per file, {@code /ctext list}, {@code /ctext reload} and join texts.
 *
 * <p>Texts are trusted MiniMessage. {@code <player>}, {@code <online>} and {@code <world>} are inserted
 * unparsed; optional PlaceholderAPI tokens are resolved before MiniMessage with their values escaped (
 * nothing is evaluated twice). Clickable prev/next links only use the validated file name and page numbers.
 */
public final class CustomTextModule implements AtriumModule, Listener {
    static final String PERM_USE = "atrium.ctext";
    static final String PERM_OTHERS = "atrium.ctext.others";
    static final String PERM_LIST = "atrium.ctext.list";
    static final String PERM_RELOAD = "atrium.ctext.reload";
    static final String PERM_EDIT = "atrium.ctext.edit";
    static final String PERM_BYPASS_COOLDOWN = "atrium.ctext.bypass.cooldown";

    private static final int MAX_JOIN_DELAY_TICKS = 1200;
    private static final List<String> EXAMPLES = List.of("rules.yml", "motd.yml", "info.yml");

    private ModuleContext ctx;
    private ConfigHandle<CustomTextConfig> config;
    private TextRenderer renderer;
    private CustomTextCommands commands;
    private Path folder;
    /** Serialises read-modify-write cycles on the text files. */
    private final Object fileLock = new Object();
    /** Live texts; command nodes look themselves up here on every use, so /ctext reload updates them. */
    private volatile Map<String, TextDefinition> texts = Map.of();
    private volatile Map<String, String> aliasTargets = Map.of();
    private boolean aliasesRegistered;

    @Override
    public String id() {
        return "customtext";
    }

    @Override
    public String description() {
        return "Custom text pages: /ctext, aliases, clickable paging and join messages";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(CustomTextConfig.class, CustomTextConfig::new);
        this.renderer = new TextRenderer(ctx.messages(), ctx.logger());
        this.folder = ctx.plugin().getDataPath().resolve("modules").resolve("customtext");

        ctx.permission(PERM_USE, PermissionDefault.TRUE, "View custom text pages with /ctext");
        ctx.permission(PERM_OTHERS, PermissionDefault.OP, "Show a custom text page to another player");
        ctx.permission(PERM_LIST, PermissionDefault.OP, "List the loaded custom texts");
        ctx.permission(PERM_RELOAD, PermissionDefault.OP, "Reload the custom text files");
        ctx.permission(PERM_EDIT, PermissionDefault.OP, "Edit custom text pages in game");
        ctx.permission(PERM_BYPASS_COOLDOWN, PermissionDefault.OP, "Bypass custom text view cooldowns");

        createExamples();
        TextLoader.Result result;
        try {
            result = TextLoader.load(folder);
        } catch (IOException e) {
            ctx.logger().error("Cannot read the custom text folder {}; starting without texts", folder, e);
            result = TextLoader.Result.empty();
        }
        apply(result);

        this.commands = new CustomTextCommands(this);
        commands.registerRoot();
        commands.registerEdit();
        for (String alias : aliasTargets.keySet()) {
            ctx.command(aliasCommand(alias));
        }
        aliasesRegistered = true;
        ctx.listen(this);
    }

    @Override
    public void reload() {
        // /atrium reload: keep the previous texts when the folder cannot be read.
        try {
            apply(TextLoader.load(folder));
        } catch (IOException e) {
            ctx.logger().error("Cannot reload the custom texts; keeping the previous texts", e);
        }
    }

    private void apply(TextLoader.Result result) {
        result.errors().forEach(line -> ctx.logger().warn("Text file skipped: {}", line));
        result.warnings().forEach(line -> ctx.logger().warn("Text file warning: {}", line));
        Set<String> previousAliases = aliasTargets.keySet();
        this.texts = result.texts();
        this.aliasTargets = result.aliases();
        ctx.logger().info("Loaded {} custom texts ({} files skipped)", result.texts().size(), result.failedFiles());
        if (aliasesRegistered && !previousAliases.equals(aliasTargets.keySet())) {
            ctx.logger().warn("Text command aliases were added or removed: restart the server to register or "
                    + "remove them. /ctext <name> and the reloaded contents already work.");
        }
    }

    // ---- command support ----

    List<TextDefinition> texts() {
        return List.copyOf(texts.values());
    }

    @Nullable TextDefinition text(String name) {
        return texts.get(TextName.normalize(name));
    }

    @Nullable TextDefinition textForAlias(String alias) {
        String name = aliasTargets.get(alias);
        return name == null ? null : texts.get(name);
    }

    List<TextDefinition> visible(CommandSender sender) {
        return texts.values().stream()
                .filter(text -> text.permission().isEmpty() || sender.hasPermission(text.permission()))
                .toList();
    }

    CustomTextConfig config() {
        return config.get();
    }

    ModuleContext context() {
        return ctx;
    }

    Cmds cmds() {
        return ctx.commands();
    }

    /**
     * Shows one page. A player viewer is always handled on their own thread; the console is served inline on
     * the thread that ran the command.
     */
    void display(CommandSender sender, @Nullable Player viewer, TextDefinition text, int page, boolean navigation) {
        if (viewer == null) {
            renderer.send(sender, null, text, page, navigation);
            return;
        }
        ctx.scheduler().entity(viewer, () -> {
            if (viewer.isOnline()) {
                renderer.send(viewer, viewer, text, page, navigation);
            }
        }, null);
    }

    /** Replies to a sender on the right thread. */
    void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(recipient, key, tags);
        }
    }

    /**
     * Acquires the per-text cooldown. Only used for a plain open (no explicit page), so clicking prev/next is
     * never blocked by it. Returns {@code null} when the player may see the text now.
     */
    @Nullable Duration acquireCooldown(TextDefinition text, Player player) {
        if (text.cooldownSeconds() <= 0 || player.hasPermission(PERM_BYPASS_COOLDOWN)) {
            return null;
        }
        Duration left = ctx.cooldowns().tryAcquire(ctx.users().user(player),
                "customtext:" + text.name(), Duration.ofSeconds(text.cooldownSeconds()));
        return left.isZero() ? null : left;
    }

    /** The outcome of an in-game edit: the reloaded texts and the pages that were actually written. */
    record AppliedPages(TextLoader.Result texts, List<List<String>> pages) {}

    /** Reloads the folder on the I/O executor, under the same lock as the in-game editor. */
    CompletableFuture<TextLoader.Result> reloadAsync() {
        return ctx.scheduler().supplyIo(() -> {
            synchronized (fileLock) {
                TextLoader.Result result = TextLoader.load(folder);
                apply(result);
                return result;
            }
        });
    }

    /**
     * Applies one edit to {@code <name>.yml} on the I/O executor. The file is re-read, edited and written
     * atomically (temp file + move) inside {@link #fileLock}, and every text is reloaded before the lock is
     * released. The edit therefore builds on the current file instead of on the snapshot the command was
     * typed against, and two quick edits cannot lose each other's change. Other keys of the file are
     * preserved. A refused edit ({@link TextEditModel.TextEditException}) and a failed write both leave the
     * old texts active because the reload only happens after the move succeeded.
     */
    CompletableFuture<AppliedPages> applyPages(String name, UnaryOperator<List<List<String>>> edit) {
        return ctx.scheduler().supplyIo(() -> {
            synchronized (fileLock) {
                Path file = folder.resolve(name + ".yml");
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("text file is missing: " + file);
                }
                List<List<String>> pages = TextYaml.editPages(file, edit);
                TextLoader.Result result = TextLoader.load(folder);
                apply(result);
                return new AppliedPages(result, pages);
            }
        });
    }

    private CommandSpec aliasCommand(String alias) {
        LiteralCommandNode<CommandSourceStack> node = commands.aliasNode(alias);
        return new CommandSpec(node, "Show the '" + alias + "' custom text", List.of());
    }

    // ---- join ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.get().joinTexts) {
            return;
        }
        Player player = event.getPlayer();
        boolean firstJoin = !player.hasPlayedBefore();
        List<TextDefinition> selected = texts.values().stream()
                .filter(TextDefinition::join)
                .filter(text -> !text.firstJoinOnly() || firstJoin)
                .filter(text -> text.permission().isEmpty() || player.hasPermission(text.permission()))
                .toList();
        if (selected.isEmpty()) {
            return;
        }
        long delay = Math.max(0, Math.min(MAX_JOIN_DELAY_TICKS, config.get().joinDelayTicks));
        ctx.track(ctx.scheduler().entityLater(player, () -> {
            for (TextDefinition text : selected) {
                if (!player.isOnline()) {
                    return;
                }
                renderer.send(player, player, text, 1, false);
            }
        }, null, delay));
    }

    // ---- examples ----

    private void createExamples() {
        try {
            if (Files.isDirectory(folder)) {
                return;
            }
            Files.createDirectories(folder);
            for (String example : EXAMPLES) {
                try (InputStream in = CustomTextModule.class.getResourceAsStream("/customtext/" + example)) {
                    if (in == null) {
                        ctx.logger().warn("Bundled customtext/{} is missing; no example written", example);
                        continue;
                    }
                    Files.copy(in, folder.resolve(example));
                }
            }
        } catch (IOException e) {
            ctx.logger().error("Cannot create the custom text folder {} or its examples", folder, e);
        }
    }
}
