package dev.yanianz.atrium.module.nick;

import dev.yanianz.atrium.api.nick.NickService;
import dev.yanianz.atrium.api.tablist.TabListService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Nicknames: validation, uniqueness against names and other nicknames, restricted colour permissions,
 * display-name/player-list application and {@code /realname}.
 */
public final class NickModule implements AtriumModule, NickService, Listener {
    static final String PERM_NICK = "atrium.nick";
    static final String PERM_OTHERS = "atrium.nick.others";
    static final String PERM_COLOR = "atrium.nick.color";
    static final String PERM_FORMAT = "atrium.nick.format";
    static final String PERM_GRADIENT = "atrium.nick.gradient";
    static final String PERM_REALNAME = "atrium.realname";

    private ModuleContext ctx;
    private ConfigHandle<NickConfig> config;
    private NickRules rules;
    private NickStore store;
    private NickRenderer renderer;
    private volatile boolean stopping;

    @Override
    public String id() {
        return "nick";
    }

    @Override
    public String description() {
        return "Nicknames with validation, colour permissions and /realname lookup";
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.stopping = false;
        this.config = context.config(NickConfig.class, NickConfig::new);
        this.rules = NickRules.from(config.get(), message -> context.logger().warn(message));
        context.migrate(NickStore.MIGRATIONS);
        this.store = new NickStore(context.database());
        this.renderer = new NickRenderer();
        context.component(store.component);
        context.listen(this);

        context.permission(PERM_NICK, PermissionDefault.TRUE, "Set, clear or display your own nickname");
        context.permission(PERM_OTHERS, PermissionDefault.OP, "Set or clear another player's nickname");
        context.permission(PERM_COLOR, PermissionDefault.OP, "Use colour tags in nicknames");
        context.permission(PERM_FORMAT, PermissionDefault.OP, "Use decoration tags in nicknames");
        context.permission(PERM_GRADIENT, PermissionDefault.OP, "Use gradient tags in nicknames");
        context.permission(PERM_REALNAME, PermissionDefault.TRUE, "Find a player by nickname");

        NickCommands commands = new NickCommands(this);
        commands.register();
        context.track(commands);

        // The component is loaded asynchronously for players who were already online; retry briefly.
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            context.scheduler().entityLater(player, () -> applyStored(player, 3), null, 20L);
        }
        context.service(NickService.class, this);
    }

    // ---- NickService (administrative import/maintenance access) ----

    @Override
    public CompletableFuture<Optional<NickInfo>> nick(UUID player) {
        return stateOf(player).thenApply(state -> state == null || !state.hasNick()
                ? Optional.<NickInfo>empty()
                : Optional.of(new NickInfo(state.nick(), state.plain(), state.updatedAt())));
    }

    @Override
    public CompletableFuture<Result> setNick(UUID player, String nickname, boolean overwrite) {
        if (player == null || nickname == null || nickname.isBlank()) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        Component parsed;
        try {
            parsed = renderer.typed(nickname, true, true, true);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        String plain = renderer.plain(parsed);
        if (NickLogic.validate(plain, rules) != null) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        NickState next = NickState.of(nickname, plain);
        return stateOf(player).thenCompose(current -> {
            if (current != null && current.hasNick() && !overwrite) {
                return CompletableFuture.completedFuture(Result.SKIPPED);
            }
            CompletableFuture<Boolean> nameAllowed = config.get().blockRealNames
                    ? ctx.users().resolveAccount(plain, false).thenApply(found ->
                            found.isEmpty() || (found.get().uuid().equals(player) && config.get().allowOwnName))
                    : CompletableFuture.completedFuture(true);
            return nameAllowed.thenCompose(allowed -> {
                if (!allowed) {
                    return CompletableFuture.completedFuture(Result.TAKEN);
                }
                return store.claim(player, next).thenCompose(claimed -> {
                    if (claimed == NickStore.ClaimResult.TAKEN) {
                        return CompletableFuture.completedFuture(Result.TAKEN);
                    }
                    return applyImportedState(player, next).thenApply(ignored -> Result.SUCCESS);
                });
            });
        });
    }

    @Override
    public CompletableFuture<Result> clearNick(UUID player) {
        return store.release(player).thenCompose(ignored -> applyImportedState(player, NickState.EMPTY)
                .thenApply(done -> Result.SUCCESS));
    }

    @Override
    public Result validate(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return Result.INVALID;
        }
        try {
            String plain = renderer.plain(renderer.typed(nickname, true, true, true));
            return NickLogic.validate(plain, rules) == null ? Result.SUCCESS : Result.INVALID;
        } catch (RuntimeException e) {
            return Result.INVALID;
        }
    }

    private CompletableFuture<NickState> stateOf(UUID player) {
        Optional<AtriumUser> cached = ctx.users().cached(player);
        if (cached.isPresent()) {
            try {
                return CompletableFuture.completedFuture(cached.get().get(store.component));
            } catch (IllegalStateException e) {
                // Still loading; read the stored row directly.
            }
        }
        return store.component.loader().load(player);
    }

    /** Updates a loaded user's in-memory nickname; an unloaded player only needs the row already written. */
    private CompletableFuture<Void> applyImportedState(UUID player, NickState state) {
        Optional<AtriumUser> cached = ctx.users().cached(player);
        if (cached.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            cached.get().update(store.component, current -> state);
            return ctx.users().saveNow(player);
        } catch (IllegalStateException e) {
            ctx.logger().warn("Nickname of {} is still not loaded; the stored row is authoritative", player, e);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override
    public void reload() {
        this.rules = NickRules.from(config.get(), message -> ctx.logger().warn(message));
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            ctx.scheduler().entity(player, () -> applyStored(player, 1), null);
        }
    }

    @Override
    public void disable() {
        stopping = true;
        if (ctx == null || config == null) {
            return;
        }
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            try {
                ctx.scheduler().entity(player, () -> reset(player), null);
            } catch (RuntimeException e) {
                ctx.logger().debug("Could not reset the nickname of {} during disable", player.getName(), e);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (stopping || !config.get().restoreOnJoin) {
            return;
        }
        Player player = event.getPlayer();
        // One tick later so a later join listener cannot overwrite the restored name.
        ctx.scheduler().entityLater(player, () -> applyStored(player, 1), null, 1L);
    }

    ModuleContext context() {
        return ctx;
    }

    ConfigHandle<NickConfig> configHandle() {
        return config;
    }

    NickRules rules() {
        return rules;
    }

    NickRenderer renderer() {
        return renderer;
    }

    NickStore store() {
        return store;
    }

    NickState state(Player player) {
        return ctx.users().user(player).get(store.component);
    }

    NickState stateOrNull(Player player) {
        try {
            return state(player);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    void saveState(Player player, NickState state) {
        ctx.users().user(player).update(store.component, current -> state);
    }

    void clearState(Player player) {
        ctx.users().user(player).update(store.component, current -> NickState.EMPTY);
    }

    Component display(Player player, NickState state) {
        return render(state, player.hasPermission(PERM_COLOR), player.hasPermission(PERM_FORMAT),
                player.hasPermission(PERM_GRADIENT));
    }

    Component render(NickState state, boolean color, boolean format, boolean gradient) {
        return NickRenderer.decorate(config.get().prefix, renderer.stored(state, color, format, gradient));
    }

    void apply(Player player, Component displayName) {
        player.displayName(displayName);
        if (updatesListName()) {
            player.playerListName(displayName);
        }
        renderTabList(player);
    }

    void reset(Player player) {
        player.displayName(null);
        if (updatesListName()) {
            player.playerListName(null);
        }
        renderTabList(player);
    }

    /**
     * The tablist module renders list names from the display name (also through the TAB plugin): ask it to show
     * the new name now instead of on its next update.
     */
    private void renderTabList(Player player) {
        ctx.services().get(TabListService.class).ifPresent(tabList -> tabList.requestRender(player.getUniqueId()));
    }

    private void applyStored(Player player, int retries) {
        if (stopping || !player.isOnline()) {
            return;
        }
        NickState state;
        try {
            state = state(player);
        } catch (IllegalStateException e) {
            if (retries > 0) {
                ctx.scheduler().entityLater(player, () -> applyStored(player, retries - 1), null, 20L);
            } else {
                ctx.logger().warn("Nickname of {} is still not loaded; not restored", player.getName());
            }
            return;
        }
        if (!state.hasNick()) {
            return;
        }
        try {
            apply(player, display(player, state));
        } catch (RuntimeException e) {
            ctx.logger().error("Restoring the nickname of {} failed", player.getName(), e);
        }
    }

    /**
     * The tablist module formats list names itself (using the display name, natively or through the TAB plugin),
     * so do not fight over them.
     */
    private boolean updatesListName() {
        return config.get().updatePlayerListName
                && !dev.yanianz.atrium.api.Atrium.api().enabledModules().contains("tablist");
    }
}
