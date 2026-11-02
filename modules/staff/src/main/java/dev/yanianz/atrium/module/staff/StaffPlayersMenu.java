package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

/** Paged online-player list plus a small action menu for the chosen player. */
final class StaffPlayersMenu {
    private final ModuleContext ctx;
    private final StaffManager manager;

    StaffPlayersMenu(ModuleContext ctx, StaffManager manager) {
        this.ctx = ctx;
        this.manager = manager;
    }

    void open(Player viewer) {
        ctx.menus().open(viewer, PagedMenu.builder(ctx.menus(),
                        ctx.messages().render(viewer, "staff.menu-title"))
                .content(player -> buttons(player))
                .build());
    }

    private List<Button> buttons(Player viewer) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        List<Button> buttons = new ArrayList<>(online.size());
        for (Player target : online) {
            if (target.equals(viewer)) {
                continue;
            }
            String name = target.getName();
            buttons.add(Button.of(head(viewer, target), click -> {
                Player current = Bukkit.getPlayerExact(name);
                if (current == null || !current.isOnline()) {
                    ctx.messages().send(click.player(), "staff.player-gone", Msg.unparsed("player", name));
                    click.error();
                    return;
                }
                click.open(actions(click.player(), current));
            }));
        }
        return buttons;
    }

    private ItemStack head(Player viewer, Player target) {
        ItemStack stack = ItemStack.of(Material.PLAYER_HEAD);
        if (stack.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(target);
            stack.setItemMeta(skull);
        }
        return ItemBuilder.from(stack)
                .name(ctx.messages().render(viewer, "staff.menu-player-name",
                        Msg.unparsed("player", target.getName())))
                .lore(ctx.messages().render(viewer, "staff.menu-player-lore",
                        Msg.unparsed("player", target.getName())))
                .hideDetails()
                .build();
    }

    private Menu actions(Player viewer, Player target) {
        Component title = ctx.messages().render(viewer, "staff.menu-actions-title",
                Msg.unparsed("player", target.getName()));
        Menu.Builder builder = Menu.builder(title, 3);
        builder.button(10, Button.of(icon(viewer, Material.ENDER_PEARL, "staff.menu-action-teleport"),
                click -> teleport(click, target)));
        builder.button(12, Button.of(icon(viewer, Material.SPYGLASS, "staff.menu-action-invsee"),
                click -> {
                    click.close();
                    manager.runCommand(click.player(), "invsee", target);
                }));
        builder.button(14, Button.of(icon(viewer, Material.ENDER_CHEST, "staff.menu-action-enderchest"),
                click -> {
                    click.close();
                    manager.enderchest(click.player(), target);
                }));
        builder.button(16, Button.of(icon(viewer, Material.PACKED_ICE, "staff.menu-action-freeze"),
                click -> {
                    click.close();
                    manager.freeze(click.player(), target);
                }));
        return builder.build();
    }

    private ItemStack icon(Player viewer, Material material, String key) {
        return ItemBuilder.of(material)
                .name(ctx.messages().render(viewer, key))
                .lore(ctx.messages().render(viewer, key + "-lore"))
                .hideDetails()
                .build();
    }

    private void teleport(MenuClick click, Player target) {
        Player viewer = click.player();
        click.close();
        ctx.scheduler().supplyOnEntity(target, target::getLocation)
                .thenAccept(location -> ctx.scheduler().entity(viewer,
                        () -> viewer.teleportAsync(location).whenComplete((moved, error) -> {
                            if (error != null || !Boolean.TRUE.equals(moved)) {
                                ctx.logger().warn("Staff teleport of {} failed", viewer.getName(), error);
                                ctx.scheduler().entity(viewer,
                                        () -> ctx.messages().send(viewer, "staff.random-failed"), null);
                            }
                        }), null))
                .exceptionally(error -> {
                    if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)) {
                        ctx.logger().warn("Resolving a staff teleport target failed", error);
                    }
                    ctx.scheduler().entity(viewer, () -> ctx.messages().send(viewer, "staff.player-gone",
                            Msg.unparsed("player", target.getName())), null);
                    return null;
                });
    }
}
