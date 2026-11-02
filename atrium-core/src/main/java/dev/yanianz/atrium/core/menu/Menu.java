package dev.yanianz.atrium.core.menu;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * One rendered chest menu: title, size, buttons and (optional) input slots. Build with
 * {@link #builder(Component, int)} and show with {@link MenuService#open}.
 *
 * <p>Every click and drag is cancelled except plain item moves into or out of declared input slots
 * (see {@link ClickPolicy}). Empty non-input slots are filled with the theme filler unless disabled.
 */
public final class Menu implements MenuProvider {
    private final Component title;
    private final int rows;
    private final Map<Integer, Button> buttons;
    private final Set<Integer> inputSlots;
    private final boolean playerInventoryInput;
    private final boolean filler;
    private final @Nullable ItemStack fillerItem;
    private final @Nullable CloseHandler onClose;

    private Menu(Builder b) {
        this.title = b.title;
        this.rows = b.rows;
        this.buttons = Collections.unmodifiableMap(new HashMap<>(b.buttons));
        this.inputSlots = Collections.unmodifiableSet(new LinkedHashSet<>(b.inputSlots));
        this.playerInventoryInput = b.playerInventoryInput;
        this.filler = b.filler;
        this.fillerItem = b.fillerItem == null ? null : b.fillerItem.clone();
        this.onClose = b.onClose;
    }

    public static Builder builder(Component title, int rows) {
        return new Builder(title, rows);
    }

    @Override
    public Menu render(Player viewer) {
        return this;
    }

    public Component title() {
        return title;
    }

    public int rows() {
        return rows;
    }

    public int size() {
        return rows * SlotLayout.COLUMNS;
    }

    public Map<Integer, Button> buttons() {
        return buttons;
    }

    public @Nullable Button button(int slot) {
        return buttons.get(slot);
    }

    public Set<Integer> inputSlots() {
        return inputSlots;
    }

    public boolean playerInventoryInput() {
        return playerInventoryInput;
    }

    public boolean filler() {
        return filler;
    }

    @Nullable ItemStack fillerItem() {
        return fillerItem;
    }

    @Nullable CloseHandler onClose() {
        return onClose;
    }

    public static final class Builder {
        private final Component title;
        private final int rows;
        private final Map<Integer, Button> buttons = new HashMap<>();
        private final Set<Integer> inputSlots = new LinkedHashSet<>();
        private boolean playerInventoryInput;
        private boolean filler = true;
        private @Nullable ItemStack fillerItem;
        private @Nullable CloseHandler onClose;

        private Builder(Component title, int rows) {
            this.title = title;
            this.rows = SlotLayout.checkRows(rows);
        }

        private int check(int slot) {
            if (slot < 0 || slot >= rows * SlotLayout.COLUMNS) {
                throw new IllegalArgumentException("Slot " + slot + " is outside a " + rows + "-row menu");
            }
            return slot;
        }

        public Builder button(int slot, Button button) {
            buttons.put(check(slot), button);
            inputSlots.remove(slot);
            return this;
        }

        public Builder button(int row, int column, Button button) {
            return button(SlotLayout.slot(row, column), button);
        }

        /** Display-only icon. */
        public Builder item(int slot, ItemStack icon) {
            return button(slot, Button.of(icon));
        }

        /** Icon with a handler for left, right and shift clicks. */
        public Builder item(int slot, ItemStack icon, ClickHandler onClick) {
            return button(slot, Button.of(icon, onClick));
        }

        /** Puts the same button in several slots (borders, spacers). */
        public Builder fill(int[] slots, Button button) {
            for (int slot : slots) {
                button(slot, button);
            }
            return this;
        }

        /**
         * Declares slots where players may put and take items. Read them in {@link #onClose} or a button
         * handler; whatever is left there when the menu closes is given back to the viewer.
         */
        public Builder input(int... slots) {
            for (int slot : slots) {
                check(slot);
                buttons.remove(slot);
                inputSlots.add(slot);
            }
            return this;
        }

        /**
         * Allows simple clicks (pick up / place / swap, no shift-click) inside the viewer's own inventory so
         * items can be moved into input slots. Only has an effect when the menu has input slots.
         */
        public Builder playerInventoryInput(boolean allow) {
            this.playerInventoryInput = allow;
            return this;
        }

        /** Fill empty slots with the theme filler (default: true). */
        public Builder filler(boolean enabled) {
            this.filler = enabled;
            return this;
        }

        /** Fill empty slots with this item instead of the theme filler. */
        public Builder filler(ItemStack item) {
            this.filler = true;
            this.fillerItem = item.clone();
            return this;
        }

        public Builder onClose(CloseHandler handler) {
            this.onClose = handler;
            return this;
        }

        public Menu build() {
            return new Menu(this);
        }
    }
}
