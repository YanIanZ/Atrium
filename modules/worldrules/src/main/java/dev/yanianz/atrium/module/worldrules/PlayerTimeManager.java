package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Applies and persists {@code /ptime}. Every method must run on the player's own thread; the value is
 * loaded with the rest of the user data before {@code PlayerJoinEvent}.
 */
final class PlayerTimeManager {
    static final long DAY_LENGTH = 24000L;

    private final ModuleContext ctx;
    private final WorldRulesStore store;

    PlayerTimeManager(ModuleContext ctx, WorldRulesStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    /** Re-applies the stored time after a login or a module reload. */
    void apply(Player player) {
        WorldRulesStore.PlayerTime value = stored(player);
        if (!value.active()) {
            return;
        }
        player.setPlayerTime(value.ticks(), value.relative());
    }

    void set(Player player, long ticks) {
        long value = Math.floorMod(ticks, DAY_LENGTH);
        player.setPlayerTime(value, false);
        save(player, new WorldRulesStore.PlayerTime(true, value, false));
    }

    void freeze(Player player, Long ticks) {
        long value = ticks == null ? Math.floorMod(player.getPlayerTime(), DAY_LENGTH) : Math.floorMod(ticks, DAY_LENGTH);
        player.setPlayerTime(value, false);
        save(player, new WorldRulesStore.PlayerTime(true, value, false));
    }

    /** Keeps the current visible time while letting the world clock keep running. */
    void unfreeze(Player player) {
        long visible = player.getPlayerTime();
        long world = player.getWorld().getTime();
        long offset = visible - world;
        player.setPlayerTime(offset, true);
        save(player, new WorldRulesStore.PlayerTime(true, offset, true));
    }

    void reset(Player player) {
        player.resetPlayerTime();
        save(player, WorldRulesStore.PlayerTime.NOT_SET);
    }

    WorldRulesStore.PlayerTime stored(Player player) {
        return ctx.users().user(player).get(store.component);
    }

    private void save(Player player, WorldRulesStore.PlayerTime value) {
        UUID id = player.getUniqueId();
        ctx.users().user(player).update(store.component, ignored -> value);
        ctx.users().saveNow(id).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Saving the player time of {} failed", player.getName(), error);
            }
        });
    }
}
