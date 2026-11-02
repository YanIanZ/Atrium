package dev.yanianz.atrium.core.menu;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.jspecify.annotations.Nullable;

/**
 * A 3-row yes/no dialog: confirm (left), the question with details (centre), cancel (right). After a
 * handler ran, the dialog goes back to the previous menu (or closes) unless the handler opened another
 * menu itself; prefer {@link MenuClick#replace} there so the dialog does not stay in the history.
 */
public final class ConfirmMenu {
    private ConfirmMenu() {}

    public static MenuProvider of(MenuService menus, Component title, List<Component> details, ClickHandler onConfirm,
            @Nullable ClickHandler onCancel) {
        List<Component> lines = List.copyOf(details);
        return viewer -> {
            Menu.Builder menu = Menu.builder(title, 3)
                    .item(SlotLayout.slot(1, 2), ItemBuilder.of(Material.LIME_CONCRETE)
                            .name(menus.text(viewer, "core.menu.confirm-yes")).lore(lines).build(),
                            click -> finish(click, onConfirm))
                    .item(SlotLayout.slot(1, 6), ItemBuilder.of(Material.RED_CONCRETE)
                            .name(menus.text(viewer, "core.menu.confirm-no")).build(),
                            click -> finish(click, onCancel));
            menu.item(SlotLayout.slot(1, 4), ItemBuilder.of(Material.OAK_SIGN).name(title).lore(lines).hideDetails().build());
            return menu.build();
        };
    }

    private static void finish(MenuClick click, @Nullable ClickHandler handler) {
        if (handler != null) {
            handler.onClick(click);
        }
        if (click.isStillOpen()) {
            click.back();
        }
    }
}
