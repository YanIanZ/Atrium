package dev.yanianz.atrium.core.menu;

import org.bukkit.entity.Player;

/**
 * Builds a menu for a viewer. Providers are kept in the back-navigation history and called again on
 * {@link MenuService#back} and {@link MenuService#refresh}, so a provider that reads live data always shows
 * fresh content. Called on the viewer's thread. A fixed {@link Menu} is its own provider.
 */
@FunctionalInterface
public interface MenuProvider {
    Menu render(Player viewer);
}
