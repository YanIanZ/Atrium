package dev.yanianz.atrium.module.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class RecipeParserTest {
    private static ConfigurationNode yaml(String text) throws Exception {
        return YamlConfigurationLoader.builder().buildAndLoadString(text);
    }

    private static final String RESULT = """
            result:
              material: DIAMOND_SWORD
              amount: 1
              name: "<red>Blade"
              lore:
                - "<gray>A custom blade"
              custom-model-data: 42
              enchantments:
                sharpness: 5
            """;

    @Test
    void parsesShapedRecipe() throws Exception {
        String text = """
                type: shaped
                pattern:
                  - " D "
                  - "DSD"
                  - " S "
                key:
                  D: DIAMOND
                  S: STICK
                """ + RESULT;
        RecipeSpec spec = RecipeParser.parse("blade", yaml(text));
        RecipeSpec.Shaped shaped = assertInstanceOf(RecipeSpec.Shaped.class, spec);
        assertEquals(RecipeSpec.Kind.SHAPED, shaped.kind());
        assertEquals(List.of(" D ", "DSD", " S "), shaped.pattern());
        assertEquals(List.of(Material.DIAMOND), shaped.key().get('D').materials());
        assertEquals(List.of(Material.STICK), shaped.key().get('S').materials());
        assertEquals(Material.DIAMOND_SWORD, shaped.result().material());
        assertEquals(1, shaped.result().amount());
        assertEquals("<red>Blade", shaped.result().name());
        assertEquals(List.of("<gray>A custom blade"), shaped.result().lore());
        assertEquals(42, shaped.result().customModelData());
        assertEquals(Map.of("minecraft:sharpness", 5), shaped.result().enchantments());
    }

    @Test
    void parsesShapelessRecipeWithMaterialChoices() throws Exception {
        String text = """
                type: shapeless
                ingredients:
                  - [OAK_PLANKS, BIRCH_PLANKS]
                  - STICK
                result:
                  material: WOODEN_SHOVEL
                """;
        RecipeSpec.Shapeless spec = assertInstanceOf(RecipeSpec.Shapeless.class,
                RecipeParser.parse("shovel", yaml(text)));
        assertEquals(2, spec.ingredients().size());
        assertEquals(List.of(Material.OAK_PLANKS, Material.BIRCH_PLANKS), spec.ingredients().getFirst().materials());
        assertEquals(1, spec.result().amount());
        assertNull(spec.result().name());
        assertTrue(spec.result().lore().isEmpty());
        assertNull(spec.result().customModelData());
    }

    @Test
    void parsesFurnaceDefaultsAndExplicitValues() throws Exception {
        String defaults = """
                type: furnace
                input: RAW_IRON
                result:
                  material: IRON_INGOT
                """;
        RecipeSpec.Cooking furnace = assertInstanceOf(RecipeSpec.Cooking.class,
                RecipeParser.parse("iron", yaml(defaults)));
        assertEquals(RecipeSpec.Kind.FURNACE, furnace.kind());
        assertEquals(200, furnace.cookingTime());
        assertEquals(0f, furnace.experience());

        String explicit = """
                type: furnace
                input: RAW_GOLD
                experience: 1.5
                cooking-time: 300
                result:
                  material: GOLD_INGOT
                """;
        RecipeSpec.Cooking custom = assertInstanceOf(RecipeSpec.Cooking.class,
                RecipeParser.parse("gold", yaml(explicit)));
        assertEquals(300, custom.cookingTime());
        assertEquals(1.5f, custom.experience());
    }

    @Test
    void parsesEveryCookingKind() throws Exception {
        for (String type : List.of("blasting", "smoking", "campfire")) {
            String text = "type: " + type + "\ninput: BEEF\nresult:\n  material: COOKED_BEEF\n";
            RecipeSpec.Cooking spec = assertInstanceOf(RecipeSpec.Cooking.class,
                    RecipeParser.parse(type, yaml(text)));
            assertEquals(type, spec.kind().configName());
        }
    }

    @Test
    void parsesStonecuttingAndResultAmount() throws Exception {
        String text = """
                type: stonecutting
                input: STONE
                result:
                  material: STONE_SLAB
                  amount: 2
                """;
        RecipeSpec.Stonecutting spec = assertInstanceOf(RecipeSpec.Stonecutting.class,
                RecipeParser.parse("slabs", yaml(text)));
        assertEquals(List.of(Material.STONE), spec.input().materials());
        assertEquals(2, spec.result().amount());
    }

    @Test
    void parsesSmithingTransform() throws Exception {
        String text = """
                type: smithing
                template: NETHERITE_UPGRADE_SMITHING_TEMPLATE
                base: DIAMOND_SWORD
                addition: NETHERITE_INGOT
                result:
                  material: NETHERITE_SWORD
                """;
        RecipeSpec.Smithing spec = assertInstanceOf(RecipeSpec.Smithing.class,
                RecipeParser.parse("netherite", yaml(text)));
        assertEquals(RecipeSpec.Kind.SMITHING, spec.kind());
        assertEquals(List.of(Material.NETHERITE_INGOT), spec.addition().materials());
    }

    @Test
    void rejectsUnknownTypeAndMissingFields() throws Exception {
        assertThrows(RecipeFormatException.class,
                () -> RecipeParser.parse("x", yaml("type: magic\nresult:\n  material: STONE\n")));
        assertThrows(RecipeFormatException.class,
                () -> RecipeParser.parse("x", yaml("result:\n  material: STONE\n")));
        assertThrows(RecipeFormatException.class,
                () -> RecipeParser.parse("x", yaml("type: furnace\nresult:\n  material: STONE\n")));
        assertThrows(RecipeFormatException.class,
                () -> RecipeParser.parse("x", yaml("type: shapeless\ninput: STONE\n")));
    }

    @Test
    void rejectsBrokenShapedPatternsAndKeys() throws Exception {
        String base = RESULT;
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shaped
                pattern: ["DD", "D"]
                key: {D: DIAMOND}
                """ + base)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shaped
                pattern: ["DD"]
                key: {D: DIAMOND, S: STICK}
                """ + base)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shaped
                pattern: ["DS"]
                key: {D: DIAMOND}
                """ + base)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shaped
                pattern: ["DDDD"]
                key: {D: DIAMOND}
                """ + base)));
    }

    @Test
    void rejectsUnknownMaterialAndTags() throws Exception {
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shapeless
                ingredients: [NOT_A_MATERIAL]
                result: {material: STONE}
                """)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shapeless
                ingredients: ["#minecraft:planks"]
                result: {material: STONE}
                """)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: furnace
                input: AIR
                result: {material: STONE}
                """)));
    }

    @Test
    void rejectsBadResultValuesAndEnchantmentDuplicates() throws Exception {
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shapeless
                ingredients: [STONE]
                result: {material: STONE, amount: 0}
                """)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shapeless
                ingredients: [STONE]
                result: {material: STONE, amount: 65}
                """)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shapeless
                ingredients: [STONE]
                result:
                  material: STONE
                  enchantments: {sharpness: 0}
                """)));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("""
                type: shapeless
                ingredients: [STONE]
                result:
                  material: STONE
                  enchantments: {sharpness: 2, "minecraft:sharpness": 3}
                """)));
    }

    @Test
    void rejectsInvalidIdAndEmptyFile() throws Exception {
        assertThrows(RecipeFormatException.class,
                () -> RecipeParser.parse("Bad Id", yaml("type: shapeless\nresult: {material: STONE}\n")));
        assertThrows(RecipeFormatException.class, () -> RecipeParser.parse("x", yaml("")));
    }
}
