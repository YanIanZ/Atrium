package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorthGeneratorTest {
    private static final WorthGenerator.Options PLAIN = new WorthGenerator.Options(0, 5, 2);

    private static WorthGenerator.Ingredient of(String material, int amount) {
        return new WorthGenerator.Ingredient(List.of(material), amount);
    }

    private static WorthGenerator.Recipe recipe(String result, int amount, WorthGenerator.Ingredient... ingredients) {
        return new WorthGenerator.Recipe(result, amount, List.of(ingredients));
    }

    /** Compares prices by value, so 1 and 1.00 are the same price. */
    private static void assertPrice(String expected, java.math.BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    @Test
    void derivesPriceFromTheOnlyRecipe() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("oak_log", new BigDecimal("4")), Set.of(),
                List.of(recipe("oak_planks", 4, of("oak_log", 1))), PLAIN);
        assertPrice("1", plan.prices().get("oak_planks"));
        assertEquals(2, plan.passes(), "one productive pass plus the pass that finds nothing new");
    }

    @Test
    void dividesByTheResultAmount() {
        // One log makes four planks, so one plank is a quarter of the log.
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("oak_log", new BigDecimal("100")), Set.of(),
                List.of(recipe("oak_planks", 4, of("oak_log", 1))), PLAIN);
        assertPrice("25", plan.prices().get("oak_planks"));
    }

    @Test
    void appliesTheMarkupAndRoundsDown() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("iron_ingot", new BigDecimal("3")), Set.of(),
                List.of(recipe("iron_block", 1, of("iron_ingot", 9))),
                new WorthGenerator.Options(10, 5, 2));
        // 27 * 1.1 = 29.7 -> 29.70 exactly
        assertEquals(new BigDecimal("29.70"), plan.prices().get("iron_block"));
        WorthGenerator.Plan down = WorthGenerator.generate(Map.of("a", new BigDecimal("1")), Set.of(),
                List.of(recipe("b", 3, of("a", 1))), new WorthGenerator.Options(0, 5, 2));
        assertPrice("0.33", down.prices().get("b"));
    }

    @Test
    void picksTheCheapestUsableRecipe() {
        WorthGenerator.Plan plan = WorthGenerator.generate(
                Map.of("iron", new BigDecimal("10"), "gold", new BigDecimal("5")), Set.of(),
                List.of(recipe("gear", 1, of("iron", 2)), recipe("gear", 1, of("gold", 3))), PLAIN);
        assertPrice("15", plan.prices().get("gear"));
    }

    @Test
    void pricesAChainOverSeveralPasses() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("log", new BigDecimal("4")), Set.of(),
                List.of(recipe("plank", 4, of("log", 1)), recipe("stick", 4, of("plank", 1))), PLAIN);
        assertPrice("1", plan.prices().get("plank"));
        assertPrice("0.25", plan.prices().get("stick"));
        assertTrue(plan.passes() >= 2);
    }

    @Test
    void respectsThePassLimit() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("a", BigDecimal.ONE), Set.of(),
                List.of(recipe("b", 1, of("a", 1)), recipe("c", 1, of("b", 1)), recipe("d", 1, of("c", 1))),
                new WorthGenerator.Options(0, 2, 2));
        assertFalse(plan.prices().containsKey("d"), "d needs three passes");
        assertEquals(2, plan.passes());
        assertTrue(plan.considered() >= 1);
    }

    @Test
    void protectedMaterialsAreNeverTouched() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("log", new BigDecimal("4")),
                Set.of("plank"), List.of(recipe("plank", 4, of("log", 1))), PLAIN);
        assertTrue(plan.prices().isEmpty());
    }

    @Test
    void generatedPricesCanBeReplacedWhenBasePricesAreAllowedToChange() {
        // With no protection, an existing base price is recomputed from the recipe.
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("log", new BigDecimal("4")),
                Set.of(), List.of(recipe("plank", 4, of("log", 1))), PLAIN);
        assertPrice("1", plan.prices().get("plank"));
    }

    @Test
    void unpricedIngredientsAreSkippedUntilTheChainReachesThem() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of(), Set.of(),
                List.of(recipe("unknown", 1, of("mystery", 1))), PLAIN);
        assertTrue(plan.prices().isEmpty());
        assertEquals(1, plan.considered());
    }

    @Test
    void anAlternativeMaterialIsEnough() {
        WorthGenerator.Recipe mixed = new WorthGenerator.Recipe("plank", 1, List.of(
                new WorthGenerator.Ingredient(List.of("missing", "cheap"), 1)));
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("cheap", new BigDecimal("3")), Set.of(),
                List.of(mixed), PLAIN);
        assertPrice("3", plan.prices().get("plank"));
    }

    @Test
    void cheapestAlternativeWins() {
        WorthGenerator.Recipe mixed = new WorthGenerator.Recipe("plank", 1, List.of(
                new WorthGenerator.Ingredient(List.of("expensive", "cheap"), 2)));
        WorthGenerator.Plan plan = WorthGenerator.generate(
                Map.of("expensive", new BigDecimal("10"), "cheap", new BigDecimal("2")), Set.of(),
                List.of(mixed), PLAIN);
        assertPrice("4", plan.prices().get("plank"));
    }

    @Test
    void zeroAndAbsurdPricesAreNotGenerated() {
        WorthGenerator.Plan zero = WorthGenerator.generate(Map.of("a", new BigDecimal("0.001")), Set.of(),
                List.of(recipe("b", 1000, of("a", 1))), new WorthGenerator.Options(0, 5, 2));
        assertTrue(zero.prices().isEmpty(), "a zero price would make the item unsellable");
        WorthGenerator.Plan absurd = WorthGenerator.generate(Map.of("a", new BigDecimal("999999999999")),
                Set.of(), List.of(recipe("b", 1, of("a", 1))), PLAIN);
        assertTrue(absurd.prices().isEmpty());
    }

    @Test
    void optionsAreClamped() {
        WorthGenerator.Options options = new WorthGenerator.Options(Double.NaN, 0, 99);
        assertEquals(0, options.increasePercent());
        assertEquals(1, options.maxPasses());
        assertEquals(10, options.scale());
        WorthGenerator.Options high = new WorthGenerator.Options(5000, 99, -5);
        assertEquals(1000, high.increasePercent());
        assertEquals(10, high.maxPasses());
        assertEquals(0, high.scale());
    }

    @Test
    void outputIsSortedAndResultListIsUnique() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("a", new BigDecimal("2")), Set.of(),
                List.of(recipe("b", 1, of("a", 1)), recipe("c", 1, of("a", 2))), PLAIN);
        assertEquals(List.of("b", "c"), List.copyOf(plan.prices().keySet()));
        assertEquals(List.of("b", "c"), WorthGenerator.results(
                List.of(recipe("b", 1, of("a", 1)), recipe("c", 1, of("a", 2)), recipe("b", 1, of("a", 1)))));
    }

    @Test
    void anEmptyRecipeListProducesNothing() {
        WorthGenerator.Plan plan = WorthGenerator.generate(Map.of("a", BigDecimal.TEN), Set.of(), List.of(), PLAIN);
        assertTrue(plan.prices().isEmpty());
        assertEquals(1, plan.passes());
    }
}
