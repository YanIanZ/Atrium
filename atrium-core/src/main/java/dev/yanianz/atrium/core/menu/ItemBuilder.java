package dev.yanianz.atrium.core.menu;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Builds menu icons with data components. Names and lore lines are shown without the vanilla italics;
 * lore lines without an explicit colour are gray, names without one are white.
 *
 * <pre>{@code
 * ItemStack icon = ItemBuilder.of(Material.RED_BED)
 *         .name(messages.render(player, "homes.menu.home-name", Msg.unparsed("home", home.name())))
 *         .lore(messages.render(player, "homes.menu.home-lore"))
 *         .glint(home.isDefault())
 *         .hideDetails()
 *         .build();
 * }</pre>
 */
public final class ItemBuilder {
    /** Components hidden by {@link #hideDetails()}: everything that is noise on a menu icon. */
    private static final List<DataComponentType> DETAILS = List.of(
            DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.ENCHANTMENTS,
            DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.UNBREAKABLE, DataComponentTypes.DYED_COLOR,
            DataComponentTypes.TRIM, DataComponentTypes.POTION_CONTENTS, DataComponentTypes.BANNER_PATTERNS,
            DataComponentTypes.CAN_BREAK, DataComponentTypes.CAN_PLACE_ON, DataComponentTypes.JUKEBOX_PLAYABLE,
            DataComponentTypes.FIREWORKS, DataComponentTypes.FIREWORK_EXPLOSION, DataComponentTypes.BUNDLE_CONTENTS,
            DataComponentTypes.CONTAINER, DataComponentTypes.WRITTEN_BOOK_CONTENT, DataComponentTypes.INSTRUMENT,
            DataComponentTypes.MAP_ID, DataComponentTypes.POT_DECORATIONS, DataComponentTypes.SUSPICIOUS_STEW_EFFECTS);

    private final ItemStack stack;
    private @Nullable Component name;
    private final List<Component> lore = new ArrayList<>();
    private @Nullable Boolean glint;
    private final Set<DataComponentType> hidden = new LinkedHashSet<>();
    private boolean hideTooltip;
    private CustomModelData.@Nullable Builder modelData;
    private @Nullable Key itemModel;

    private ItemBuilder(ItemStack stack) {
        this.stack = stack;
    }

    public static ItemBuilder of(Material material) {
        return new ItemBuilder(ItemStack.of(material));
    }

    public static ItemBuilder of(Material material, int amount) {
        return new ItemBuilder(ItemStack.of(material, Math.max(1, Math.min(amount, 99))));
    }

    /** Starts from a copy of an existing stack (the original is not modified). */
    public static ItemBuilder from(ItemStack stack) {
        return new ItemBuilder(stack.clone());
    }

    public ItemBuilder amount(int amount) {
        stack.setAmount(Math.max(1, Math.min(amount, stack.getMaxStackSize())));
        return this;
    }

    public ItemBuilder name(Component name) {
        this.name = name;
        return this;
    }

    /** Replaces the lore. */
    public ItemBuilder lore(List<? extends Component> lines) {
        lore.clear();
        return addLore(lines);
    }

    /** Replaces the lore. A component containing newlines is split into lines. */
    public ItemBuilder lore(Component... lines) {
        return lore(Arrays.asList(lines));
    }

    public ItemBuilder addLore(Component line) {
        return addLore(List.of(line));
    }

    /** Appends lines; components with {@code <newline>} (e.g. multi-line lang entries) are split. */
    public ItemBuilder addLore(List<? extends Component> lines) {
        for (Component line : lines) {
            lore.addAll(Lines.split(line));
        }
        return this;
    }

    public ItemBuilder glint(boolean glint) {
        this.glint = glint;
        return this;
    }

    public ItemBuilder customModelData(float value) {
        modelData().addFloat(value);
        return this;
    }

    public ItemBuilder customModelData(String value) {
        modelData().addString(value);
        return this;
    }

    /** Resource-pack item model ({@code minecraft:item_model}). */
    public ItemBuilder itemModel(Key model) {
        this.itemModel = model;
        return this;
    }

    /** Hides the given components from the tooltip. */
    public ItemBuilder hide(DataComponentType... types) {
        hidden.addAll(Arrays.asList(types));
        return this;
    }

    /** Hides attributes, enchantments, dye, trims, potion effects and similar details. */
    public ItemBuilder hideDetails() {
        hidden.addAll(DETAILS);
        return this;
    }

    /** Hides the whole tooltip (fillers, spacers). */
    public ItemBuilder hideTooltip() {
        this.hideTooltip = true;
        return this;
    }

    private CustomModelData.Builder modelData() {
        if (modelData == null) {
            modelData = CustomModelData.customModelData();
        }
        return modelData;
    }

    public ItemStack build() {
        ItemStack result = stack.clone();
        if (name != null) {
            result.setData(DataComponentTypes.CUSTOM_NAME, plain(name, NamedTextColor.WHITE));
        }
        if (!lore.isEmpty()) {
            result.setData(DataComponentTypes.LORE, ItemLore.lore(lore.stream().map(l -> plain(l, NamedTextColor.GRAY)).toList()));
        }
        if (glint != null) {
            result.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, glint);
        }
        if (modelData != null) {
            result.setData(DataComponentTypes.CUSTOM_MODEL_DATA, modelData.build());
        }
        if (itemModel != null) {
            result.setData(DataComponentTypes.ITEM_MODEL, itemModel);
        }
        if (hideTooltip || !hidden.isEmpty()) {
            result.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
                    .hideTooltip(hideTooltip)
                    .hiddenComponents(Set.copyOf(hidden))
                    .build());
        }
        return result;
    }

    /** Removes the default italics and gives uncoloured text a base colour. */
    static Component plain(Component component, NamedTextColor base) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE).colorIfAbsent(base);
    }
}
