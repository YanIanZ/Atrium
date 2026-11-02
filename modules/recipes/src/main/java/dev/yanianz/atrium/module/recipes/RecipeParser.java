package dev.yanianz.atrium.module.recipes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses one recipe YAML document. Pure: no server access, no file access, so every rule is unit tested.
 *
 * <pre>
 * type: shaped | shapeless | furnace | blasting | smoking | campfire | stonecutting | smithing
 * result:
 *   material: DIAMOND_SWORD
 *   amount: 1
 *   name: "&lt;red&gt;Blade"          # trusted MiniMessage, applied later
 *   lore: ["&lt;gray&gt;A custom blade"]
 *   custom-model-data: 42
 *   enchantments: {sharpness: 5}
 * </pre>
 */
public final class RecipeParser {
    private static final int MAX_PATTERN_SIDE = 3;
    private static final int MAX_SHAPELESS = 9;
    private static final int MAX_RESULT_AMOUNT = 64;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_LORE_LINES = 32;
    private static final int MAX_LORE_LENGTH = 256;
    private static final int MAX_ENCHANT_LEVEL = 255;
    private static final int MAX_INGREDIENT_CHOICES = 64;
    private static final int MAX_COOKING_TIME = 72_000;

    private RecipeParser() {}

    public static RecipeSpec parse(String id, ConfigurationNode root) throws RecipeFormatException {
        if (!RecipeId.isValid(id)) {
            throw new RecipeFormatException("the file name must be " + RecipeId.RULE + ", got '" + id + "'");
        }
        if (root == null || root.virtual() || root.empty()) {
            throw new RecipeFormatException("the file is empty");
        }
        String type = text(root.node("type"), "type", "").toLowerCase(Locale.ROOT).replace('-', '_');
        if (type.isEmpty()) {
            throw new RecipeFormatException("type is missing");
        }
        RecipeSpec.ResultSpec result = parseResult(root.node("result"));
        return switch (type) {
            case "shaped", "crafting_shaped" -> parseShaped(id, root, result);
            case "shapeless", "crafting_shapeless" -> parseShapeless(id, root, result);
            case "furnace", "smelting", "cooking" -> parseCooking(id, root, result, RecipeSpec.Kind.FURNACE);
            case "blasting" -> parseCooking(id, root, result, RecipeSpec.Kind.BLASTING);
            case "smoking" -> parseCooking(id, root, result, RecipeSpec.Kind.SMOKING);
            case "campfire" -> parseCooking(id, root, result, RecipeSpec.Kind.CAMPFIRE);
            case "stonecutting", "stone_cutting" -> new RecipeSpec.Stonecutting(id,
                    ingredient(root.node("input"), "input"), result);
            case "smithing", "smithing_transform" -> parseSmithing(id, root, result);
            default -> throw new RecipeFormatException("unknown type '" + type
                    + "' (expected shaped, shapeless, furnace, blasting, smoking, campfire, "
                    + "stonecutting or smithing)");
        };
    }

    private static RecipeSpec.Shaped parseShaped(String id, ConfigurationNode root,
            RecipeSpec.ResultSpec result) throws RecipeFormatException {
        ConfigurationNode patternNode = root.node("pattern");
        if (!patternNode.isList()) {
            throw new RecipeFormatException("pattern must be a list of 1-3 rows");
        }
        List<String> pattern = new ArrayList<>();
        for (ConfigurationNode row : patternNode.childrenList()) {
            if (row.isList() || row.isMap()) {
                throw new RecipeFormatException("pattern must contain text rows, not maps or lists");
            }
            pattern.add(row.getString(""));
        }
        if (pattern.isEmpty() || pattern.size() > MAX_PATTERN_SIDE) {
            throw new RecipeFormatException("pattern must have 1-3 rows, got " + pattern.size());
        }
        int width = pattern.getFirst().length();
        if (width < 1 || width > MAX_PATTERN_SIDE) {
            throw new RecipeFormatException("a pattern row must have 1-3 characters, got " + width);
        }
        for (int row = 0; row < pattern.size(); row++) {
            if (pattern.get(row).length() != width) {
                throw new RecipeFormatException("pattern row " + (row + 1) + " has "
                        + pattern.get(row).length() + " characters; every row must have " + width);
            }
        }

        ConfigurationNode keyNode = root.node("key");
        if (!keyNode.isMap()) {
            throw new RecipeFormatException("key must map pattern characters to ingredients");
        }
        Map<Character, RecipeSpec.Ingredient> key = new LinkedHashMap<>();
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : keyNode.childrenMap().entrySet()) {
            String raw = String.valueOf(entry.getKey());
            if (raw.length() != 1) {
                throw new RecipeFormatException("key '" + raw + "' must be exactly one character");
            }
            char symbol = raw.charAt(0);
            if (symbol == ' ') {
                throw new RecipeFormatException("the space character is the empty slot and cannot have an ingredient");
            }
            key.put(symbol, ingredient(entry.getValue(), "key." + raw));
        }

