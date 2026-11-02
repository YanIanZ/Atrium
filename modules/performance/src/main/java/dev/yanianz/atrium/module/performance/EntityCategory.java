package dev.yanianz.atrium.module.performance;

import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Monster;

/** The four categories the entity limiter understands. */
enum EntityCategory {
    ANIMALS,
    MONSTERS,
    ITEMS,
    MINECARTS;

    static EntityCategory of(Entity entity) {
        if (entity == null) {
            return null;
        }
        // Items and minecarts are checked first: they are never animals or monsters, but this
        // keeps the category decision obvious if a future entity type implements several interfaces.
        if (entity instanceof Item) {
            return ITEMS;
        }
        if (entity instanceof Minecart) {
            return MINECARTS;
        }
        if (entity instanceof Monster) {
            return MONSTERS;
        }
        if (entity instanceof Animals) {
            return ANIMALS;
        }
        return null;
    }

    boolean matches(Entity entity) {
        return of(entity) == this;
    }
}
