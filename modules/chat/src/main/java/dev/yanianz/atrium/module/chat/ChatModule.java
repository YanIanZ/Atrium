package dev.yanianz.atrium.module.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.api.chat.ChatChannel;
import dev.yanianz.atrium.api.chat.ChatGate;
import dev.yanianz.atrium.api.chat.IgnoreService;
import dev.yanianz.atrium.api.chat.StaffChatEvent;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.proxy.NetworkPlayer;
import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.tablist.TabListService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Chat module: formats public chat through a viewer-aware renderer, adds private messages, staff chat,
 * mentions, per-player chat colours, emoji shortcodes, cross-server chat, /me, /broadcast and /clearchat,
 * and enforces the configurable filter and anti-spam rules.
 */
public final class ChatModule implements AtriumModule, Listener {
    private static final UUID CONSOLE_SENDER = UUID.nameUUIDFromBytes("atrium:console".getBytes(StandardCharsets.UTF_8));

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<ChatConfig> configHandle;
    private ChatStore store;
    private volatile ChatFormatter formatter;
    private volatile ChatColorPalette palette = new ChatColorPalette(List.of());
    private volatile EmojiReplacer.Table emoji = EmojiReplacer.Table.EMPTY;
    private volatile List<ChatFilter.Rule> filterRules = List.of();
    private volatile CapsFilter.Settings capsSettings = new CapsFilter.Settings(false, 60, 6, true, false, List.of());
    private volatile AdFilter.Settings adSettings = new AdFilter.Settings(false, AdFilter.Action.CENSOR, "***",
            Set.of(), true);
    private volatile List<RangeTable.Step> rangeSteps = List.of();
    private volatile List<ChatTags.Definition> tags = List.of();
    private volatile List<ChatConfig.SpriteEntry> sprites = List.of();
    private volatile ChatRoomStore roomStore;
    private volatile Component deleteHover = Component.empty();
    private volatile Sound mentionSound;
    private volatile ChatRelay relay;
    private final SpamGuard spam = new SpamGuard();
    private final CommandSpamGuard commandSpam = new CommandSpamGuard();
    private volatile ChatHistory history = new ChatHistory(200);
    /** World names and block positions are cached on the player's own thread so chat never reads a world. */
    private final java.util.Map<UUID, String> worldNames = new ConcurrentHashMap<>();
    private final java.util.Map<UUID, RangeTable.Position> positions = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public String description() {
        return "Chat formatting, private messages, staff chat, colours, emoji, cross-server chat and moderation";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("proxy", "moderation", "vanish");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.configHandle = ctx.config(ChatConfig.class, ChatConfig::new);
        ctx.migrate(ChatStore.MIGRATIONS);
        this.store = new ChatStore(ctx.database());
        this.roomStore = new ChatRoomStore(ctx.database());
        ctx.component(store.component);
        ctx.service(IgnoreService.class, new ChatIgnoreService(uuid -> cachedUser(uuid).ignored()));
        rebuild();
        try {
            roomStore.install(roomStore.loadAll().get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
            ctx.logger().error("Cannot load chat rooms", e);
        }
        ctx.listen(this);
        new ChatCommands(this).register();

        ctx.permission("atrium.chat.msg", PermissionDefault.TRUE, "Send private messages");
        ctx.permission("atrium.chat.reply", PermissionDefault.TRUE, "Reply to the last private message");
        ctx.permission("atrium.chat.ignore", PermissionDefault.TRUE, "Ignore or unignore a player");
        ctx.permission("atrium.ignore.exempt", PermissionDefault.OP, "Cannot be ignored by other players");
        ctx.permission("atrium.chat.msgtoggle", PermissionDefault.TRUE, "Toggle incoming private messages");
        ctx.permission("atrium.chat.me", PermissionDefault.TRUE, "Use /me");
        ctx.permission("atrium.chat.color", PermissionDefault.FALSE, "Use colour codes in chat");
        ctx.permission("atrium.chat.format", PermissionDefault.FALSE, "Use decoration codes in chat");
        ctx.permission("atrium.chat.link", PermissionDefault.FALSE, "Post clickable links in chat");
        ctx.permission("atrium.chat.advanced", PermissionDefault.OP, "Use full MiniMessage in chat");
        ctx.permission("atrium.chat.emoji", PermissionDefault.TRUE, "Use :shortcode: emoji in messages");
        ctx.permission("atrium.chat.staff", PermissionDefault.OP, "Use staff chat");
        ctx.permission("atrium.chat.broadcast", PermissionDefault.OP, "Send server broadcasts");
        ctx.permission("atrium.chat.clearchat", PermissionDefault.OP, "Clear chat");
        ctx.permission("atrium.chat.clearchat.exempt", PermissionDefault.OP, "Stay visible when chat is cleared");
        ctx.permission("atrium.chat.socialspy", PermissionDefault.OP, "See private messages between players");
        ctx.permission("atrium.chat.filter.alert", PermissionDefault.OP, "Receive chat filter alerts");
        ctx.permission("atrium.chat.filter.bypass", PermissionDefault.OP, "Bypass the chat filter");
        ctx.permission("atrium.chat.spam.bypass", PermissionDefault.OP, "Bypass chat rate limits");
        ctx.permission("atrium.chat.caps.bypass", PermissionDefault.OP, "Bypass the anti-caps filter");
        ctx.permission("atrium.chat.item", PermissionDefault.TRUE, "Use [item] and [item:slot] in chat");
        ctx.permission("atrium.chat.sprite", PermissionDefault.FALSE, "Use [sprite:...] tags in chat");
        ctx.permission("atrium.chat.shout", PermissionDefault.TRUE, "Use the shout prefix");
        ctx.permission("atrium.chat.range.bypass", PermissionDefault.FALSE, "Always receive ranged chat");
        ctx.permission("atrium.chat.range.spy", PermissionDefault.OP, "Toggle range spy with /rangespy");
        ctx.permission("atrium.chat.delete", PermissionDefault.OP, "Delete chat messages for everyone");
        ctx.permission("atrium.chat.rooms", PermissionDefault.TRUE, "Use persistent chat rooms");
        ctx.permission("atrium.chat.rooms.admin", PermissionDefault.OP, "Delete any chat room");
        ctx.permission("atrium.chat.tags", PermissionDefault.TRUE, "Select a chat tag");
        ctx.permission("atrium.chat.signspy", PermissionDefault.OP, "See sign advertisement spy");
        ctx.permission("atrium.chatcolor", PermissionDefault.TRUE, "Choose a default chat colour with /chatcolor");
        ctx.permission(ChatColorPalette.GRADIENT_PERMISSION, PermissionDefault.TRUE, "Use gradient chat colours");
        for (ChatColorPalette.Choice choice : palette.choices()) {
            ctx.permission(ChatColorPalette.permission(choice.name()), PermissionDefault.TRUE,
                    "Use the '" + choice.name() + "' chat colour");
        }
        startRelay();
    }

    @Override
    public void reload() {
        rebuild();
        spam.clear();
        commandSpam.clear();
        history.clear();
        for (ChatColorPalette.Choice choice : palette.choices()) {
            ctx.permission(ChatColorPalette.permission(choice.name()), PermissionDefault.TRUE,
                    "Use the '" + choice.name() + "' chat colour");
        }
        for (AtriumUser user : ctx.users().online()) {
            user.player().ifPresent(this::applyTabTag);
        }
        startRelay();
    }

    @Override
    public void disable() {
        stopRelay();
        spam.clear();
        commandSpam.clear();
        history.clear();
        ctx.services().get(TabListService.class).ifPresent(tab -> {
            tab.clearNameTags("chat-prefix");
            tab.clearNameTags("chat-suffix");
        });
    }

    private void rebuild() {
        ChatConfig config = configHandle.get();
        this.formatter = new ChatFormatter(ctx.messages(), config, this::renderToken);
        this.palette = ChatColorPalette.compile(config.chatColors, message -> ctx.logger().warn(message));
        this.emoji = EmojiReplacer.compile(config.emoji == null ? null : config.emoji.codes,
                message -> ctx.logger().warn(message));
        this.filterRules = ChatFilter.compile(config.filter, message -> ctx.logger().warn(message));
        this.capsSettings = CapsFilter.Settings.of(config.caps);
        this.adSettings = AdFilter.Settings.of(config.ads);
        this.rangeSteps = RangeTable.compile(config.range == null ? null : config.range.dynamic);
        this.tags = ChatTags.compile(config.tags, message -> ctx.logger().warn(message));
        this.sprites = config.sprites == null || config.sprites.entries == null
                ? List.of() : List.copyOf(config.sprites.entries);
        this.history = new ChatHistory(config.deleteButton == null ? 200 : config.deleteButton.historySize);
        this.deleteHover = config.deleteButton == null || config.deleteButton.hover == null
                ? Component.text("Delete") : ctx.messages().parseTrusted(config.deleteButton.hover);
        this.mentionSound = parseSound(config.mentions);
        for (ChatFilter.Rule rule : filterRules) {
            if (!rule.group().isEmpty()) {
                ctx.permission("atrium.chat.filter.bypass." + rule.group(), PermissionDefault.OP,
                        "Bypass the '" + rule.group() + "' chat filter group");
            }
        }
        for (ChatConfig.SpriteEntry sprite : sprites) {
            if (sprite != null && sprite.id != null && !sprite.id.isBlank()) {
                ctx.permission("atrium.chat.sprite." + sprite.id, PermissionDefault.OP,
                        "Use the '" + sprite.id + "' chat sprite");
            }
        }
        for (ChatTags.Definition tag : tags) {
            if (!tag.permission().isEmpty()) {
                ctx.permission(tag.permission(), PermissionDefault.OP, "Use the '" + tag.id() + "' chat tag");
            }
        }
    }

    private void startRelay() {
        stopRelay();
        ChatConfig config = config();
        if (config.crossServer == null || !config.crossServer.enabled) {
            return;
        }
        Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
        if (proxy.isEmpty()) {
            return;
        }
        ChatRelay created = new ChatRelay(ctx, proxy.get(), this);
        created.start();
        this.relay = created;
    }

    private void stopRelay() {
        ChatRelay current = relay;
        relay = null;
        if (current != null) {
            current.close();
        }
    }

    private ChatConfig config() {
        return configHandle.get();
    }

    ModuleContext context() {
        return ctx;
    }

    // ---- chat event ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player sender = event.getPlayer();
        UUID senderId = sender.getUniqueId();

        // A shadow-muted player's message must look normal to them but reach nobody. The moderation module
        // shows it to the sender only (using this renderer) and cancels the event at HIGHEST, so it must not
        // be relayed to staff chat here first.
        boolean shadowMuted = isShadowMuted(senderId);
        boolean staff = cachedUser(senderId).staffChat()
                && sender.hasPermission("atrium.chat.staff") && !shadowMuted;
        Optional<Component> denied = deny(sender, staff ? ChatChannel.STAFF_CHAT : ChatChannel.CHAT);
        if (denied.isPresent()) {
            event.setCancelled(true);
            sender.sendMessage(denied.get());
            return;
        }
        if (staff) {
            event.setCancelled(true);
            String staffText = plain(event.originalMessage());
            sendStaff(sender, staffText, event.originalMessage(), false);
            return;
        }

        ChatConfig config = config();
        String plain = plain(event.originalMessage());
        List<String> names = viewerNames(event);
        List<String> ruleIds = new ArrayList<>();

        String text = plain;
        if (!sender.hasPermission("atrium.chat.caps.bypass")) {
            CapsFilter.Result caps = CapsFilter.check(text, capsSettings, names);
            if (caps.exceeded()) {
                ruleIds.add("caps");
                text = caps.text();
                notifyStaff(sender, plain, List.of("caps"));
                if (capsSettings.cancel()) {
                    event.setCancelled(true);
                    ctx.messages().send(sender, "chat.caps-blocked");
                    return;
                }
            }
        }
        text = applyEmoji(sender, text);

        boolean bypass = sender.hasPermission("atrium.chat.filter.bypass");
        if (!bypass && adSettings.enabled()) {
            AdFilter.Verdict ads = AdFilter.apply(text, adSettings);
            if (!ads.matches().isEmpty()) {
                ruleIds.add("ad");
                if (ads.blocked()) {
                    event.setCancelled(true);
                    ctx.messages().send(sender, "chat.filter-blocked");
                    notifyStaff(sender, plain, List.of("ad"));
                    runFilterActions(sender, plain, List.of("ad"), List.of());
                    return;
                }
                text = ads.text();
            }
        }

        ChatFilter.Verdict verdict = bypass
                ? new ChatFilter.Verdict(text, false, List.of())
                : ChatFilter.apply(text, filterRules, config.filter.replacement,
                        group -> sender.hasPermission("atrium.chat.filter.bypass." + group));
        if (verdict.blocked()) {
            event.setCancelled(true);
            ctx.messages().send(sender, "chat.filter-blocked");
            notifyStaff(sender, plain, verdict);
            runFilterActions(sender, plain, verdict);
            return;
        }
        text = verdict.text();
        if (!bypass && hasWarning(verdict)) {
            notifyStaff(sender, plain, verdict);
        }
        for (ChatFilter.Match match : verdict.matches()) {
            if (!ruleIds.contains(match.ruleId())) {
                ruleIds.add(match.ruleId());
            }
        }
        if (!ruleIds.isEmpty()) {
            if (verdict.matches().isEmpty()) {
                runFilterActions(sender, plain, ruleIds, List.of());
            } else {
                runFilterActions(sender, plain, verdict);
            }
        }

        ChatUser chatUser = cachedUser(senderId);
        if (config.rooms != null && config.rooms.enabled && chatUser.chatRoom() != null) {
            event.setCancelled(true);
            if (!allowSpam(sender, text)) {
                return;
            }
            sendRoom(sender, chatUser.chatRoom(), text);
            return;
        }

        boolean shout = false;
        String shoutPrefix = config.range == null ? "" : config.range.shoutPrefix;
        if (config.range != null && config.range.enabled && shoutPrefix != null && !shoutPrefix.isEmpty()
                && text.startsWith(shoutPrefix)
                && (!config.range.requireShoutPermission || sender.hasPermission("atrium.chat.shout"))) {
            shout = true;
            text = text.substring(shoutPrefix.length());
            if (text.startsWith(" ")) {
                text = text.substring(1);
            }
            if (text.isBlank()) {
                event.setCancelled(true);
                ctx.messages().send(sender, "chat.shout-empty");
                return;
            }
        }

        if (!allowSpam(sender, text)) {
            event.setCancelled(true);
            return;
        }

        if (shout && config.range.shoutCost > 0) {
            event.setCancelled(true);
            chargeAndShout(sender, text);
            return;
        }

        removeIgnoredViewers(event, senderId);
        names = viewerNames(event);
        if (config.range != null && config.range.enabled && !sender.hasPermission("atrium.chat.range.bypass")) {
            int range = shout ? shoutRange() : chatRange();
            removeOutOfRange(event, sender, range);
        }

        List<String> mentioned = config.mentions.enabled
                ? MentionDetector.find(text, names, config.mentions.requireAtSign)
                : List.of();
        Pattern mentionPattern = config.mentions.enabled && !names.isEmpty()
                ? MentionDetector.pattern(names, config.mentions.requireAtSign)
                : null;
        List<Player> mentionTargets = new ArrayList<>();
        for (Audience audience : event.viewers()) {
            if (audience instanceof Player viewer && !viewer.getUniqueId().equals(senderId)
                    && mentioned.stream().anyMatch(name -> name.equalsIgnoreCase(viewer.getName()))) {
                mentionTargets.add(viewer);
            }
        }

        // Publish the filtered text. Later listeners at MONITOR (the Discord bridge, chat bubbles,
        // cross-server relays) read event.message(), so leaving the raw message here would send a
        // filtered word to the public webhook. The renderer still controls how the line is displayed.
        event.message(relayedMessage(text));
        boolean rewritten = !text.equals(plain);
        Component message = formatter.playerMessage(sender, event.originalMessage(), text, rewritten,
                chatColor(sender));
        Component rendered = formatter.format(sender, message, mentionPattern,
                worldNames.getOrDefault(senderId, "?"), tagPrefix(sender), tagSuffix(sender));
        UUID deleteId = null;
        if (config.deleteButton != null && config.deleteButton.enabled) {
            deleteId = history.add(senderId, sender.getName(), text, event.signedMessage(),
                    System.currentTimeMillis()).id();
        }
        final UUID buttonId = deleteId;
        event.renderer((source, displayName, rawMessage, viewer) -> withDeleteButton(rendered, viewer, buttonId));
        for (Player target : mentionTargets) {
            playMentionSoundLater(target);
        }
    }