        Set<Character> used = new LinkedHashSet<>();
        for (String row : pattern) {
            for (int i = 0; i < row.length(); i++) {
                char symbol = row.charAt(i);
                if (symbol != ' ') {
                    used.add(symbol);
                }
            }
        }
        for (char symbol : used) {
            if (!key.containsKey(symbol)) {
                throw new RecipeFormatException("pattern uses '" + symbol + "' but key has no ingredient for it");
            }
        }
        for (char symbol : key.keySet()) {
            if (!used.contains(symbol)) {
                throw new RecipeFormatException("key '" + symbol + "' is not used by the pattern");
            }
        }
        return new RecipeSpec.Shaped(id, pattern, key, result);
    }

    private static RecipeSpec.Shapeless parseShapeless(String id, ConfigurationNode root,
            RecipeSpec.ResultSpec result) throws RecipeFormatException {
        ConfigurationNode node = root.node("ingredients");
        if (!node.isList()) {
            throw new RecipeFormatException("ingredients must be a list of 1-9 ingredients");
        }
        List<RecipeSpec.Ingredient> ingredients = new ArrayList<>();
        for (ConfigurationNode child : node.childrenList()) {
            ingredients.add(ingredient(child, "ingredients"));
        }
        if (ingredients.isEmpty() || ingredients.size() > MAX_SHAPELESS) {
            throw new RecipeFormatException("ingredients must have 1-9 entries, got " + ingredients.size());
        }
        return new RecipeSpec.Shapeless(id, ingredients, result);
    }

    private static RecipeSpec.Cooking parseCooking(String id, ConfigurationNode root,
            RecipeSpec.ResultSpec result, RecipeSpec.Kind kind) throws RecipeFormatException {
        RecipeSpec.Ingredient input = ingredient(root.node("input"), "input");
        float experience = decimal(root.node("experience"), "experience", 0f, 0f, 1000f);
        int defaultTime = switch (kind) {
            case FURNACE -> 200;
            case BLASTING, SMOKING -> 100;
            case CAMPFIRE -> 600;
            default -> 200;
        };
        int time = integer(root.node("cooking-time"), "cooking-time", defaultTime, 1, MAX_COOKING_TIME);
        return new RecipeSpec.Cooking(id, kind, input, result, experience, time);
    }

    private static RecipeSpec.Smithing parseSmithing(String id, ConfigurationNode root,
            RecipeSpec.ResultSpec result) throws RecipeFormatException {
        return new RecipeSpec.Smithing(id,
                ingredient(root.node("template"), "template"),
                ingredient(root.node("base"), "base"),
                ingredient(root.node("addition"), "addition"),
                result);
    }

    private static RecipeSpec.ResultSpec parseResult(ConfigurationNode node) throws RecipeFormatException {
        if (!node.isMap()) {
            throw new RecipeFormatException("result must be a map with at least a 'material' entry");
        }
        ConfigurationNode materialNode = node.node("material");
        String rawMaterial = text(materialNode, "result.material", null);
        if (rawMaterial == null) {
            throw new RecipeFormatException("result.material is missing");
        }
        Material material = Material.matchMaterial(rawMaterial);
        if (material == null) {
            throw new RecipeFormatException("result.material: unknown material '" + rawMaterial + "'");
        }
        if (material == Material.AIR) {
            throw new RecipeFormatException("result.material cannot be AIR");
        }
        int amount = integer(node.node("amount"), "result.amount", 1, 1, MAX_RESULT_AMOUNT);

        ConfigurationNode nameNode = node.node("name");
        if (nameNode.virtual()) {
            nameNode = node.node("display-name");
        }
        String name = text(nameNode, "result.name", "");
        if (name.isEmpty()) {
            name = null;
        } else if (name.length() > MAX_NAME_LENGTH) {
            throw new RecipeFormatException("result.name is longer than " + MAX_NAME_LENGTH + " characters");
        }

        List<String> lore = new ArrayList<>();
        ConfigurationNode loreNode = node.node("lore");
        if (!loreNode.virtual() && !loreNode.empty()) {
            List<? extends ConfigurationNode> lines = loreNode.isList() ? loreNode.childrenList() : List.of(loreNode);
            if (lines.size() > MAX_LORE_LINES) {
                throw new RecipeFormatException("result.lore has more than " + MAX_LORE_LINES + " lines");
            }
            for (ConfigurationNode line : lines) {
                if (line.isList() || line.isMap()) {
                    throw new RecipeFormatException("result.lore must contain text lines");
                }
                String value = line.getString("");
                if (value.length() > MAX_LORE_LENGTH) {
                    throw new RecipeFormatException("a result.lore line is longer than "
                            + MAX_LORE_LENGTH + " characters");
                }
                lore.add(value);
            }
        }

        int modelData = integer(node.node("custom-model-data"), "result.custom-model-data", -1, 0,
                Integer.MAX_VALUE);
        Integer customModelData = modelData < 0 ? null : modelData;

        Map<String, Integer> enchantments = new LinkedHashMap<>();
        ConfigurationNode enchantNode = node.node("enchantments");
        if (!enchantNode.virtual() && !enchantNode.empty()) {
            if (!enchantNode.isMap()) {
                throw new RecipeFormatException("result.enchantments must map enchantment names to levels");
            }
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : enchantNode.childrenMap().entrySet()) {
                String rawKey = String.valueOf(entry.getKey());
                int level = integer(entry.getValue(), "result.enchantments." + rawKey, -1, 1,
                        MAX_ENCHANT_LEVEL);
                if (level < 0) {
                    throw new RecipeFormatException("result.enchantments." + rawKey + " is missing its level");
                }
                String key = EnchantRules.normalize(rawKey);
                if (key.isEmpty()) {
                    throw new RecipeFormatException("result.enchantments contains an empty key");
                }
                if (enchantments.putIfAbsent(key, level) != null) {
                    throw new RecipeFormatException("result.enchantments lists '" + key + "' twice");
                }
            }
        }
        return new RecipeSpec.ResultSpec(material, amount, name, lore, customModelData, enchantments);
    }

    private static RecipeSpec.Ingredient ingredient(ConfigurationNode node, String field)
            throws RecipeFormatException {
        if (node == null || node.virtual() || node.empty()) {
            throw new RecipeFormatException(field + " is missing");
        }
        List<? extends ConfigurationNode> choices = node.isList() ? node.childrenList() : List.of(node);
        if (choices.isEmpty()) {
            throw new RecipeFormatException(field + " is empty");
        }
        if (choices.size() > MAX_INGREDIENT_CHOICES) {
            throw new RecipeFormatException(field + " has more than " + MAX_INGREDIENT_CHOICES + " choices");
        }
        Set<Material> materials = new LinkedHashSet<>();
        for (ConfigurationNode choice : choices) {
            if (choice.isList() || choice.isMap()) {
                throw new RecipeFormatException(field + " must contain material names");
            }
            String raw = choice.getString("").trim();
            if (raw.isEmpty()) {
                throw new RecipeFormatException(field + " contains an empty material name");
            }
            if (raw.startsWith("#")) {
                throw new RecipeFormatException(field + ": material tags ('" + raw
                        + "') are not supported; list the materials instead");
            }
            Material material = Material.matchMaterial(raw);
            if (material == null) {
                throw new RecipeFormatException(field + ": unknown material '" + raw + "'");
            }
            if (material == Material.AIR) {
                throw new RecipeFormatException(field + " cannot be AIR");
            }
            materials.add(material);
        }
        return new RecipeSpec.Ingredient(List.copyOf(materials));
    }

    private static String text(ConfigurationNode node, String field, String fallback)
            throws RecipeFormatException {
        if (node == null || node.virtual() || node.empty()) {
            return fallback;
        }
        if (node.isList() || node.isMap()) {
            throw new RecipeFormatException(field + " must be text");
        }
        String value = node.getString("");
        return value == null ? fallback : value.trim();
    }

    private static int integer(ConfigurationNode node, String field, int fallback, int min, int max)
            throws RecipeFormatException {
        if (node == null || node.virtual() || node.empty()) {
            return fallback;
        }
        if (node.isList() || node.isMap()) {
            throw new RecipeFormatException(field + " must be a whole number");
        }
        String raw = node.getString("").trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new RecipeFormatException(field + " must be a whole number, got '" + raw + "'");
        }
        if (value < min || value > max) {
            throw new RecipeFormatException(field + " must be between " + min + " and " + max
                    + ", got " + value);
        }
        return value;
    }

    private static float decimal(ConfigurationNode node, String field, float fallback, float min, float max)
            throws RecipeFormatException {
        if (node == null || node.virtual() || node.empty()) {
            return fallback;
        }
        if (node.isList() || node.isMap()) {
            throw new RecipeFormatException(field + " must be a number");
        }
        String raw = node.getString("").trim();
        if (raw.isEmpty()) {
            return fallback;
        }
        float value;
        try {
            value = Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            throw new RecipeFormatException(field + " must be a number, got '" + raw + "'");
        }
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new RecipeFormatException(field + " must be between " + min + " and " + max
                    + ", got " + value);
        }
        return value;
    }
}
