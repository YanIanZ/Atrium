package dev.yanianz.atrium.module.mobs;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.EntityType;

/** Entity type lookup and permission node names. */
final class EntityTypes {
    private EntityTypes() {}

    static Registry<EntityType> registry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE);
    }

    static Optional<EntityType> parse(String token) {
        NamespacedKey key = NamespacedKey.fromString(token.toLowerCase(Locale.ROOT));
        return key == null ? Optional.empty() : Optional.ofNullable(registry().get(key));
    }

    /** Types /spawnmob may create: spawnable, never players. */
    static boolean spawnable(EntityType type) {
        return type != EntityType.PLAYER && type.isSpawnable();
    }

    /** Types a spawner may be set to: spawnable living entities. */
    static boolean spawnerType(EntityType type) {
        return spawnable(type) && type.isAlive();
    }

    static List<EntityType> spawnableTypes() {
        return registry().stream().filter(EntityTypes::spawnable).toList();
    }

    /** {@code zombie} for minecraft types, {@code namespace.key} otherwise (no ':' in permission nodes). */
    static String node(EntityType type) {
        NamespacedKey key = type.getKey();
        return NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.getNamespace() + "." + key.getKey();
    }

    static String name(EntityType type) {
        NamespacedKey key = type.getKey();
        return NamespacedKey.MINECRAFT.equals(key.getNamespace()) ? key.getKey() : key.asString();
    }
}