    private Component withDeleteButton(Component rendered, Audience viewer, @Nullable UUID id) {
        if (id == null || !(viewer instanceof Player player) || !player.hasPermission("atrium.chat.delete")) {
            return rendered;
        }
        ChatConfig config = config();
        String label = config.deleteButton == null ? " [x]" : config.deleteButton.label;
        return rendered.append(Component.text(label == null ? " [x]" : label, NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/chatdelete " + id))
                .hoverEvent(HoverEvent.showText(deleteHover)));
    }

    /**
     * Relays the final plain text of a public message after every other listener had a chance to cancel it.
     * This is what keeps a shadow-muted or otherwise cancelled message from leaking to other servers.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatRelay(AsyncChatEvent event) {
        ChatConfig config = config();
        if (config.range != null && config.range.enabled) {
            // Ranged chat is local to this server; its recipients were already filtered.
            return;
        }
        if (config.crossServer == null || !config.crossServer.enabled || !config.crossServer.globalChat) {
            return;
        }
        ChatRelay current = relay;
        if (current == null || !current.available()) {
            return;
        }
        Player sender = event.getPlayer();
        UUID senderId = sender.getUniqueId();
        if (isShadowMuted(senderId) || isMuted(senderId)) {
            return;
        }
        String text = relayText(sender, event.originalMessage());
        if (text == null || text.isBlank()) {
            return;
        }
        current.publishGlobal(senderId, sender.getName(), text);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        rememberWorld(event.getPlayer());
        rememberPosition(event.getPlayer());
        ctx.scheduler().entity(event.getPlayer(), () -> applyTabTag(event.getPlayer()), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        rememberWorld(event.getPlayer());
        rememberPosition(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || to.getWorld() == null) {
            return;
        }
        if (from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        rememberPosition(event.getPlayer());
    }

    private void rememberWorld(Player player) {
        if (player.getWorld() != null) {
            worldNames.put(player.getUniqueId(), player.getWorld().getName());
        }
    }

    private void rememberPosition(Player player) {
        Location location = player.getLocation();
        if (location == null || location.getWorld() == null) {
            return;
        }
        positions.put(player.getUniqueId(), new RangeTable.Position(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ()));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoinMessage(PlayerJoinEvent event) {
        ChatConfig.JoinQuit settings = config().joinQuit;
        if (settings == null || !settings.enabled || hidden(event.getPlayer())) {
            return;
        }
        Player player = event.getPlayer();
        String format = player.hasPlayedBefore() ? settings.join : settings.firstJoin;
        if (format == null || format.isBlank()) {
            event.joinMessage(null);
            return;
        }
        event.joinMessage(ctx.messages().parseTrusted(format,
                Msg.unparsed("player", player.getName()),
                Msg.component("display_name", player.displayName()),
                Msg.unparsed("world", worldNames.getOrDefault(player.getUniqueId(), "?"))));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuitMessage(PlayerQuitEvent event) {
        ChatConfig.JoinQuit settings = config().joinQuit;
        if (settings == null || !settings.enabled || hidden(event.getPlayer())) {
            return;
        }
        String format = settings.quit;
        if (format == null || format.isBlank()) {
            event.quitMessage(null);
            return;
        }
        Player player = event.getPlayer();
        event.quitMessage(ctx.messages().parseTrusted(format,
                Msg.unparsed("player", player.getName()),
                Msg.component("display_name", player.displayName()),
                Msg.unparsed("world", worldNames.getOrDefault(player.getUniqueId(), "?"))));
    }

    private boolean hidden(Player player) {
        ChatConfig.JoinQuit settings = config().joinQuit;
        if (settings.hideAbove > 0 && ctx.users().online().size() > settings.hideAbove) {
            return true;
        }
        if (settings.respectVanish && isVanished(player.getUniqueId())) {
            return true;
        }
        String world = player.getWorld() == null ? "" : player.getWorld().getName();
        return settings.hiddenWorlds != null && settings.hiddenWorlds.contains(world);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandSpam(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("atrium.chat.spam.bypass")) {
            return;
        }
        CommandSpamGuard.Result result = commandSpam.check(player.getUniqueId(), event.getMessage(),
                System.currentTimeMillis(), CommandSpamGuard.Settings.of(config().commandSpam));
        if (result == CommandSpamGuard.Result.TOO_MANY) {
            event.setCancelled(true);
            ctx.messages().send(player, "chat.command-spam");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        ChatConfig.SignSpy settings = config().signSpy;
        if (settings == null || !settings.enabled) {
            return;
        }
        Player author = event.getPlayer();
        int limit = Math.max(1, Math.min(event.lines().size(), settings.maxLines));
        List<String> lines = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String line = plainComponent(event.line(i));
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty() || !viewer.get().hasPermission("atrium.chat.signspy")) {
                continue;
            }
            ctx.messages().send(viewer.get(), "chat.sign-spy",
                    Msg.unparsed("player", author.getName()),
                    Msg.unparsed("lines", String.join(" | ", lines)));
        }
    }

    private static String plainComponent(@Nullable Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        worldNames.remove(uuid);
        positions.remove(uuid);
        spam.forget(uuid);
        commandSpam.forget(uuid);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    /**
     * The component later MONITOR listeners (the Discord bridge, chat bubbles, cross-server relays) must
     * see: the filtered text as plain text. It is never parsed as MiniMessage, so untrusted chat input
     * cannot inject formatting into those relays.
     */
    static Component relayedMessage(String filtered) {
        return Component.text(filtered == null ? "" : filtered);
    }

    /** Emoji expansion for player-typed text; console text is never touched. */
    private String applyEmoji(CommandSender sender, String raw) {
        ChatConfig config = config();
        if (config.emoji == null || !config.emoji.enabled || raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        if (!(sender instanceof Player player) || !player.hasPermission("atrium.chat.emoji")) {
            return raw;
        }
        return EmojiReplacer.replace(raw, emoji);
    }

    private ChatColorPalette.@Nullable Choice chatColor(Player player) {
        String stored = cachedUser(player.getUniqueId()).chatColor();
        if (stored == null) {
            return null;
        }
        return palette.resolve(stored, player::hasPermission).orElse(null);
    }

    private boolean isMuted(UUID uuid) {
        return ctx.services().get(MuteService.class).map(mutes -> mutes.isMuted(uuid)).orElse(false);
    }

    private boolean isShadowMuted(UUID uuid) {
        return ctx.services().get(MuteService.class).map(mutes -> mutes.isShadowMuted(uuid)).orElse(false);
    }

    private boolean isVanished(UUID uuid) {
        return ctx.services().get(VanishService.class).map(vanish -> vanish.isVanished(uuid)).orElse(false);
    }

    /** Hard mutes also stop command-originated chat (/msg, /me, staff chat), not only AsyncChatEvent. */
    private boolean denyMuted(CommandSender sender) {
        if (sender instanceof Player player && isMuted(player.getUniqueId())) {
            ctx.messages().send(player, "chat.muted");
            return true;
        }
        return false;
    }

    /** The final plain text a viewer would see, or {@code null} when the filter blocks it. */
    private String relayText(Player sender, Component original) {
        String plain = plain(original);
        String expanded = applyEmoji(sender, plain);
        if (!sender.hasPermission("atrium.chat.filter.bypass")) {
            AdFilter.Verdict ads = AdFilter.apply(expanded, adSettings);
            if (ads.blocked()) {
                return null;
            }
            expanded = ads.text();
        }
        ChatFilter.Verdict verdict = sender.hasPermission("atrium.chat.filter.bypass")
                ? new ChatFilter.Verdict(expanded, false, List.of())
                : ChatFilter.apply(expanded, filterRules, config().filter.replacement,
                        group -> sender.hasPermission("atrium.chat.filter.bypass." + group));
        if (verdict.blocked()) {
            return null;
        }
        Component message = formatter.playerMessage(sender, original, verdict.text(),
                !verdict.text().equals(plain), chatColor(sender));
        return plain(message);
    }

    private void removeIgnoredViewers(AsyncChatEvent event, UUID senderId) {
        event.viewers().removeIf(audience -> audience instanceof Player viewer
                && !viewer.getUniqueId().equals(senderId)
                && ignores(viewer, senderId));
    }

    // ---- range, shout and tokens ----

    private Optional<Component> renderToken(Player sender, ChatTokens.Token token) {
        if (token instanceof ChatTokens.Item item) {
            ChatConfig.Items items = config().items;
            if (items == null || !items.enabled
                    || (items.requirePermission && !sender.hasPermission("atrium.chat.item"))) {
                return Optional.of(Component.text(item.raw()));
            }
            Component rendered = ChatItems.render(sender, item.slot());
            return Optional.of(rendered == null ? Component.text(item.raw()) : rendered);
        }
        if (token instanceof ChatTokens.Sprite sprite) {
            return renderSprite(sender, sprite);
        }
        return Optional.empty();
    }

    private Optional<Component> renderSprite(Player sender, ChatTokens.Sprite token) {
        ChatConfig.Sprites settings = config().sprites;
        if (settings == null || !settings.enabled
                || (settings.requirePermission && !sender.hasPermission("atrium.chat.sprite"))) {
            return Optional.of(Component.text(token.raw()));
        }
        String wanted = token.name() == null ? "" : token.name().trim().toLowerCase(Locale.ROOT);
        for (ChatConfig.SpriteEntry entry : sprites) {
            if (entry == null || entry.id == null || !entry.id.equalsIgnoreCase(wanted)) {
                continue;
            }
            if (!token.atlas().isBlank() && entry.atlas != null
                    && !entry.atlas.equalsIgnoreCase(token.atlas())) {
                continue;
            }
            if (settings.requirePermission && !sender.hasPermission("atrium.chat.sprite." + entry.id)) {
                return Optional.of(Component.text(token.raw()));
            }
            if (entry.sprite == null || entry.sprite.isBlank()) {
                return Optional.of(Component.text(token.raw()));
            }
            try {
                Key atlas = Key.key(entry.atlas == null || entry.atlas.isBlank() ? "minecraft" : entry.atlas.trim());
                Key sprite = Key.key(entry.sprite.trim());
                return Optional.of(Component.object(net.kyori.adventure.text.object.ObjectContents.sprite(atlas, sprite)));
            } catch (RuntimeException e) {
                ctx.logger().warn("Invalid sprite '{}:{}' in chat config", entry.atlas, entry.sprite, e);
                return Optional.of(Component.text(token.raw()));
            }
        }
        return Optional.of(Component.text(token.raw()));
    }

    int chatRange() {
        ChatConfig.Range range = config().range;
        if (range == null) {
            return 100;
        }
        int online = ctx.users().online().size();
        return RangeTable.resolve(rangeSteps, online, range.defaultRange);
    }

    int shoutRange() {
        ChatConfig.Range range = config().range;
        if (range == null || range.shoutRange <= 0) {
            return chatRange();
        }
        return range.shoutRange;
    }

    private void removeOutOfRange(AsyncChatEvent event, Player sender, int range) {
        RangeTable.Position origin = positions.get(sender.getUniqueId());
        if (origin == null) {
            return;
        }
        event.viewers().removeIf(audience -> {
            if (!(audience instanceof Player viewer) || viewer.getUniqueId().equals(sender.getUniqueId())) {
                return false;
            }
            if (viewer.hasPermission("atrium.chat.range.bypass") || rangeSpy(viewer)) {
                return false;
            }
            return !RangeTable.inRange(origin, positions.get(viewer.getUniqueId()), range);
        });
    }

    private boolean rangeSpy(Player viewer) {
        return viewer.hasPermission("atrium.chat.range.spy") && cachedUser(viewer.getUniqueId()).rangeSpy();
    }

    private void chargeAndShout(Player sender, String text) {
        ChatConfig.Range range = config().range;
        Optional<EconomyService> economy = ctx.services().get(EconomyService.class);
        if (economy.isEmpty()) {
            sender.sendMessage(ctx.messages().render(sender, "chat.shout-unavailable"));
            return;
        }
        BigDecimal cost = BigDecimal.valueOf(range.shoutCost);
        economy.get().withdraw(sender.getUniqueId(), cost, "chat shout").thenAccept(result -> {
            if (!result.success()) {
                onSender(sender, () -> ctx.messages().send(sender, "chat.shout-no-money",
                        Msg.unparsed("cost", economy.get().format(cost)),
                        Msg.unparsed("balance", economy.get().format(result.balance()))));
                return;
            }
            ctx.scheduler().entity(sender, () -> {
                if (sender.isOnline()) {
                    broadcastShout(sender, text);
                }
            }, null);
        }).exceptionally(throwable -> {
            ctx.logger().warn("Could not charge {} for a shout", sender.getName(), throwable);
            onSender(sender, () -> ctx.messages().send(sender, "chat.shout-unavailable"));
            return null;
        });
    }

    private void broadcastShout(Player sender, String text) {
        int range = shoutRange();
        RangeTable.Position origin = positions.get(sender.getUniqueId());
        Component message = formatter.playerMessage(sender, Component.text(text), text, true, chatColor(sender));
        Component formatted = formatter.format(sender, message, null,
                worldNames.getOrDefault(sender.getUniqueId(), "?"), tagPrefix(sender), tagSuffix(sender));
        int recipients = 0;
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty()) {
                continue;
            }
            Player target = viewer.get();
            if (!target.getUniqueId().equals(sender.getUniqueId())) {
                if (cachedUser(user.uuid()).ignores(sender.getUniqueId())) {
                    continue;
                }
                if (!target.hasPermission("atrium.chat.range.bypass") && !rangeSpy(target)
                        && !RangeTable.inRange(origin, positions.get(target.getUniqueId()), range)) {
                    continue;
                }
            }
            ctx.messages().send(target, "chat.shout-format", Msg.component("message", formatted));
            recipients++;
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.shout-format",
                Msg.component("message", formatted));
        if (recipients == 0) {
            ctx.messages().send(sender, "chat.shout-nobody");
        }
    }

