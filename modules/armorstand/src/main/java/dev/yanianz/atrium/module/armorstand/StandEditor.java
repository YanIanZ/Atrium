package dev.yanianz.atrium.module.armorstand;

import dev.yanianz.atrium.api.armorstand.ArmorStandEditEvent.Action;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.ClickHandler;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuProvider;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.EulerAngle;

/**
 * Chest GUI editor built on {@code ctx.menus()}: every click and drag is cancelled by the menu service, the
 * icons are display copies, and handlers run one tick later on the viewer's thread. Each handler re-checks
 * that the stand is still valid, in reach and owned by that region before it changes anything.
 */
final class StandEditor {
    static final int ROWS = 4;
    private static final int INFO = 8;
    private static final int PART_FIRST = 9;
    private static final int STEP = 16;
    private static final int RESET = 17;
    private static final int AXIS_FIRST = 18;
    private static final int EQUIPMENT_FIRST = 27;
    private static final int CLOSE = 35;
    private static final EquipmentSlot[] EQUIPMENT = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND
    };
    private static final Material[] PART_ICONS = {
        Material.LEATHER_HELMET, Material.LEATHER_CHESTPLATE, Material.STICK, Material.STICK,
        Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS
    };

    private final ModuleContext ctx;
    private final ConfigHandle<ArmorStandConfig> config;
    private final StandAccess access;

    /** A change made by a button; returns whether the menu should be re-rendered. */
    @FunctionalInterface
    private interface Edit {
        boolean apply(Player player);
    }

    StandEditor(ModuleContext ctx, ConfigHandle<ArmorStandConfig> config, StandAccess access) {
        this.ctx = ctx;
        this.config = config;
        this.access = access;
    }

    /** Viewer's thread; the stand must already be checked with {@link StandAccess#find}. */
    void open(Player viewer, ArmorStand stand) {
        if (!access.permitted(viewer, stand, Action.OPEN, "")) {
            ctx.messages().send(viewer, "armorstand.denied");
            return;
        }
        ctx.menus().open(viewer, new Editor(stand));
    }

    /** Per-viewer editor state. Only touched on the viewer's thread (render and click handlers). */
    private final class Editor implements MenuProvider {
        final ArmorStand stand;
        Pose.Part part = Pose.Part.HEAD;
        int stepIndex;

        Editor(ArmorStand stand) {
            this.stand = stand;
        }

        double step() {
            List<Double> steps = config.get().steps();
            stepIndex = Math.floorMod(stepIndex, steps.size());
            return steps.get(stepIndex);
        }

        /** Wraps an edit with the reach/ownership re-check; closes the editor when the stand is gone. */
        ClickHandler guarded(Edit edit) {
            return click -> {
                Player p = click.player();
                if (!p.hasPermission("atrium.armorstand") || !access.usable(p, stand)) {
                    ctx.messages().send(p, "armorstand.stand-gone");
                    click.close();
                    return;
                }
                if (edit.apply(p)) {
                    click.refresh();
                } else {
                    click.error();
                }
            };
        }

        @Override
        public Menu render(Player p) {
            Menu.Builder menu = Menu.builder(text(p, "armorstand.gui.title"), ROWS);
            menu.item(CLOSE, ItemBuilder.of(Material.OAK_DOOR).name(text(p, "armorstand.gui.close")).build(),
                    click -> click.close());
            if (!stand.isValid() || !Bukkit.isOwnedByCurrentRegion(stand)) {
                // Never read a stand this thread does not own; the next click closes the editor.
                menu.item(INFO, ItemBuilder.of(Material.BARRIER).name(text(p, "armorstand.gui.gone")).build());
                return menu.build();
            }
            Toggle[] toggles = Toggle.values();
            for (int i = 0; i < toggles.length; i++) {
                Toggle toggle = toggles[i];
                boolean on = toggle.get(stand);
                menu.item(i, ItemBuilder.of(on ? toggle.icon : Material.GRAY_DYE)
                        .name(text(p, "armorstand.gui.toggle",
                                Msg.component("name", text(p, "armorstand.toggle." + toggle.key())),
                                Msg.component("state", text(p, on ? "armorstand.gui.on" : "armorstand.gui.off"))))
                        .lore(text(p, "armorstand.gui.toggle-hint"))
                        .glint(on).hideDetails().build(), guarded(player -> {
                            if (!access.permitted(player, stand, Action.TOGGLE, toggle.key())) {
                                deny(player);
                                return false;
                            }
                            toggle.set(stand, !toggle.get(stand));
                            return true;
                        }));
            }
            String stepText = format(step());
            Component partName = text(p, "armorstand.part." + part.key());
            menu.item(INFO, ItemBuilder.of(Material.ARMOR_STAND)
                    .name(text(p, "armorstand.gui.info", Msg.component("part", partName), Msg.unparsed("step", stepText)))
                    .lore(text(p, "armorstand.gui.info-lore", Msg.unparsed("step", stepText)))
                    .build());
            Pose.Part[] parts = Pose.Part.values();
            for (int i = 0; i < parts.length; i++) {
                Pose.Part candidate = parts[i];
                boolean selected = candidate == part;
                menu.item(PART_FIRST + i, ItemBuilder.of(PART_ICONS[i])
                        .name(text(p, "armorstand.gui.part",
                                Msg.component("part", text(p, "armorstand.part." + candidate.key()))))
                        .lore(text(p, selected ? "armorstand.gui.part-selected" : "armorstand.gui.part-hint"))
                        .glint(selected).hideDetails().build(), guarded(player -> {
                            part = candidate;
                            return true;
                        }));
            }
            menu.item(STEP, ItemBuilder.of(Material.CLOCK)
                    .name(text(p, "armorstand.gui.step", Msg.unparsed("step", stepText)))
                    .lore(text(p, "armorstand.gui.step-hint")).build(), click -> guarded(player -> {
                        stepIndex += click.isRight() ? -1 : 1;
                        return true;
                    }).onClick(click));
            menu.item(RESET, ItemBuilder.of(Material.BARRIER)
                    .name(text(p, "armorstand.gui.reset", Msg.component("part", partName)))
                    .lore(text(p, "armorstand.gui.reset-hint")).build(), guarded(player -> {
                        if (!access.permitted(player, stand, Action.POSE, part.key())) {
                            deny(player);
                            return false;
                        }
                        part.set(stand, Pose.DEFAULT.get(part).toEuler());
                        return true;
                    }));
            Pose.Angles angles = Pose.Angles.of(part.get(stand));
            Pose.Axis[] axes = Pose.Axis.values();
            for (int i = 0; i < axes.length; i++) {
                Pose.Axis axis = axes[i];
                int base = AXIS_FIRST + i * 3;
                TagResolver tags = TagResolver.resolver(Msg.unparsed("axis", axis.name()), Msg.unparsed("step", stepText),
                        Msg.unparsed("value", format(angles.get(axis))), Msg.component("part", partName));
                menu.item(base, ItemBuilder.of(Material.RED_STAINED_GLASS_PANE)
                        .name(text(p, "armorstand.gui.axis-minus", tags)).build(), rotate(axis, -1));
                menu.item(base + 1, ItemBuilder.of(Material.PAPER)
                        .name(text(p, "armorstand.gui.axis-value", tags))
                        .lore(text(p, "armorstand.gui.axis-value-hint", tags)).build(), rotate(axis, 0));
                menu.item(base + 2, ItemBuilder.of(Material.LIME_STAINED_GLASS_PANE)
                        .name(text(p, "armorstand.gui.axis-plus", tags)).build(), rotate(axis, 1));
            }
            boolean swap = config.get().equipmentSwap && p.hasPermission("atrium.armorstand.equipment");
            for (int i = 0; i < EQUIPMENT.length; i++) {
                EquipmentSlot slot = EQUIPMENT[i];
                TagResolver slotName = Msg.component("slot",
                        text(p, "armorstand.slot." + slot.name().toLowerCase(Locale.ROOT)));
                ItemStack worn = stand.getEquipment().getItem(slot);
                // A display copy: the menu service clones it again and never lets it be taken.
                ItemBuilder icon = worn.isEmpty() ? ItemBuilder.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                        : ItemBuilder.from(worn).amount(worn.getAmount());
                icon.name(text(p, swap ? "armorstand.gui.equipment" : "armorstand.gui.equipment-locked", slotName))
                        .lore(text(p, swap ? "armorstand.gui.equipment-hint" : "armorstand.gui.equipment-locked-hint"));
                menu.item(EQUIPMENT_FIRST + i, icon.build(), guarded(player -> swapEquipment(player, stand, slot)));
            }
            return menu.build();
        }

        /** {@code direction} -1/+1 rotates by one step, 0 sets the axis to zero. */
        private ClickHandler rotate(Pose.Axis axis, int direction) {
            return guarded(player -> {
                if (!access.permitted(player, stand, Action.POSE, part.key())) {
                    deny(player);
                    return false;
                }
                EulerAngle current = part.get(stand);
                EulerAngle next = direction == 0 ? switch (axis) {
                    case X -> current.setX(0);
                    case Y -> current.setY(0);
                    case Z -> current.setZ(0);
                } : PoseMath.rotate(current, axis, direction * step());
                part.set(stand, next);
                return true;
            });
        }
    }

    private Component text(Player p, String key, TagResolver... tags) {
        return ctx.messages().render(p, key, tags);
    }

    static String format(double degrees) {
        return java.math.BigDecimal.valueOf(degrees).stripTrailingZeros().toPlainString();
    }

    /**
     * Swaps exactly the main-hand item (never the off hand) with the stand's slot. Both sides are read and
     * written in the same tick on the thread owning both, so nothing can be duplicated or lost.
     */
    private boolean swapEquipment(Player p, ArmorStand stand, EquipmentSlot slot) {
        if (!config.get().equipmentSwap || !p.hasPermission("atrium.armorstand.equipment")) {
            ctx.messages().send(p, "armorstand.no-permission-equipment");
            return false;
        }
        if (stand.getDisabledSlots().contains(slot)) {
            ctx.messages().send(p, "armorstand.slot-locked");
            return false;
        }
        PlayerInventory inventory = p.getInventory();
        ItemStack hand = inventory.getItemInMainHand();
        ItemStack worn = stand.getEquipment().getItem(slot);
        if (hand.isEmpty() && worn.isEmpty()) {
            return true;
        }
        if (!access.permitted(p, stand, Action.EQUIPMENT, slot.name().toLowerCase(Locale.ROOT))) {
            deny(p);
            return false;
        }
        ItemStack toStand = hand.isEmpty() ? null : hand.clone();
        ItemStack toHand = worn.isEmpty() ? null : worn.clone();
        stand.getEquipment().setItem(slot, toStand);
        inventory.setItemInMainHand(toHand);
        return true;
    }

    private void deny(Player p) {
        ctx.messages().send(p, "armorstand.denied");
    }
}
