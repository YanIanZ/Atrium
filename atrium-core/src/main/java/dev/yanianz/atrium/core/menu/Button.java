package dev.yanianz.atrium.core.menu;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * An icon in a menu plus what happens when it is clicked. Immutable: {@code on...} methods return a copy.
 *
 * <p>The handler given to {@link #of(ItemStack, ClickHandler)} reacts to left, right and shift clicks.
 * Other click types (middle, number keys, drop, double click) only fire handlers registered for them
 * explicitly with {@link #on(ClickType, ClickHandler)}, so a double click never triggers an action twice.
 */
public final class Button {
    private static final java.util.Set<ClickType> COMMON =
            java.util.EnumSet.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT, ClickType.SHIFT_RIGHT);

    private final ItemStack icon;
    private final Map<ClickType, ClickHandler> handlers;
    private final @Nullable ClickHandler common;
    private final boolean silent;

    private Button(ItemStack icon, Map<ClickType, ClickHandler> handlers, @Nullable ClickHandler common, boolean silent) {
        this.icon = icon;
        this.handlers = handlers;
        this.common = common;
        this.silent = silent;
    }

    /** A display-only icon. */
    public static Button of(ItemStack icon) {
        return new Button(icon.clone(), new EnumMap<>(ClickType.class), null, false);
    }

    /** An icon that reacts to left, right and shift clicks. */
    public static Button of(ItemStack icon, ClickHandler onClick) {
        return new Button(icon.clone(), new EnumMap<>(ClickType.class), onClick, false);
    }

    /** A copy that also handles one click type (overrides the common handler for that type). */
    public Button on(ClickType type, ClickHandler handler) {
        Map<ClickType, ClickHandler> copy = new EnumMap<>(ClickType.class);
        copy.putAll(handlers);
        copy.put(type, handler);
        return new Button(icon, copy, common, silent);
    }

    public Button onLeft(ClickHandler handler) {
        return on(ClickType.LEFT, handler);
    }

    public Button onRight(ClickHandler handler) {
        return on(ClickType.RIGHT, handler);
    }

    public Button onShiftLeft(ClickHandler handler) {
        return on(ClickType.SHIFT_LEFT, handler);
    }

    public Button onShiftRight(ClickHandler handler) {
        return on(ClickType.SHIFT_RIGHT, handler);
    }

    /** A copy that does not play the theme click sound. */
    public Button silent() {
        return new Button(icon, handlers, common, true);
    }

    public ItemStack icon() {
        return icon.clone();
    }

    ItemStack iconUnsafe() {
        return icon;
    }

    boolean isSilent() {
        return silent;
    }

    /** The handler for a click type, or null if the button ignores it. */
    public @Nullable ClickHandler handler(ClickType type) {
        ClickHandler specific = handlers.get(type);
        if (specific != null) {
            return specific;
        }
        return COMMON.contains(type) ? common : null;
    }
}
