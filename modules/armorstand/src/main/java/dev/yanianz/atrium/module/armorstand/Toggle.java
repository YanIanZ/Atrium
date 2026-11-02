package dev.yanianz.atrium.module.armorstand;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;

/** Boolean armor stand properties the editor can flip. */
enum Toggle {
    ARMS(Material.STICK, ArmorStand::hasArms, ArmorStand::setArms),
    BASE_PLATE(Material.SMOOTH_STONE_SLAB, ArmorStand::hasBasePlate, ArmorStand::setBasePlate),
    SMALL(Material.RABBIT_FOOT, ArmorStand::isSmall, ArmorStand::setSmall),
    GRAVITY(Material.FEATHER, ArmorStand::hasGravity, ArmorStand::setGravity),
    VISIBLE(Material.GLASS, ArmorStand::isVisible, ArmorStand::setVisible),
    GLOWING(Material.GLOWSTONE_DUST, ArmorStand::isGlowing, ArmorStand::setGlowing),
    INVULNERABLE(Material.OBSIDIAN, ArmorStand::isInvulnerable, ArmorStand::setInvulnerable),
    NAME_VISIBLE(Material.NAME_TAG, ArmorStand::isCustomNameVisible, ArmorStand::setCustomNameVisible);

    final Material icon;
    private final Predicate<ArmorStand> getter;
    private final BiConsumer<ArmorStand, Boolean> setter;

    Toggle(Material icon, Predicate<ArmorStand> getter, BiConsumer<ArmorStand, Boolean> setter) {
        this.icon = icon;
        this.getter = getter;
        this.setter = setter;
    }

    String key() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    boolean get(ArmorStand stand) {
        return getter.test(stand);
    }

    void set(ArmorStand stand, boolean value) {
        setter.accept(stand, value);
    }

    static Set<Toggle> enabled(ArmorStand stand) {
        EnumSet<Toggle> set = EnumSet.noneOf(Toggle.class);
        for (Toggle toggle : values()) {
            if (toggle.get(stand)) {
                set.add(toggle);
            }
        }
        return set;
    }

    static void applyAll(ArmorStand stand, Set<Toggle> enabled) {
        for (Toggle toggle : values()) {
            toggle.set(stand, enabled.contains(toggle));
        }
    }
}