    // ---- chat tags ----

    private Optional<ChatTags.Definition> selectedTag(Player player) {
        ChatConfig.Tags config = config().tags;
        if (config == null || !config.enabled) {
            return Optional.empty();
        }
        return ChatTags.resolve(tags, cachedUser(player.getUniqueId()).chatTag(), player::hasPermission);
    }

    private Component tagPrefix(Player player) {
        return selectedTag(player)
                .map(tag -> ctx.messages().parseTrusted(tag.prefix(), Msg.unparsed("player", player.getName())))
                .orElse(Component.empty());
    }

    private Component tagSuffix(Player player) {
        return selectedTag(player)
                .map(tag -> ctx.messages().parseTrusted(tag.suffix(), Msg.unparsed("player", player.getName())))
                .orElse(Component.empty());
    }

    void applyTabTag(Player player) {
        Optional<TabListService> tab = ctx.services().get(TabListService.class);
        if (tab.isEmpty()) {
            return;
        }
        ChatConfig.Tags config = config().tags;
        Optional<ChatTags.Definition> tag = config != null && config.enabled && config.showInTabList
                ? selectedTag(player) : Optional.empty();
        if (tag.isPresent()) {
            tab.get().setNameTag(player.getUniqueId(), "chat-prefix", TabListService.Placement.BEFORE,
                    tagPrefix(player));
            tab.get().setNameTag(player.getUniqueId(), "chat-suffix", TabListService.Placement.AFTER,
                    tagSuffix(player));
        } else {
            tab.get().setNameTag(player.getUniqueId(), "chat-prefix", TabListService.Placement.BEFORE, null);
            tab.get().setNameTag(player.getUniqueId(), "chat-suffix", TabListService.Placement.AFTER, null);
        }
    }

