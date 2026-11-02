package dev.yanianz.atrium.module.display;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Optional;
import org.bukkit.entity.Player;

/**
 * {@code /<command> toggle}: flips the persisted "hidden" flag of the sender on their own thread, applies it
 * right away and confirms only after the flag was saved. Messages: {@code <module>.toggle-hidden},
 * {@code <module>.toggle-shown}, {@code <module>.toggle-save-failed}.
 */
public final class ToggleCommand {
    /** Called on the player's thread after the flag changed. */
    public interface Apply {
        void apply(Player player, boolean hidden);
    }

    private final ModuleContext ctx;
    private final ToggleStore store;
    private final Apply apply;

    public ToggleCommand(ModuleContext ctx, ToggleStore store, Apply apply) {
        this.ctx = ctx;
        this.store = store;
        this.apply = apply;
    }

    public int execute(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = ctx.commands().player(c.getSource());
        String module = ctx.moduleId();
        ctx.scheduler().entity(player, () -> {
            Optional<AtriumUser> user = ctx.users().cached(player.getUniqueId());
            if (user.isEmpty()) {
                ctx.messages().send(player, "core.data-load-failed");
                return;
            }
            boolean hidden = user.get().update(store.component, t -> new ToggleStore.Toggle(!t.hidden())).hidden();
            apply.apply(player, hidden);
            ctx.users().saveNow(player.getUniqueId()).whenComplete((ignored, error) ->
                    ctx.scheduler().entity(player, () -> {
                        if (error != null) {
                            ctx.logger().warn("Saving the {} toggle of {} failed", module, player.getName(), error);
                            ctx.messages().send(player, module + ".toggle-save-failed");
                        } else {
                            ctx.messages().send(player, module + (hidden ? ".toggle-hidden" : ".toggle-shown"));
                        }
                    }, null));
        }, null);
        return Cmds.OK;
    }
}
