package dev.yanianz.atrium.core.menu;

/**
 * Reacts to a click on a menu button. Runs on the viewer's own thread (Folia: the player's entity
 * scheduler), one tick after the click, and only if the menu is still open. The click itself is always
 * cancelled; a handler never needs to cancel anything. Exceptions are logged and play the error sound.
 */
@FunctionalInterface
public interface ClickHandler {
    void onClick(MenuClick click);
}