    private List<String> viewerNames(AsyncChatEvent event) {
        List<String> names = new ArrayList<>();
        for (Audience audience : event.viewers()) {
            if (audience instanceof Player player) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private boolean ignores(Player viewer, UUID senderId) {
        return cachedUser(viewer.getUniqueId()).ignores(senderId);
    }

    /**
     * Component snapshot of any loaded player, safe from the async chat thread. A player whose data is not
     * loaded yet is treated as default state instead of throwing.
     */
    private ChatUser cachedUser(UUID uuid) {
        return ctx.users().cached(uuid).map(user -> {
            try {
                return user.get(store.component);
            } catch (IllegalStateException e) {
                return ChatUser.DEFAULT;
            }
        }).orElse(ChatUser.DEFAULT);
    }

    private Optional<Component> deny(Player sender, ChatChannel channel) {
        Optional<ChatGate> gate = ctx.services().get(ChatGate.class);
        if (gate.isEmpty()) {
            return Optional.empty();
        }
        try {
            return gate.get().denyReason(sender, channel);
        } catch (RuntimeException e) {
            ctx.logger().warn("ChatGate failed for {} on {}", sender.getName(), channel, e);
            return Optional.empty();
        }
    }

    private void playMentionSound(Player player) {
        Sound sound = mentionSound;
        if (sound != null) {
            player.playSound(sound);
        }
    }

    /**
     * Plays the mention sound on the player's own region thread. The chat event is async and the caller
     * may run on another player's thread, so the sound can never be played directly from there.
     */
    private void playMentionSoundLater(Player player) {
        ctx.scheduler().entity(player, () -> {
            if (player.isOnline()) {
                playMentionSound(player);
            }
        }, null);
    }

    private Sound parseSound(ChatConfig.Mentions mentions) {
        if (mentions == null || !mentions.enabled || mentions.sound == null || mentions.sound.isBlank()) {
            return null;
        }
        String key = mentions.sound.trim().toLowerCase(Locale.ROOT);
        if (!key.contains(":")) {
            key = "minecraft:" + key;
        }
        float volume = Math.max(0f, Math.min(4f, mentions.volume));
        float pitch = Math.max(0.1f, Math.min(4f, mentions.pitch));
        try {
            return Sound.sound(Key.key(key), Sound.Source.PLAYER, volume, pitch);
        } catch (RuntimeException e) {
            ctx.logger().warn("Invalid mention sound '{}' in chat config", mentions.sound, e);
            return null;
        }
    }

    private boolean hasWarning(ChatFilter.Verdict verdict) {
        return verdict.matches().stream().anyMatch(match -> match.action() == ChatFilter.Action.WARN);
    }

    private void notifyStaff(Player sender, String message, ChatFilter.Verdict verdict) {
        List<String> rules = verdict.matches().stream()
                .map(ChatFilter.Match::ruleId)
                .distinct()
                .toList();
        notifyStaff(sender, message, rules);
    }

    private void notifyStaff(Player sender, String message, List<String> rules) {
        String joined = String.join(", ", rules);
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> player = user.player();
            if (player.isEmpty() || !player.get().hasPermission("atrium.chat.filter.alert")) {
                continue;
            }
            ctx.messages().send(player.get(), "chat.filter-alert",
                    Msg.unparsed("player", sender.getName()),
                    Msg.unparsed("rules", joined),
                    Msg.unparsed("message", message));
        }
    }

    /** Runs the global and per-rule action lines through the actions module. */
    private void runFilterActions(Player sender, String message, ChatFilter.Verdict verdict) {
        List<String> lines = new ArrayList<>();
        for (ChatFilter.Match match : verdict.matches()) {
            lines.addAll(match.actions());
        }
        runFilterActions(sender, message, verdict.matches().stream().map(ChatFilter.Match::ruleId).toList(), lines);
    }

    private void runFilterActions(Player sender, String message, List<String> rules, List<String> extra) {
        ChatConfig config = config();
        List<String> lines = new ArrayList<>();
        if (config.filter != null && config.filter.actions != null) {
            lines.addAll(config.filter.actions);
        }
        if (extra != null) {
            lines.addAll(extra);
        }
        if (lines.isEmpty()) {
            return;
        }
        Optional<ActionService> service = ctx.services().get(ActionService.class);
        if (service.isEmpty()) {
            return;
        }
        service.get().run(lines, sender, Map.of(
                        "message", message == null ? "" : message,
                        "rules", String.join(",", rules)))
                .exceptionally(throwable -> {
                    ctx.logger().warn("Chat filter actions failed for {}", sender.getName(), throwable);
                    return null;
                });
    }

    private boolean allowSpam(Player sender, String raw) {
        if (sender.hasPermission("atrium.chat.spam.bypass")) {
            return true;
        }
        ChatConfig config = config();
        SpamGuard.Result result = spam.check(sender.getUniqueId(), raw, System.currentTimeMillis(),
                SpamGuard.Settings.of(config.antiSpam));
        if (result == SpamGuard.Result.ALLOWED) {
            return true;
        }
        if (result == SpamGuard.Result.TOO_FAST) {
            double seconds = Math.max(0.1, config.antiSpam.minimumIntervalMillis / 1000.0);
            ctx.messages().send(sender, "chat.spam-too-fast", Msg.number("seconds", seconds));
        } else if (result == SpamGuard.Result.SIMILAR) {
            ctx.messages().send(sender, "chat.spam-similar");
        } else {
            ctx.messages().send(sender, "chat.spam-duplicate");
        }
        return false;
    }

    /** Filter helper for command-originated text. Returns null when the message must be blocked. */
    private String filterText(CommandSender sender, String raw, String blockKey) {
        if (!(sender instanceof Player player) || player.hasPermission("atrium.chat.filter.bypass")) {
            return raw;
        }
        String text = raw;
        AdFilter.Verdict ads = AdFilter.apply(text, adSettings);
        if (ads.blocked()) {
            ctx.messages().send(player, blockKey);
            notifyStaff(player, raw, List.of("ad"));
            return null;
        }
        if (!ads.matches().isEmpty()) {
            notifyStaff(player, raw, List.of("ad"));
            runFilterActions(player, raw, List.of("ad"), List.of());
            text = ads.text();
        }
        ChatFilter.Verdict verdict = ChatFilter.apply(text, filterRules, config().filter.replacement,
                group -> player.hasPermission("atrium.chat.filter.bypass." + group));
        if (verdict.blocked()) {
            ctx.messages().send(player, blockKey);
            notifyStaff(player, raw, verdict);
            runFilterActions(player, raw, verdict);
            return null;
        }
        if (hasWarning(verdict)) {
            notifyStaff(player, raw, verdict);
        }
        if (!verdict.matches().isEmpty()) {
            runFilterActions(player, raw, verdict);
        }
        return verdict.text();
    }

    // ---- private messages ----

    void sendPrivate(CommandSender sender, Player target, String raw) {
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        if (denyMuted(sender)) {
            return;
        }
        if (sender instanceof Player player) {
            Optional<Component> denied = deny(player, ChatChannel.PRIVATE_MESSAGE);
            if (denied.isPresent()) {
                player.sendMessage(denied.get());
                return;
            }
            if (player.getUniqueId().equals(target.getUniqueId())) {
                ctx.messages().send(player, "chat.msg-self");
                return;
            }
            if (!allowSpam(player, raw)) {
                return;
            }
        }
        String expanded = applyEmoji(sender, raw);
        String text = filterText(sender, expanded, "chat.filter-blocked");
        if (text == null) {
            return;
        }
        ChatUser targetUser = user(target);
        if (senderId != null && targetUser.ignores(senderId)) {
            ctx.messages().send(sender, "chat.msg-ignored", Msg.unparsed("player", target.getName()));
            return;
        }
        if (senderId != null && !targetUser.messagesEnabled()) {
            ctx.messages().send(sender, "chat.msg-disabled", Msg.unparsed("player", target.getName()));
            return;
        }

        Component message = sender instanceof Player player
                ? formatter.playerMessage(player, Component.text(text), text, !text.equals(expanded), chatColor(player))
                : Component.text(text);
        ctx.messages().send(target, "chat.msg-received",
                Msg.unparsed("sender", sender.getName()), Msg.component("message", message));
        ctx.messages().send(sender, "chat.msg-sent",
                Msg.unparsed("target", target.getName()), Msg.component("message", message));

        if (sender instanceof Player player) {
            update(player, user -> user.withReplyTo(target.getUniqueId(), target.getName()));
            update(target, user -> user.withReplyTo(player.getUniqueId(), player.getName()));
        }

        for (AtriumUser user : ctx.users().online()) {
            if (user.uuid().equals(target.getUniqueId())
                    || (senderId != null && user.uuid().equals(senderId))) {
                continue;
            }
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty() || !viewer.get().hasPermission("atrium.chat.socialspy")) {
                continue;
            }
            if (!user.get(store.component).socialSpy()) {
                continue;
            }
            ctx.messages().send(viewer.get(), "chat.social-spy",
                    Msg.unparsed("sender", sender.getName()),
                    Msg.unparsed("target", target.getName()),
                    Msg.component("message", message));
        }
    }

