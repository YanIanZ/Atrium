package dev.yanianz.atrium.module.recipes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * A parsed recipe file. Pure data: no Bukkit recipe objects are created here, so parsing stays testable
 * without a running server. {@link RecipeBuilder} turns a spec into the matching Bukkit recipe and result
 * item on the global thread.
 */
public sealed interface RecipeSpec permits RecipeSpec.Shaped, RecipeSpec.Shapeless, RecipeSpec.Cooking,
        RecipeSpec.Stonecutting, RecipeSpec.Smithing {

    String id();

    Kind kind();

    ResultSpec result();

    enum Kind {
        SHAPED("shaped"),
        SHAPELESS("shapeless"),
        FURNACE("furnace"),
        BLASTING("blasting"),
        SMOKING("smoking"),
        CAMPFIRE("campfire"),
        STONECUTTING("stonecutting"),
        SMITHING("smithing");

        private final String configName;

        Kind(String configName) {
            this.configName = configName;
        }

        public String configName() {
            return configName;
        }
    }

    /** A set of materials accepted in one ingredient slot. */
    record Ingredient(List<Material> materials) {
        public Ingredient {
            materials = List.copyOf(materials);
            if (materials.isEmpty()) {
                throw new IllegalArgumentException("an ingredient needs at least one material");
            }
        }
    }

    /**
     * @param name             trusted MiniMessage, or {@code null} for no custom name
     * @param lore             trusted MiniMessage lines, possibly empty
     * @param customModelData  {@code null} when not set
     * @param enchantments     normalised enchantment keys ({@code minecraft:sharpness}) to level
     */
    record ResultSpec(
            Material material,
            int amount,
            String name,
            List<String> lore,
            Integer customModelData,
            Map<String, Integer> enchantments) {
        public ResultSpec {
            lore = List.copyOf(lore);
            enchantments = Collections.unmodifiableMap(new LinkedHashMap<>(enchantments));
        }
    }

    record Shaped(
            String id,
            List<String> pattern,
            Map<Character, Ingredient> key,
            ResultSpec result) implements RecipeSpec {
        public Shaped {
            pattern = List.copyOf(pattern);
            key = Collections.unmodifiableMap(new LinkedHashMap<>(key));
        }

        @Override
        public Kind kind() {
            return Kind.SHAPED;
        }
    }

    record Shapeless(String id, List<Ingredient> ingredients, ResultSpec result) implements RecipeSpec {
        public Shapeless {
            ingredients = List.copyOf(ingredients);
        }

        @Override
        public Kind kind() {
            return Kind.SHAPELESS;
        }
    }

    record Cooking(
            String id,
            Kind kind,
            Ingredient input,
            ResultSpec result,
            float experience,
            int cookingTime) implements RecipeSpec {
        public Cooking {
            if (kind != Kind.FURNACE && kind != Kind.BLASTING && kind != Kind.SMOKING && kind != Kind.CAMPFIRE) {
                throw new IllegalArgumentException("not a cooking kind: " + kind);
            }
        }
    }

    record Stonecutting(String id, Ingredient input, ResultSpec result) implements RecipeSpec {
        @Override
        public Kind kind() {
            return Kind.STONECUTTING;
        }
    }

    record Smithing(
            String id,
            Ingredient template,
            Ingredient base,
            Ingredient addition,
            ResultSpec result) implements RecipeSpec {
        @Override
        public Kind kind() {
            return Kind.SMITHING;
        }
    }
}
