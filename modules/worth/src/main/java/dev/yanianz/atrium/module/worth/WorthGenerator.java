package dev.yanianz.atrium.module.worth;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Derives prices for items that have none from the recipes that produce them, starting from the prices an
 * administrator already set.
 *
 * <p>One material costs the cheapest recipe that can be made entirely from already priced ingredients:
 * {@code sum(ingredient price * amount) / result amount}. The division by the result amount is what keeps a
 * recipe that yields several items from being valued as if it yielded one (a log that makes four planks makes
 * each plank a quarter of the log). Prices are rounded <em>down</em>, so a generated price can never exceed the
 * cost of its ingredients.
 *
 * <p>Passes repeat until no new price is found or {@code maxPasses} is reached, because one recipe may depend
 * on an item priced in the previous pass. An ingredient with several accepted materials is priced at the
 * cheapest one that already has a price. Nothing is written here: the plan is returned to the caller, which
 * decides whether to keep it (dry run) or save it.
 */
final class WorthGenerator {
    /** Upper bound for a generated price; anything above is almost certainly a broken recipe. */
    static final BigDecimal MAX_PRICE = BigDecimal.valueOf(1_000_000_000L);

    /**
     * @param increasePercent markup over the ingredient cost (0 = exact cost)
     * @param maxPasses       how often the whole recipe list is walked, 1-10
     * @param scale           decimal places, rounded down
     */
    record Options(double increasePercent, int maxPasses, int scale) {
        Options {
            if (!Double.isFinite(increasePercent) || increasePercent < 0) {
                increasePercent = 0;
            }
            increasePercent = Math.min(increasePercent, 1000);
            maxPasses = Math.clamp(maxPasses, 1, 10);
            scale = Math.clamp(scale, 0, 10);
        }
    }

    /** A recipe whose result is {@code resultAmount} items and whose ingredients are alternatives. */
    record Recipe(String result, int resultAmount, List<Ingredient> ingredients) {
        Recipe {
            result = result.toLowerCase(java.util.Locale.ROOT);
            resultAmount = Math.max(1, resultAmount);
            ingredients = List.copyOf(ingredients);
        }
    }

    /** One ingredient: {@code amount} of any material in {@code materials}. */
    record Ingredient(List<String> materials, int amount) {
        Ingredient {
            materials = materials.stream().map(m -> m.toLowerCase(java.util.Locale.ROOT)).distinct().toList();
            amount = Math.max(1, amount);
        }
    }

    /**
     * @param prices     generated prices, sorted by material
     * @param passes     how many passes ran
     * @param considered result materials that still have no usable recipe
     */
    record Plan(Map<String, BigDecimal> prices, int passes, int considered) {
        Plan {
            prices = java.util.Collections.unmodifiableMap(new TreeMap<>(prices));
        }
    }

    private WorthGenerator() {}

    /**
     * @param base      prices to start from (material -> positive price)
     * @param protectedMaterials result materials that must not be generated (entries an administrator wrote)
     */
    static Plan generate(Map<String, BigDecimal> base, Set<String> protectedMaterials, List<Recipe> recipes,
            Options options) {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        base.forEach((material, price) -> {
            if (material != null && price != null && price.signum() > 0) {
                prices.put(material.toLowerCase(java.util.Locale.ROOT), price);
            }
        });
        Set<String> blocked = new TreeSet<>();
        protectedMaterials.forEach(material -> blocked.add(material.toLowerCase(java.util.Locale.ROOT)));
        Set<String> results = new TreeSet<>();
        recipes.forEach(recipe -> results.add(recipe.result()));
        Set<String> generated = new TreeSet<>();

        BigDecimal markup = BigDecimal.ONE.add(
                BigDecimal.valueOf(options.increasePercent()).movePointLeft(2));
        int passes = 0;
        for (int pass = 0; pass < options.maxPasses(); pass++) {
            passes = pass + 1;
            // Cost against the prices known at the start of the pass: one pass is one dependency level,
            // so max-passes really limits how deep a chain can be resolved.
            Map<String, BigDecimal> snapshot = Map.copyOf(prices);
            Map<String, BigDecimal> discovered = new LinkedHashMap<>();
            for (String material : results) {
                if (generated.contains(material) || blocked.contains(material) || discovered.containsKey(material)) {
                    continue;
                }
                BigDecimal best = cheapest(recipes, material, snapshot);
                if (best == null) {
                    continue;
                }
                BigDecimal price = best.multiply(markup).setScale(options.scale(), RoundingMode.DOWN);
                if (price.signum() <= 0 || price.compareTo(MAX_PRICE) > 0) {
                    continue;
                }
                discovered.put(material, price);
            }
            if (discovered.isEmpty()) {
                break;
            }
            discovered.forEach((material, price) -> {
                prices.put(material, price);
                generated.add(material);
            });
        }
        int considered = 0;
        for (String material : results) {
            if (!prices.containsKey(material)) {
                considered++;
            }
        }
        Map<String, BigDecimal> output = new LinkedHashMap<>();
        for (String material : generated) {
            output.put(material, prices.get(material));
        }
        return new Plan(new TreeMap<>(output), passes, considered);
    }

    /** Cheapest usable unit price of {@code material}, or {@code null} when no recipe is fully priced yet. */
    private static BigDecimal cheapest(List<Recipe> recipes, String material, Map<String, BigDecimal> prices) {
        BigDecimal best = null;
        for (Recipe recipe : recipes) {
            if (!recipe.result().equals(material)) {
                continue;
            }
            BigDecimal total = cost(recipe, prices);
            if (total == null) {
                continue;
            }
            BigDecimal unit = total.divide(BigDecimal.valueOf(recipe.resultAmount()), 10, RoundingMode.DOWN);
            if (best == null || unit.compareTo(best) < 0) {
                best = unit;
            }
        }
        return best;
    }

    private static BigDecimal cost(Recipe recipe, Map<String, BigDecimal> prices) {
        BigDecimal total = BigDecimal.ZERO;
        for (Ingredient ingredient : recipe.ingredients()) {
            BigDecimal cheapest = null;
            for (String material : ingredient.materials()) {
                BigDecimal price = prices.get(material);
                if (price != null && (cheapest == null || price.compareTo(cheapest) < 0)) {
                    cheapest = price;
                }
            }
            if (cheapest == null) {
                return null;
            }
            total = total.add(cheapest.multiply(BigDecimal.valueOf(ingredient.amount())));
        }
        return total;
    }

    /** Every result material of {@code recipes}, for the command report. */
    static List<String> results(List<Recipe> recipes) {
        List<String> results = new ArrayList<>(new TreeSet<>(
                recipes.stream().map(Recipe::result).toList()));
        return List.copyOf(results);
    }
}
