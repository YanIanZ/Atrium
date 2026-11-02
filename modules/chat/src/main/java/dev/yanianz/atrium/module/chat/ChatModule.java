package dev.yanianz.atrium.module.chat;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.chat.ChatChannel;
import dev.yanianz.atrium.api.chat.ChatGate;
import dev.yanianz.atrium.api.chat.IgnoreService;
import dev.yanianz.atrium.api.chat.StaffChatEvent;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.proxy.NetworkPlayer;
import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncChatEvent;
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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
    private volatile Sound mentionSound;
    private volatile ChatRelay relay;
    private final SpamGuard spam = new SpamGuard();
    /** World names are cached on the player's own thread so the async chat event never reads a world. */
    private final java.util.Map<UUID, String> worldNames = new ConcurrentHashMap<>();

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
        ctx.component(store.component);
        ctx.service(IgnoreService.class, new ChatIgnoreService(uuid -> cachedUser(uuid).ignored()));
        rebuild();
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
        for (ChatColorPalette.Choice choice : palette.choices()) {
            ctx.permission(ChatColorPalette.permission(choice.name()), PermissionDefault.TRUE,
                    "Use the '" + choice.name() + "' chat colour");
        }
        startRelay();
    }

    @Override
    public void disable() {
        stopRelay();
        spam.clear();
    }

    private void rebuild() {
        ChatConfig config = configHandle.get();
        this.formatter = new ChatFormatter(ctx.messages(), config);
        this.palette = ChatColorPalette.compile(config.chatColors, message -> ctx.logger().warn(message));
        this.emoji = EmojiReplacer.compile(config.emoji == null ? null : config.emoji.codes,
                message -> ctx.logger().warn(message));
        this.filterRules = ChatFilter.compile(config.filter, message -> ctx.logger().warn(message));
        this.mentionSound = parseSound(config.mentions);
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
        String expanded = applyEmoji(sender, plain);
        boolean bypass = sender.hasPermission("atrium.chat.filter.bypass");
        ChatFilter.Verdict verdict = bypass
                ? new ChatFilter.Verdict(expanded, false, List.of())
                : ChatFilter.apply(expanded, filterRules, config.filter.replacement);
        if (verdict.blocked()) {
            event.setCancelled(true);
            ctx.messages().send(sender, "chat.filter-blocked");
            notifyStaff(sender, plain, verdict);
            return;
        }
        if (!bypass && hasWarning(verdict)) {
            notifyStaff(sender, plain, verdict);
        }
        if (!allowSpam(sender, verdict.text())) {
            event.setCancelled(true);
            return;
        }

        removeIgnoredViewers(event, senderId);
        List<String> names = viewerNames(event);
        List<String> mentioned = config.mentions.enabled
                ? MentionDetector.find(verdict.text(), names, config.mentions.requireAtSign)
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
        event.message(relayedMessage(verdict.text()));
        boolean rewritten = !verdict.text().equals(plain);
        Component message = formatter.playerMessage(sender, event.originalMessage(), verdict.text(), rewritten,
                chatColor(sender));
        Component rendered = formatter.format(sender, message, mentionPattern,
                worldNames.getOrDefault(senderId, "?"));
        // The renderer runs once per viewer, sometimes on the chat thread: it must stay free of side
        // effects. Mention sounds are played once, on the mentioned player's own region thread.
        event.renderer((source, displayName, rawMessage, viewer) -> rendered);
        for (Player target : mentionTargets) {
            playMentionSoundLater(target);
        }
    }

    /**
     * Relays the final plain text of a public message after every other listener had a chance to cancel it.
     * This is what keeps a shadow-muted or otherwise cancelled message from leaking to other servers.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChatRelay(AsyncChatEvent event) {
        ChatConfig config = config();
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        rememberWorld(event.getPlayer());
    }

    private void rememberWorld(Player player) {
        if (player.getWorld() != null) {
            worldNames.put(player.getUniqueId(), player.getWorld().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        worldNames.remove(uuid);
        spam.forget(uuid);
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
        ChatFilter.Verdict verdict = sender.hasPermission("atrium.chat.filter.bypass")
                ? new ChatFilter.Verdict(expanded, false, List.of())
                : ChatFilter.apply(expanded, filterRules, config().filter.replacement);
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
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> player = user.player();
            if (player.isEmpty() || !player.get().hasPermission("atrium.chat.filter.alert")) {
                continue;
            }
            ctx.messages().send(player.get(), "chat.filter-alert",
                    Msg.unparsed("player", sender.getName()),
                    Msg.unparsed("rules", String.join(", ", rules)),
                    Msg.unparsed("message", message));
        }
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
        ChatFilter.Verdict verdict = ChatFilter.apply(raw, filterRules, config().filter.replacement);
        if (verdict.blocked()) {
            ctx.messages().send(player, blockKey);
            notifyStaff(player, raw, verdict);
            return null;
        }
        if (hasWarning(verdict)) {
            notifyStaff(player, raw, verdict);
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

    // ---- commands ----

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