    /**
     * Sends a private message to a player on another server. The target was validated against the proxy's
     * cached player list by the caller; the payload carries the final plain text only.
     */
    void sendPrivateRemote(CommandSender sender, String targetName, String raw) {
        ChatConfig config = config();
        ChatRelay current = relay;
        if (config.crossServer == null || !config.crossServer.privateMessages || current == null
                || !current.available()) {
            ctx.messages().send(sender, "chat.cross-server-unavailable");
            return;
        }
        if (!CrossServerChat.validName(targetName)) {
            ctx.messages().send(sender, "chat.msg-unknown", Msg.unparsed("player", targetName));
            return;
        }
        if (denyMuted(sender)) {
            return;
        }
        Player player = sender instanceof Player value ? value : null;
        UUID senderId = player == null ? CONSOLE_SENDER : player.getUniqueId();
        if (player != null) {
            Optional<Component> denied = deny(player, ChatChannel.PRIVATE_MESSAGE);
            if (denied.isPresent()) {
                player.sendMessage(denied.get());
                return;
            }
            if (player.getName().equalsIgnoreCase(targetName)) {
                ctx.messages().send(player, "chat.msg-self");
                return;
            }
            if (!allowSpam(player, raw)) {
                return;
            }
        }
        String expanded = applyEmoji(sender, raw);
        String text = filterText(sender, expanded, "chat.filter-blocked");
        if (text == null) {
            return;
        }
        Component message = player != null
                ? formatter.playerMessage(player, Component.text(text), text, !text.equals(expanded), chatColor(player))
                : Component.text(text);
        String plain = plain(message);
        if (!current.publishPrivate(senderId, sender.getName(), targetName, plain)) {
            ctx.messages().send(sender, "chat.cross-server-unavailable");
            return;
        }
        ctx.messages().send(sender, "chat.msg-sent",
                Msg.unparsed("target", targetName), Msg.component("message", message));
        if (player != null) {
            update(player, user -> user.withReplyTo(null, targetName));
        }
    }

