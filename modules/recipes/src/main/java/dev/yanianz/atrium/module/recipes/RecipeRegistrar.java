package dev.yanianz.atrium.module.recipes;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Recipe;
import org.jspecify.annotations.Nullable;

/**
 * Owns the registered {@code atrium:<id>} recipes. Registration, removal and lookup happen on the global
 * thread; the active maps are concurrent so the join/discovery task on a region thread can read them.
 */
public final class RecipeRegistrar {
    public record ApplyResult(int registered, List<String> errors, Set<NamespacedKey> previousKeys) {
        public ApplyResult {
            errors = List.copyOf(errors);
            previousKeys = Set.copyOf(previousKeys);
        }
    }

    private final ModuleContext ctx;
    private final RecipeBuilder builder;
    private final Map<String, RecipeSpec> active = new ConcurrentHashMap<>();
    private final Set<NamespacedKey> keys = ConcurrentHashMap.newKeySet();

    public RecipeRegistrar(ModuleContext ctx, RecipeBuilder builder) {
        this.ctx = ctx;
        this.builder = builder;
    }

    /**
     * Removes every recipe owned by this module and registers the given set. Must run on the global thread.
     */
    public ApplyResult replace(Map<String, RecipeSpec> recipes) {
        Set<NamespacedKey> previous = new HashSet<>(keys);
        for (NamespacedKey key : previous) {
            try {
                Bukkit.removeRecipe(key);
            } catch (RuntimeException e) {
                ctx.logger().warn("Could not remove recipe {} before reloading", key, e);
            }
        }
        keys.clear();
        active.clear();
        List<String> errors = new ArrayList<>();
        int registered = 0;
        for (RecipeSpec spec : recipes.values()) {
            NamespacedKey key = RecipeBuilder.key(spec.id());
            try {
                Recipe recipe = builder.build(spec);
                if (!Bukkit.addRecipe(recipe)) {
                    errors.add(spec.id() + ": the server rejected the recipe (is another plugin using the key?)");
                    continue;
                }
                active.put(spec.id(), spec);
                keys.add(key);
                registered++;
            } catch (RecipeBuildException e) {
                errors.add(spec.id() + ": " + e.getMessage());
            } catch (RuntimeException e) {
                errors.add(spec.id() + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        return new ApplyResult(registered, errors, previous);
    }

    /** Removes every recipe owned by this module. Safe to call from disable. */
    public void unregisterAll() {
        for (NamespacedKey key : Set.copyOf(keys)) {
            try {
                Bukkit.removeRecipe(key);
            } catch (RuntimeException e) {
                // The server may already be shutting down; report instead of hiding the failure.
                ctx.logger().warn("Could not remove recipe {}", key, e);
            }
        }
        keys.clear();
        active.clear();
    }

    public Set<NamespacedKey> keys() {
        return Set.copyOf(keys);
    }

    public Map<String, RecipeSpec> active() {
        return Collections.unmodifiableMap(Map.copyOf(active));
    }

    public int size() {
        return active.size();
    }

    @Nullable
    public RecipeSpec spec(String id) {
        return active.get(id);
    }

    /** Builds a fresh result item for {@code /customrecipe give}; may run on any thread. */
    public org.bukkit.inventory.ItemStack result(RecipeSpec spec) throws RecipeBuildException {
        return builder.result(spec.result());
    }
}
