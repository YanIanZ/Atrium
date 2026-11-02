package dev.yanianz.atrium.module.worth;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Server;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * Reads the server's recipes into the plain {@link WorthGenerator.Recipe} model.
 *
 * <p>Only vanilla-shaped recipes are used: a recipe whose result or inputs carry a custom name, lore, custom
 * model data, item model or enchantments is skipped, because its result is a different item that happens to
 * share a material with the plain price. This is what stops a plugin recipe from inventing a cheap price for
 * the base material.
 *
 * <p>Must run on the server (global) thread: it reads the live recipe registry.
 */
final class RecipeSnapshot {
    private RecipeSnapshot() {}

    static List<WorthGenerator.Recipe> collect(Server server) {
        List<WorthGenerator.Recipe> recipes = new ArrayList<>();
        var iterator = server.recipeIterator();
        while (iterator.hasNext()) {
            Recipe recipe = iterator.next();
            WorthGenerator.Recipe converted = convert(recipe);
            if (converted != null) {
                recipes.add(converted);
            }
        }
        return List.copyOf(recipes);
    }

    private static WorthGenerator.@Nullable Recipe convert(Recipe recipe) {
        ItemStack result = recipe.getResult();
        if (!vanilla(result)) {
            return null;
        }
        String material = result.getType().name();
        int amount = Math.max(1, result.getAmount());
        if (recipe instanceof ShapedRecipe shaped) {
            List<WorthGenerator.Ingredient> ingredients = new ArrayList<>();
            String[] shape = shaped.getShape();
            var choices = shaped.getChoiceMap();
            for (String row : shape) {
                for (char symbol : row.toCharArray()) {
                    if (symbol == ' ') {
                        continue;
                    }
                    RecipeChoice choice = choices.get(symbol);
                    if (choice != null) {
                        add(ingredients, choice);
                    }
                }
            }
            return ingredients.isEmpty() ? null : new WorthGenerator.Recipe(material, amount, ingredients);
        }
        if (recipe instanceof ShapelessRecipe shapeless) {
            List<WorthGenerator.Ingredient> ingredients = new ArrayList<>();
            for (RecipeChoice choice : shapeless.getChoiceList()) {
                add(ingredients, choice);
            }
            return ingredients.isEmpty() ? null : new WorthGenerator.Recipe(material, amount, ingredients);
        }
        if (recipe instanceof CookingRecipe<?> cooking) {
            return oneIngredient(material, amount, cooking.getInputChoice());
        }
        if (recipe instanceof StonecuttingRecipe stonecutting) {
            return oneIngredient(material, amount, stonecutting.getInputChoice());
        }
        // Smithing transforms combine several high-value items; treating them as a crafting cost would
        // undervalue the result drastically, so they are left to the administrator.
        return null;
    }

    private static WorthGenerator.@Nullable Recipe oneIngredient(String material, int amount,
            @Nullable RecipeChoice choice) {
        if (choice == null) {
            return null;
        }
        List<WorthGenerator.Ingredient> ingredients = new ArrayList<>();
        add(ingredients, choice);
        if (ingredients.isEmpty()) {
            return null;
        }
        return new WorthGenerator.Recipe(material, amount, ingredients);
    }

    private static void add(List<WorthGenerator.Ingredient> ingredients, RecipeChoice choice) {
        List<String> materials = new ArrayList<>();
        if (choice instanceof RecipeChoice.MaterialChoice materialChoice) {
            for (var material : materialChoice.getChoices()) {
                materials.add(material.name());
            }
        } else if (choice instanceof RecipeChoice.ExactChoice exact) {
            for (ItemStack item : exact.getChoices()) {
                if (vanilla(item) && item.getType().isItem() && !item.getType().isAir()) {
                    materials.add(item.getType().name());
                }
            }
        }
        if (!materials.isEmpty()) {
            ingredients.add(new WorthGenerator.Ingredient(materials, 1));
        }
    }

    /** A plain item: no custom data that would make it a different thing than its material. */
    @SuppressWarnings("deprecation") // integer custom model data is still the format plugins write
    private static boolean vanilla(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.getType().isItem()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        return !meta.hasDisplayName() && !meta.hasLore() && !meta.hasCustomModelData()
                && !meta.hasCustomModelDataComponent() && !meta.hasItemModel()
                && meta.getEnchants().isEmpty() && !meta.hasAttributeModifiers()
                && (meta.getPersistentDataContainer() == null
                        || meta.getPersistentDataContainer().isEmpty());
    }
}