    /** Cached proxy player on another server, or empty when the name is local/unknown/stale. */
    Optional<NetworkPlayer> remotePlayer(String name) {
        if (!CrossServerChat.validName(name)) {
            return Optional.empty();
        }
        Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
        if (proxy.isEmpty()) {
            return Optional.empty();
        }
        String own = proxy.get().serverName();
        return proxy.get().networkPlayers().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(name))
                .filter(candidate -> !candidate.server().equalsIgnoreCase(own))
                .findFirst();
    }

    List<String> messageTargets() {
        Set<String> names = new LinkedHashSet<>();
        for (AtriumUser user : ctx.users().online()) {
            names.add(user.name());
        }
        ctx.services().get(ProxyService.class).ifPresent(proxy -> {
            String own = proxy.serverName();
            List<NetworkPlayer> remote = new ArrayList<>(proxy.networkPlayers());
            remote.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(a.name(), b.name()));
            for (NetworkPlayer candidate : remote) {
                if (!candidate.server().equalsIgnoreCase(own) && CrossServerChat.validName(candidate.name())) {
                    names.add(candidate.name());
                }
            }
        });
        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        return sorted;
    }

    // ---- staff chat ----

    /**
     * Dispatches to an async thread when needed: {@link StaffChatEvent} is an async event, and the event bus
     * refuses to fire it from the primary thread on Paper. Chat already runs async; commands do not.
     */
    void sendStaff(CommandSender sender, String raw, Component original, boolean checkGate) {
        if (Bukkit.isPrimaryThread()) {
            ctx.scheduler().async(() -> sendStaffNow(sender, raw, original, checkGate));
        } else {
            sendStaffNow(sender, raw, original, checkGate);
        }
    }

    private void sendStaffNow(CommandSender sender, String raw, Component original, boolean checkGate) {
        if (denyMuted(sender)) {
            return;
        }
        if (sender instanceof Player player) {
            if (!player.hasPermission("atrium.chat.staff")) {
                return;
            }
            if (checkGate) {
                Optional<Component> denied = deny(player, ChatChannel.STAFF_CHAT);
                if (denied.isPresent()) {
                    player.sendMessage(denied.get());
                    return;
                }
            }
            if (!allowSpam(player, raw)) {
                return;
            }
        }
        String expanded = applyEmoji(sender, raw);
        String text = filterText(sender, expanded, "chat.filter-blocked");
        if (text == null) {
            return;
        }
        Player player = sender instanceof Player value ? value : null;
        Component message = player != null
                ? formatter.playerMessage(player, original, text, !text.equals(expanded), chatColor(player))
                : Component.text(text);
        String finalText = plain(message);

        StaffChatEvent event = new StaffChatEvent(player == null ? null : player.getUniqueId(), sender.getName(),
                finalText, localServerName(), false);
        if (fireStaffChat(event)) {
            return;
        }

        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty() || !viewer.get().hasPermission("atrium.chat.staff")) {
                continue;
            }
            ctx.messages().send(viewer.get(), "chat.staff-format",
                    Msg.unparsed("player", sender.getName()), Msg.component("message", message));
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.staff-format",
                Msg.unparsed("player", sender.getName()), Msg.component("message", message));

        ChatConfig config = config();
        ChatRelay current = relay;
        if (current != null && config.crossServer != null && config.crossServer.staffChat) {
            UUID id = player == null ? CONSOLE_SENDER : player.getUniqueId();
            current.publishStaff(id, sender.getName(), finalText);
        }
    }

    /** Fires the async event and reports whether a listener cancelled it. */
    private boolean fireStaffChat(StaffChatEvent event) {
        try {
            ctx.plugin().getServer().getPluginManager().callEvent(event);
        } catch (RuntimeException e) {
            ctx.logger().warn("A staff chat listener failed; the message is delivered", e);
            return false;
        }
        return event.isCancelled();
    }

    private String localServerName() {
        return ctx.services().get(ProxyService.class)
                .map(ProxyService::serverName)
                .filter(name -> !"unknown".equalsIgnoreCase(name))
                .orElse("");
    }

    // ---- incoming cross-server chat (async proxy thread) ----

    void onRemoteChat(ProxyMessage message, CrossServerChat.Kind kind) {
        ChatRelay current = relay;
        if (current == null) {
            return;
        }
        CrossServerChat.Payload payload = current.decode(message, kind);
        if (payload == null) {
            return;
        }
        ChatConfig config = config();
        if (config.crossServer == null || !config.crossServer.enabled) {
            return;
        }
        // A mute or shadow mute that is known on this server also hides messages from this UUID.
        if (isMuted(payload.sender()) || isShadowMuted(payload.sender())) {
            return;
        }
        switch (kind) {
            case GLOBAL -> {
                // A vanished player's public chat must not leak to the other servers either.
                if (config.crossServer.globalChat && !isVanished(payload.sender())) {
                    deliverRemoteGlobal(message, payload);
                }
            }
            case STAFF -> {
                if (config.crossServer.staffChat) {
                    deliverRemoteStaff(message, payload);
                }
            }
            case PRIVATE -> {
                if (config.crossServer.privateMessages) {
                    deliverRemotePrivate(payload);
                }
            }
        }
    }

    private void deliverRemoteGlobal(ProxyMessage message, CrossServerChat.Payload payload) {
        Component body = Component.text(payload.text());
        String server = CrossServerChat.serverLabel(message.server());
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty() || user.uuid().equals(payload.sender())) {
                continue;
            }
            if (cachedUser(user.uuid()).ignores(payload.sender())) {
                continue;
            }
            ctx.messages().send(viewer.get(), "chat.cross-server-format",
                    Msg.unparsed("server", server),
                    Msg.unparsed("player", payload.senderName()),
                    Msg.component("message", body));
        }
    }

    private void deliverRemoteStaff(ProxyMessage message, CrossServerChat.Payload payload) {
        String server = CrossServerChat.serverLabel(message.server());
        StaffChatEvent event = new StaffChatEvent(payload.sender(), payload.senderName(), payload.text(),
                server, true);
        if (fireStaffChat(event)) {
            return;
        }
        Component body = Component.text(payload.text());
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty() || !viewer.get().hasPermission("atrium.chat.staff")) {
                continue;
            }
            ctx.messages().send(viewer.get(), "chat.staff-format",
                    Msg.unparsed("player", payload.senderName()), Msg.component("message", body));
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.staff-format",
                Msg.unparsed("player", payload.senderName()), Msg.component("message", body));
    }

    private void deliverRemotePrivate(CrossServerChat.Payload payload) {
        Player target = findOnline(payload.targetName());
        if (target == null) {
            return;
        }
        ChatUser targetUser = cachedUser(target.getUniqueId());
        if (targetUser.ignores(payload.sender()) || !targetUser.messagesEnabled()) {
            return;
        }
        Component body = Component.text(payload.text());
        ctx.messages().send(target, "chat.msg-received",
                Msg.unparsed("sender", payload.senderName()), Msg.component("message", body));
        update(target, user -> user.withReplyTo(payload.sender(), payload.senderName()));
    }

    // ---- /me, /broadcast, /clearchat ----

    void sendMe(Player sender, String raw) {
        if (denyMuted(sender)) {
            return;
        }
        Optional<Component> denied = deny(sender, ChatChannel.ME);
        if (denied.isPresent()) {
            sender.sendMessage(denied.get());
            return;
        }
        if (!allowSpam(sender, raw)) {
            return;
        }
        String expanded = applyEmoji(sender, raw);
        String text = filterText(sender, expanded, "chat.filter-blocked");
        if (text == null) {
            return;
        }
        ChatConfig config = config();
        List<Player> recipients = new ArrayList<>();
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> player = user.player();
            if (player.isEmpty()) {
                continue;
            }
            if (!user.uuid().equals(sender.getUniqueId())
                    && cachedUser(user.uuid()).ignored().contains(sender.getUniqueId())) {
                continue;
            }
            recipients.add(player.get());
        }
        List<String> names = recipients.stream().map(Player::getName).toList();
        List<String> mentioned = config.mentions.enabled
                ? MentionDetector.find(text, names, config.mentions.requireAtSign)
                : List.of();
        Pattern mentionPattern = config.mentions.enabled && !names.isEmpty()
                ? MentionDetector.pattern(names, config.mentions.requireAtSign)
                : null;
        Component message = formatter.playerMessage(sender, Component.text(text), text, !text.equals(expanded),
                chatColor(sender));
        message = formatter.withMentions(message, mentionPattern);
        for (Player recipient : recipients) {
            ctx.messages().send(recipient, "chat.me-format",
                    Msg.unparsed("player", sender.getName()), Msg.component("message", message));
            if (!recipient.getUniqueId().equals(sender.getUniqueId())
                    && mentioned.stream().anyMatch(name -> name.equalsIgnoreCase(recipient.getName()))) {
                playMentionSoundLater(recipient);
            }
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.me-format",
                Msg.unparsed("player", sender.getName()), Msg.component("message", message));
    }

    void sendBroadcast(CommandSender sender, String raw) {
        if (denyMuted(sender)) {
            return;
        }
        if (sender instanceof Player player) {
            Optional<Component> denied = deny(player, ChatChannel.BROADCAST);
            if (denied.isPresent()) {
                player.sendMessage(denied.get());
                return;
            }
        }
        String expanded = applyEmoji(sender, raw);
        Component message = sender instanceof Player player
                ? formatter.playerMessage(player, Component.text(expanded), expanded, !expanded.equals(raw),
                        chatColor(player))
                : formatter.trustedMessage(expanded);
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> player = user.player();
            player.ifPresent(value -> ctx.messages().send(value, "chat.broadcast-format",
                    Msg.component("message", message)));
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.broadcast-format",
                Msg.component("message", message));
    }

    int clearChat(CommandContext<CommandSourceStack> context, boolean self) throws CommandSyntaxException {
        CommandSender sender = context.getSource().getSender();
        int lines = Math.max(1, config().clearChatLines);
        Component blank = Component.text("\n".repeat(lines));
        if (self) {
            Player player = cmds.player(context.getSource());
            player.sendMessage(blank);
            ctx.messages().send(player, "chat.clearchat-self");
            return Cmds.OK;
        }
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> player = user.player();
            if (player.isEmpty()) {
                continue;
            }
            Player value = player.get();
            if (!value.hasPermission("atrium.chat.clearchat.exempt")) {
                value.sendMessage(blank);
            }
            ctx.messages().send(value, "chat.clearchat-broadcast", Msg.unparsed("player", sender.getName()));
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.clearchat-broadcast",
                Msg.unparsed("player", sender.getName()));
        return Cmds.OK;
    }

    // ---- rooms, tags and deletion ----

    void sendRoom(Player sender, String roomName, String text) {
        ChatConfig config = config();
        Optional<ChatRoom> found = roomStore.get(roomName);
        if (found.isEmpty()) {
            update(sender, user -> user.withChatRoom(null));
            ctx.messages().send(sender, "chat.room-gone");
            return;
        }
        ChatRoom room = found.get();
        if (!room.isMember(sender.getUniqueId()) && !room.isOwner(sender.getUniqueId())) {
            update(sender, user -> user.withChatRoom(null));
            ctx.messages().send(sender, "chat.room-not-member");
            return;
        }
        String expanded = applyEmoji(sender, text);
        Component message = formatter.playerMessage(sender, Component.text(expanded), expanded,
                !expanded.equals(text), chatColor(sender));
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty()) {
                continue;
            }
            if (!room.isMember(user.uuid()) && !room.isOwner(user.uuid())) {
                continue;
            }
            if (!user.uuid().equals(sender.getUniqueId()) && cachedUser(user.uuid()).ignores(sender.getUniqueId())) {
                continue;
            }
            ctx.messages().send(viewer.get(), "chat.room-format",
                    Msg.unparsed("room", room.name()),
                    Msg.unparsed("player", sender.getName()),
                    Msg.component("message", message));
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "chat.room-format",
                Msg.unparsed("room", room.name()),
                Msg.unparsed("player", sender.getName()),
                Msg.component("message", message));
    }

    int roomInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        if (!roomsEnabled()) {
            throw cmds.fail(context.getSource(), "chat.room-disabled");
        }
        String active = user(player).chatRoom();
        if (active == null) {
            ctx.messages().send(player, "chat.room-usage");
            return Cmds.OK;
        }
        Optional<ChatRoom> room = roomStore.get(active);
        if (room.isEmpty()) {
            update(player, user -> user.withChatRoom(null));
            ctx.messages().send(player, "chat.room-gone");
            return Cmds.OK;
        }
        ChatRoom value = room.get();
        String owner = ctx.users().cached(value.owner()).map(AtriumUser::name).orElse("?");
        ctx.messages().send(player, "chat.room-info",
                Msg.unparsed("room", value.name()),
                Msg.unparsed("owner", owner),
                Msg.number("members", value.members().size()),
                Msg.unparsed("locked", value.locked() ? "yes" : "no"));
        return Cmds.OK;
    }

    int roomList(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!roomsEnabled()) {
            ctx.messages().send(sender, "chat.room-disabled");
            return Cmds.OK;
        }
        List<ChatRoom> rooms = roomStore.all();
        if (rooms.isEmpty()) {
            ctx.messages().send(sender, "chat.room-list-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "chat.room-list-header", Msg.number("count", rooms.size()));
        for (ChatRoom room : rooms) {
            ctx.messages().send(sender, "chat.room-list-entry",
                    Msg.unparsed("room", room.name()),
                    Msg.number("members", room.members().size()),
                    Msg.unparsed("locked", room.locked() ? "yes" : "no"));
        }
        return Cmds.OK;
    }

    int roomCreate(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        if (!roomsEnabled()) {
            throw cmds.fail(context.getSource(), "chat.room-disabled");
        }
        String name = StringArgumentType.getString(context, "name");
        Optional<String> normalised = ChatRoom.normaliseName(name, config().rooms.maxNameLength);
        if (normalised.isEmpty()) {
            throw cmds.fail(context.getSource(), "chat.room-bad-name");
        }
        String roomName = normalised.get();
        if (roomStore.get(roomName).isPresent()) {
            throw cmds.fail(context.getSource(), "chat.room-exists", Msg.unparsed("room", roomName));
        }
        if (roomStore.ownedBy(player.getUniqueId()) >= Math.max(1, config().rooms.maxRoomsPerPlayer)
                && !player.hasPermission("atrium.chat.rooms.admin")) {
            throw cmds.fail(context.getSource(), "chat.room-limit");
        }
        ChatRoom room = new ChatRoom(roomName, player.getUniqueId(), Set.of(player.getUniqueId()), Set.of(),
                false, System.currentTimeMillis());
        roomStore.put(room);
        persistRoom(player, room);
        update(player, user -> user.withChatRoom(roomName));
        ctx.messages().send(player, "chat.room-created", Msg.unparsed("room", roomName));
        return Cmds.OK;
    }

    int roomJoin(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        if (!roomsEnabled()) {
            throw cmds.fail(context.getSource(), "chat.room-disabled");
        }
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        ChatRoom room = roomStore.get(name)
                .orElseThrow(() -> cmds.fail(context.getSource(), "chat.room-unknown", Msg.unparsed("room", name)));
        if (!room.canJoin(player.getUniqueId(), config().rooms.allowPublicJoin)) {
            throw cmds.fail(context.getSource(), "chat.room-locked", Msg.unparsed("room", room.name()));
        }
        if (!room.isMember(player.getUniqueId()) && room.members().size() >= Math.max(1, config().rooms.maxMembers)) {
            throw cmds.fail(context.getSource(), "chat.room-full", Msg.unparsed("room", room.name()));
        }
        ChatRoom updated = room.withMember(player.getUniqueId());
        roomStore.put(updated);
        persistRoom(player, updated);
        update(player, user -> user.withChatRoom(updated.name()));
        ctx.messages().send(player, "chat.room-joined", Msg.unparsed("room", updated.name()));
        return Cmds.OK;
    }

    int roomLeave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        String active = user(player).chatRoom();
        if (active == null) {
            ctx.messages().send(player, "chat.room-not-in-one");
            return Cmds.OK;
        }
        update(player, user -> user.withChatRoom(null));
        Optional<ChatRoom> room = roomStore.get(active);
        if (room.isPresent()) {
            ChatRoom updated = room.get().withoutMember(player.getUniqueId());
            roomStore.put(updated);
            persistRoom(player, updated);
        }
        ctx.messages().send(player, "chat.room-left", Msg.unparsed("room", active));
        return Cmds.OK;
    }

    int roomInvite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        ChatRoom room = requireOwnedRoom(context, player);
        String name = StringArgumentType.getString(context, "player");
        Player target = findOnline(name);
        if (target == null) {
            throw cmds.fail(context.getSource(), "core.player-not-found");
        }
        if (room.isMember(target.getUniqueId())) {
            ctx.messages().send(player, "chat.room-already-member", Msg.unparsed("player", target.getName()));
            return Cmds.OK;
        }
        ChatRoom updated = room.withInvited(target.getUniqueId());
        roomStore.put(updated);
        persistRoom(player, updated);
        ctx.messages().send(player, "chat.room-invited", Msg.unparsed("player", target.getName()),
                Msg.unparsed("room", updated.name()));
        ctx.messages().send(target, "chat.room-invite-received", Msg.unparsed("player", player.getName()),
                Msg.unparsed("room", updated.name()));
        return Cmds.OK;
    }

    int roomKick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        ChatRoom room = requireOwnedRoom(context, player);
        String name = StringArgumentType.getString(context, "player");
        Player target = findOnline(name);
        if (target == null) {
            throw cmds.fail(context.getSource(), "core.player-not-found");
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            throw cmds.fail(context.getSource(), "chat.room-kick-self");
        }
        if (!room.isMember(target.getUniqueId())) {
            throw cmds.fail(context.getSource(), "chat.room-target-not-member", Msg.unparsed("player", target.getName()));
        }
        ChatRoom updated = room.withoutMember(target.getUniqueId());
        roomStore.put(updated);
        persistRoom(player, updated);
        update(target, user -> user.withChatRoom(null));
        ctx.messages().send(player, "chat.room-kicked", Msg.unparsed("player", target.getName()));
        ctx.messages().send(target, "chat.room-kicked-self",
                Msg.unparsed("room", updated.name()), Msg.unparsed("player", player.getName()));
        return Cmds.OK;
    }

    int roomLock(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        ChatRoom room = requireOwnedRoom(context, player);
        ChatRoom updated = room.withLocked(!room.locked());
        roomStore.put(updated);
        persistRoom(player, updated);
        ctx.messages().send(player, updated.locked() ? "chat.room-locked-on" : "chat.room-locked-off",
                Msg.unparsed("room", updated.name()));
        return Cmds.OK;
    }

    int roomDelete(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        String name = StringArgumentType.getString(context, "name").toLowerCase(Locale.ROOT);
        ChatRoom room = roomStore.get(name)
                .orElseThrow(() -> cmds.fail(context.getSource(), "chat.room-unknown", Msg.unparsed("room", name)));
        if (!room.isOwner(player.getUniqueId()) && !player.hasPermission("atrium.chat.rooms.admin")) {
            throw cmds.fail(context.getSource(), "chat.room-not-owner");
        }
        roomStore.remove(room.name());
        roomStore.delete(room.name()).exceptionally(throwable -> {
            ctx.logger().warn("Cannot delete chat room {}", room.name(), throwable);
            return false;
        });
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isPresent() && room.name().equals(cachedUser(user.uuid()).chatRoom())) {
                update(viewer.get(), value -> value.withChatRoom(null));
                ctx.messages().send(viewer.get(), "chat.room-deleted", Msg.unparsed("room", room.name()));
            }
        }
        ctx.messages().send(player, "chat.room-deleted", Msg.unparsed("room", room.name()));
        return Cmds.OK;
    }

    int roomWho(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        String name = user(player).chatRoom();
        if (name == null) {
            ctx.messages().send(player, "chat.room-not-in-one");
            return Cmds.OK;
        }
        return roomWho(context, name);
    }

    int roomWhoNamed(CommandContext<CommandSourceStack> context) {
        return roomWho(context, StringArgumentType.getString(context, "name"));
    }

    private int roomWho(CommandContext<CommandSourceStack> context, String name) {
        CommandSender sender = context.getSource().getSender();
        Optional<ChatRoom> room = roomStore.get(name);
        if (room.isEmpty()) {
            ctx.messages().send(sender, "chat.room-unknown", Msg.unparsed("room", name));
            return Cmds.OK;
        }
        List<String> online = new ArrayList<>();
        for (AtriumUser user : ctx.users().online()) {
            if (room.get().isMember(user.uuid()) || room.get().isOwner(user.uuid())) {
                online.add(user.name());
            }
        }
        online.sort(String.CASE_INSENSITIVE_ORDER);
        ctx.messages().send(sender, "chat.room-who",
                Msg.unparsed("room", room.get().name()),
                Msg.number("online", online.size()),
                Msg.number("members", room.get().members().size()),
                Msg.unparsed("players", online.isEmpty() ? "-" : String.join(", ", online)));
        return Cmds.OK;
    }

    private boolean roomsEnabled() {
        return config().rooms != null && config().rooms.enabled;
    }

    private ChatRoom requireOwnedRoom(CommandContext<CommandSourceStack> context, Player player)
            throws CommandSyntaxException {
        if (!roomsEnabled()) {
            throw cmds.fail(context.getSource(), "chat.room-disabled");
        }
        String active = user(player).chatRoom();
        if (active == null) {
            throw cmds.fail(context.getSource(), "chat.room-not-in-one");
        }
        ChatRoom room = roomStore.get(active)
                .orElseThrow(() -> cmds.fail(context.getSource(), "chat.room-gone"));
        if (!room.isOwner(player.getUniqueId()) && !player.hasPermission("atrium.chat.rooms.admin")) {
            throw cmds.fail(context.getSource(), "chat.room-not-owner");
        }
        return room;
    }

    private void persistRoom(Player actor, ChatRoom room) {
        roomStore.save(room).exceptionally(throwable -> {
            ctx.logger().warn("Cannot save chat room {}", room.name(), throwable);
            onSender(actor, () -> ctx.messages().send(actor, "chat.room-save-failed"));
            return null;
        });
    }

    List<String> roomNames() {
        List<String> names = new ArrayList<>();
        for (ChatRoom room : roomStore.all()) {
            names.add(room.name());
        }
        return names;
    }

    int chatTagInfo(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        Optional<ChatTags.Definition> tag = selectedTag(player);
        if (tag.isEmpty()) {
            ctx.messages().send(player, "chat.tag-none");
        } else {
            ctx.messages().send(player, "chat.tag-current", Msg.unparsed("tag", tag.get().id()));
        }
        return Cmds.OK;
    }

    int chatTagList(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<ChatTags.Definition> allowed = new ArrayList<>();
        for (ChatTags.Definition tag : tags) {
            if (ChatTags.allowed(tag, sender::hasPermission)) {
                allowed.add(tag);
            }
        }
        if (allowed.isEmpty()) {
            ctx.messages().send(sender, "chat.tag-list-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "chat.tag-list-header", Msg.number("count", allowed.size()));
        TextComponent.Builder line = Component.text();
        for (int i = 0; i < allowed.size(); i++) {
            ChatTags.Definition tag = allowed.get(i);
            if (i > 0) {
                line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            }
            line.append(Component.text(tag.id(), NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/chattag set " + tag.id()))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "chat.tag-hover",
                            Msg.unparsed("tag", tag.id())))));
        }
        sender.sendMessage(line.build());
        return Cmds.OK;
    }

    int chatTagSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        String id = StringArgumentType.getString(context, "tag").toLowerCase(Locale.ROOT);
        ChatTags.Definition tag = ChatTags.byId(tags, id)
                .orElseThrow(() -> cmds.fail(context.getSource(), "chat.tag-unknown", Msg.unparsed("tag", id)));
        if (!ChatTags.allowed(tag, player::hasPermission)) {
            throw cmds.fail(context.getSource(), "chat.tag-no-permission", Msg.unparsed("tag", id));
        }
        update(player, user -> user.withChatTag(tag.id()));
        applyTabTag(player);
        saveThen(player, "chat.tag-set", Msg.unparsed("tag", tag.id()));
        return Cmds.OK;
    }

    int chatTagReset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        update(player, user -> user.withChatTag(null));
        applyTabTag(player);
        saveThen(player, "chat.tag-reset");
        return Cmds.OK;
    }

    List<String> tagNames() {
        List<String> names = new ArrayList<>();
        for (ChatTags.Definition tag : tags) {
            names.add(tag.id());
        }
        return names;
    }

    int rangeSpy(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        if (!config().range.allowSpy) {
            throw cmds.fail(context.getSource(), "chat.rangespy-disabled");
        }
        boolean enabled = !user(player).rangeSpy();
        update(player, user -> user.withRangeSpy(enabled));
        ctx.messages().send(player, enabled ? "chat.rangespy-on" : "chat.rangespy-off");
        return Cmds.OK;
    }

    int deleteMessage(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player staff = cmds.player(context.getSource());
        String raw = StringArgumentType.getString(context, "id");
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(context.getSource(), "chat.delete-unknown");
        }
        ChatHistory.Entry entry = history.remove(id)
                .orElseThrow(() -> cmds.fail(context.getSource(), "chat.delete-unknown"));
        if (entry.signed() == null || !entry.signed().canDelete()) {
            ctx.messages().send(staff, "chat.delete-fallback", Msg.unparsed("player", entry.senderName()));
            return Cmds.OK;
        }
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> viewer = user.player();
            if (viewer.isEmpty()) {
                continue;
            }
            Player target = viewer.get();
            ctx.scheduler().entity(target, () -> {
                try {
                    target.deleteMessage(entry.signed());
                } catch (RuntimeException e) {
                    ctx.logger().warn("Cannot delete a chat message for {}", target.getName(), e);
                }
            }, null);
        }
        ctx.messages().send(staff, "chat.deleted", Msg.unparsed("player", entry.senderName()));
        return Cmds.OK;
    }


    /** Shows a short usage line instead of a silent failure for an incomplete command. */
    int usage(CommandContext<CommandSourceStack> context, String key) {
        ctx.messages().send(context.getSource().getSender(), key);
        return Cmds.OK;
    }

    int msg(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "target");
        String message = StringArgumentType.getString(context, "message");
        CommandSender sender = context.getSource().getSender();
        Player local = findOnline(name);
        if (local != null) {
            sendPrivate(sender, local, message);
            return Cmds.OK;
        }
        Optional<NetworkPlayer> remote = remotePlayer(name);
        if (remote.isPresent()) {
            sendPrivateRemote(sender, remote.get().name(), message);
            return Cmds.OK;
        }
        throw cmds.fail(context.getSource(), "chat.msg-unknown", Msg.unparsed("player", name));
    }

    int reply(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player sender = cmds.player(context.getSource());
        ChatUser user = user(sender);
        UUID targetId = user.replyTo();
        if (targetId != null) {
            Player target = ctx.plugin().getServer().getPlayer(targetId);
            if (target != null) {
                sendPrivate(sender, target, StringArgumentType.getString(context, "message"));
                return Cmds.OK;
            }
        }
        String name = user.replyToName();
        if (name != null) {
            Player local = findOnline(name);
            if (local != null) {
                sendPrivate(sender, local, StringArgumentType.getString(context, "message"));
                return Cmds.OK;
            }
            Optional<NetworkPlayer> remote = remotePlayer(name);
            if (remote.isPresent()) {
                sendPrivateRemote(sender, remote.get().name(), StringArgumentType.getString(context, "message"));
                return Cmds.OK;
            }
        }
        throw cmds.fail(context.getSource(), targetId == null && name == null
                ? "chat.reply-none" : "chat.reply-offline");
    }

    int ignore(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player sender = cmds.player(context.getSource());
        String name = StringArgumentType.getString(context, "player");
        Player online = findOnline(name);
        if (online != null) {
            toggleIgnore(sender, online, online.getUniqueId(), online.getName());
            return Cmds.OK;
        }
        ctx.users().loadByName(name).thenAccept(found -> {
            if (found.isEmpty()) {
                onSender(sender, () ->
                        ctx.messages().send(sender, "core.user-not-found", Msg.unparsed("name", name)));
                return;
            }
            UUID targetId = found.get().uuid();
            String targetName = found.get().name();
            onSender(sender, () -> toggleIgnore(sender, null, targetId, targetName));
        }).exceptionally(throwable -> {
            ctx.logger().warn("Could not load {} for /ignore", name, throwable);
            onSender(sender, () -> ctx.messages().send(sender, "core.error"));
            return null;
        });
        return Cmds.OK;
    }

    /** Runs on the player's own thread; the name lookup above completes on the I/O executor. */
    private void onSender(Player sender, Runnable action) {
        ctx.scheduler().entity(sender, action, null);
    }

    int ignoreList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player sender = cmds.player(context.getSource());
        Set<UUID> ignored = user(sender).ignored();
        if (ignored.isEmpty()) {
            ctx.messages().send(sender, "chat.ignore-list-empty");
            return Cmds.OK;
        }
        List<String> names = new ArrayList<>(ignored.size());
        for (UUID id : ignored) {
            names.add(ctx.users().cached(id)
                    .map(AtriumUser::name)
                    .orElse(id.toString()));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        ctx.messages().send(sender, "chat.ignore-list",
                Msg.number("count", names.size()),
                Msg.unparsed("players", String.join(", ", names)));
        return Cmds.OK;
    }

    private Player findOnline(String name) {
        for (AtriumUser user : ctx.users().online()) {
            if (user.name().equalsIgnoreCase(name)) {
                return user.player().orElse(null);
            }
        }
        return null;
    }

    /**
     * Toggles one ignore entry. Staff with {@code atrium.ignore.exempt} cannot be ignored; that check needs
     * their effective permissions, so it only applies while the target is online.
     */
    private void toggleIgnore(Player sender, Player target, UUID targetId, String targetName) {
        if (sender.getUniqueId().equals(targetId)) {
            ctx.messages().send(sender, "chat.ignore-self");
            return;
        }
        if (target != null && target.hasPermission("atrium.ignore.exempt")) {
            ctx.messages().send(sender, "chat.ignore-exempt", Msg.unparsed("player", targetName));
            return;
        }
        boolean ignored = user(sender).ignores(targetId);
        update(sender, user -> user.withIgnored(targetId, !ignored));
        ctx.messages().send(sender, ignored ? "chat.ignore-removed" : "chat.ignore-added",
                Msg.unparsed("player", targetName));
    }

    int msgToggle(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        boolean enabled = !user(player).messagesEnabled();
        update(player, user -> user.withMessagesEnabled(enabled));
        ctx.messages().send(player, enabled ? "chat.msgtoggle-on" : "chat.msgtoggle-off");
        return Cmds.OK;
    }

    int socialSpy(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        boolean enabled = !user(player).socialSpy();
        update(player, user -> user.withSocialSpy(enabled));
        ctx.messages().send(player, enabled ? "chat.socialspy-on" : "chat.socialspy-off");
        return Cmds.OK;
    }

    int staffChat(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        boolean enabled = !user(player).staffChat();
        update(player, user -> user.withStaffChat(enabled));
        ctx.messages().send(player, enabled ? "chat.staff-on" : "chat.staff-off");
        return Cmds.OK;
    }

    int staffChatMessage(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "message");
        sendStaff(context.getSource().getSender(), raw, Component.text(raw), true);
        return Cmds.OK;
    }

    int broadcast(CommandContext<CommandSourceStack> context) {
        sendBroadcast(context.getSource().getSender(), StringArgumentType.getString(context, "message"));
        return Cmds.OK;
    }

    int me(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        sendMe(cmds.player(context.getSource()), StringArgumentType.getString(context, "action"));
        return Cmds.OK;
    }

    // ---- /chatcolor and /emoji ----

    int chatColorInfo(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        if (!(sender instanceof Player player)) {
            return chatColorList(context);
        }
        ChatColorPalette.Choice current = chatColor(player);
        if (current == null) {
            ctx.messages().send(player, "chat.chatcolor-current-none", Msg.unparsed("usage", "/chatcolor <color>"));
        } else {
            ctx.messages().send(player, "chat.chatcolor-current",
                    Msg.component("color", swatch(current)), Msg.unparsed("name", current.name()));
        }
        return Cmds.OK;
    }

    int chatColorList(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        List<ChatColorPalette.Choice> allowed = allowedColors(sender);
        if (allowed.isEmpty()) {
            ctx.messages().send(sender, "chat.chatcolor-list-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "chat.chatcolor-list-header", Msg.number("count", allowed.size()));
        TextComponent.Builder line = Component.text();
        for (int i = 0; i < allowed.size(); i++) {
            ChatColorPalette.Choice choice = allowed.get(i);
            if (i > 0) {
                line.append(Component.text(", ", NamedTextColor.DARK_GRAY));
            }
            line.append(ChatColorPalette.apply(Component.text(choice.name()), choice)
                    .clickEvent(ClickEvent.runCommand("/chatcolor " + choice.name()))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "chat.chatcolor-hover",
                            Msg.unparsed("name", choice.name())))));
        }
        sender.sendMessage(line.build());
        return Cmds.OK;
    }

    int chatColorSet(CommandContext<CommandSourceStack> context, String argument, @Nullable Boolean gradient)
            throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        String name = StringArgumentType.getString(context, argument);
        ChatColorPalette.Choice choice = palette.byName(name).orElseThrow(() -> cmds.fail(context.getSource(),
                "chat.chatcolor-unknown", Msg.unparsed("color", name), Msg.unparsed("colors", String.join(", ",
                        palette.choices().stream().map(ChatColorPalette.Choice::name).toList()))));
        if (gradient != null && choice.gradient() != gradient) {
            throw cmds.fail(context.getSource(), "chat.chatcolor-wrong-type",
                    Msg.unparsed("color", choice.name()),
                    Msg.unparsed("type", gradient ? "gradient" : "color"));
        }
        if (!player.hasPermission(ChatColorPalette.permission(choice.name()))) {
            throw cmds.fail(context.getSource(), "chat.chatcolor-no-permission",
                    Msg.unparsed("color", choice.name()));
        }
        if (choice.gradient() && !player.hasPermission(ChatColorPalette.GRADIENT_PERMISSION)) {
            throw cmds.fail(context.getSource(), "chat.chatcolor-no-permission",
                    Msg.unparsed("color", ChatColorPalette.GRADIENT_PERMISSION));
        }
        update(player, user -> user.withChatColor(choice.name()));
        saveThen(player, "chat.chatcolor-set", Msg.component("color", swatch(choice)),
                Msg.unparsed("name", choice.name()));
        return Cmds.OK;
    }

    int chatColorReset(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        update(player, user -> user.withChatColor(null));
        saveThen(player, "chat.chatcolor-reset");
        return Cmds.OK;
    }

    private List<ChatColorPalette.Choice> allowedColors(CommandSender sender) {
        List<ChatColorPalette.Choice> allowed = new ArrayList<>();
        for (ChatColorPalette.Choice choice : palette.choices()) {
            if (sender.hasPermission(ChatColorPalette.permission(choice.name()))
                    && (!choice.gradient() || sender.hasPermission(ChatColorPalette.GRADIENT_PERMISSION))) {
                allowed.add(choice);
            }
        }
        return allowed;
    }

    List<String> chatColorNames(@Nullable Boolean gradient) {
        List<String> names = new ArrayList<>();
        for (ChatColorPalette.Choice choice : palette.choices()) {
            if (gradient == null || choice.gradient() == gradient) {
                names.add(choice.name());
            }
        }
        return names;
    }

    private static Component swatch(ChatColorPalette.Choice choice) {
        return ChatColorPalette.apply(Component.text(choice.name()), choice);
    }

    private void saveThen(Player player, String key, TagResolver... resolvers) {
        ctx.users().saveNow(player.getUniqueId()).thenRun(() -> ctx.scheduler().entity(player,
                () -> ctx.messages().send(player, key, resolvers), null))
                .exceptionally(throwable -> {
                    ctx.logger().warn("Could not save the chat settings of {}", player.getName(), throwable);
                    ctx.scheduler().entity(player,
                            () -> ctx.messages().send(player, "chat.chatcolor-save-failed"), null);
                    return null;
                });
    }

    int emojiList(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        ChatConfig config = config();
        if (config.emoji == null || !config.emoji.enabled) {
            ctx.messages().send(sender, "chat.emoji-disabled");
            return Cmds.OK;
        }
        EmojiReplacer.Table current = emoji;
        if (current.isEmpty()) {
            ctx.messages().send(sender, "chat.emoji-empty");
            return Cmds.OK;
        }
        List<Map.Entry<String, String>> entries = new ArrayList<>(current.entries());
        entries.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
        ctx.messages().send(sender, "chat.emoji-header", Msg.number("count", entries.size()));
        TextComponent.Builder line = Component.text();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, String> entry = entries.get(i);
            String token = entry.getKey();
            if (i > 0) {
                line.append(Component.text(" ", NamedTextColor.DARK_GRAY));
            }
            line.append(Component.text(token, NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.suggestCommand(token + " "))
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "chat.emoji-hover",
                            Msg.unparsed("emoji", entry.getValue())))));
        }
        sender.sendMessage(line.build());
        return Cmds.OK;
    }

    ChatUser user(Player player) {
        return ctx.users().user(player).get(store.component);
    }

    private void update(Player player, java.util.function.UnaryOperator<ChatUser> change) {
        ctx.users().user(player).update(store.component, change);
    }
}
