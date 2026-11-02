package dev.yanianz.atrium.module.recipes;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Builds Bukkit recipes and result items. Must run on the global thread (or during plugin enable), because
 * recipe construction and registration touch server registries.
 */
public final class RecipeBuilder {
    private static final String NAMESPACE = "atrium";

    private final ModuleContext ctx;

    public RecipeBuilder(ModuleContext ctx) {
        this.ctx = ctx;
    }

    public static NamespacedKey key(String id) {
        return new NamespacedKey(NAMESPACE, id);
    }

    public Recipe build(RecipeSpec spec) throws RecipeBuildException {
        ItemStack result = result(spec.result());
        NamespacedKey key = key(spec.id());
        try {
            return switch (spec) {
                case RecipeSpec.Shaped shaped -> shaped(key, result, shaped);
                case RecipeSpec.Shapeless shapeless -> shapeless(key, result, shapeless);
                case RecipeSpec.Cooking cooking -> cooking(key, result, cooking);
                case RecipeSpec.Stonecutting stonecutting -> new StonecuttingRecipe(key, result,
                        choice(stonecutting.input()));
                case RecipeSpec.Smithing smithing -> new SmithingTransformRecipe(key, result,
                        choice(smithing.template()), choice(smithing.base()), choice(smithing.addition()));
            };
        } catch (RecipeBuildException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RecipeBuildException("the server rejected the recipe: " + e.getMessage(), e);
        }
    }

    private Recipe shaped(NamespacedKey key, ItemStack result, RecipeSpec.Shaped spec)
            throws RecipeBuildException {
        if (spec.pattern().isEmpty() || spec.pattern().size() > 3) {
            throw new RecipeBuildException("a shaped recipe needs 1-3 pattern rows");
        }
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(spec.pattern().toArray(String[]::new));
        for (Map.Entry<Character, RecipeSpec.Ingredient> entry : spec.key().entrySet()) {
            recipe.setIngredient(entry.getKey(), choice(entry.getValue()));
        }
        return recipe;
    }

    private Recipe shapeless(NamespacedKey key, ItemStack result, RecipeSpec.Shapeless spec)
            throws RecipeBuildException {
        if (spec.ingredients().isEmpty()) {
            throw new RecipeBuildException("a shapeless recipe needs at least one ingredient");
        }
        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (RecipeSpec.Ingredient ingredient : spec.ingredients()) {
            recipe.addIngredient(choice(ingredient));
        }
        return recipe;
    }

    private Recipe cooking(NamespacedKey key, ItemStack result, RecipeSpec.Cooking spec)
            throws RecipeBuildException {
        RecipeChoice input = choice(spec.input());
        return switch (spec.kind()) {
            case FURNACE -> new FurnaceRecipe(key, result, input, spec.experience(), spec.cookingTime());
            case BLASTING -> new BlastingRecipe(key, result, input, spec.experience(), spec.cookingTime());
            case SMOKING -> new SmokingRecipe(key, result, input, spec.experience(), spec.cookingTime());
            case CAMPFIRE -> new CampfireRecipe(key, result, input, spec.experience(), spec.cookingTime());
            default -> throw new RecipeBuildException("not a cooking recipe: " + spec.kind());
        };
    }

    private static RecipeChoice choice(RecipeSpec.Ingredient ingredient) {
        return new RecipeChoice.MaterialChoice(ingredient.materials());
    }

    public ItemStack result(RecipeSpec.ResultSpec spec) throws RecipeBuildException {
        Material material = spec.material();
        if (!material.isItem()) {
            throw new RecipeBuildException("material " + material + " is not an item");
        }
        int maxStack = Math.max(1, material.getMaxStackSize());
        if (spec.amount() > maxStack) {
            throw new RecipeBuildException("result.amount " + spec.amount()
                    + " exceeds the maximum stack size (" + maxStack + ") of " + material);
        }
        int amount = spec.amount();
        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new RecipeBuildException("material " + material + " cannot have item metadata");
        }
        if (spec.name() != null && !spec.name().isBlank()) {
            meta.displayName(parse(spec.name(), "result.name"));
        }
        if (!spec.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>(spec.lore().size());
            for (String line : spec.lore()) {
                lore.add(parse(line, "result.lore"));
            }
            meta.lore(lore);
        }
        if (spec.customModelData() != null) {
            setCustomModelData(meta, spec.customModelData());
        }
        for (Map.Entry<String, Integer> entry : spec.enchantments().entrySet()) {
            Enchantment enchantment = enchantment(entry.getKey());
            int level = Math.clamp(entry.getValue(), 1, 255);
            if (meta instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta storage) {
                storage.addStoredEnchant(enchantment, level, true);
            } else {
                meta.addEnchant(enchantment, level, true);
            }
        }
        if (!item.setItemMeta(meta)) {
            throw new RecipeBuildException("the server refused the item metadata for " + material);
        }
        return item;
    }

    private Component parse(String miniMessage, String field) throws RecipeBuildException {
        try {
            return ctx.messages().parseTrusted(miniMessage);
        } catch (RuntimeException e) {
            throw new RecipeBuildException(field + " is not valid MiniMessage: " + e.getMessage(), e);
        }
    }

    private static Enchantment enchantment(String key) throws RecipeBuildException {
        try {
            NamespacedKey namespaced = NamespacedKey.fromString(key.toLowerCase(Locale.ROOT));
            Enchantment enchantment = namespaced == null ? null
                    : io.papermc.paper.registry.RegistryAccess.registryAccess()
                            .getRegistry(io.papermc.paper.registry.RegistryKey.ENCHANTMENT)
                            .get(namespaced);
            if (enchantment == null) {
                throw new RecipeBuildException("unknown enchantment '" + key + "'");
            }
            return enchantment;
        } catch (IllegalArgumentException e) {
            throw new RecipeBuildException("invalid enchantment key '" + key + "'", e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void setCustomModelData(ItemMeta meta, int value) {
        meta.setCustomModelData(value);
    }
}
