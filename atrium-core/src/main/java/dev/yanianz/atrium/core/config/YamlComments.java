package dev.yanianz.atrium.core.config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;
import org.spongepowered.configurate.util.NamingSchemes;

/**
 * Writes {@link Comment} texts of a {@code @ConfigSerializable} class above the matching keys of a saved
 * block-style YAML file. Keys inside list items are left uncommented.
 */
final class YamlComments {
    private YamlComments() {}

    static void apply(Path file, Class<?> type) {
        Map<String, String> comments = new HashMap<>();
        collect(type, "", comments, 0);
        if (comments.isEmpty()) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(lines.size() + comments.size() * 2);
            Deque<String> path = new ArrayDeque<>();
            Deque<Integer> indents = new ArrayDeque<>();
            for (String line : lines) {
                String trimmed = line.stripLeading();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue; // regenerated below; old comments are dropped
                }
                int indent = line.length() - trimmed.length();
                while (!indents.isEmpty() && indents.peek() >= indent) {
                    indents.pop();
                    path.pop();
                }
                int colon = keyEnd(trimmed);
                if (colon > 0 && !trimmed.startsWith("- ")) {
                    String key = unquote(trimmed.substring(0, colon));
                    String full = path.isEmpty() ? key : String.join(".", reversed(path)) + "." + key;
                    String comment = lookup(comments, full);
                    if (comment != null) {
                        if (indent == 0 && !out.isEmpty()) {
                            out.add("");
                        }
                        for (String c : comment.split("\n")) {
                            out.add(" ".repeat(indent) + "# " + c);
                        }
                    }
                    path.push(key);
                    indents.push(indent);
                }
                out.add(line);
            }
            Files.write(file, out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Comments are cosmetic; the config itself was saved successfully.
        }
    }

    /** Exact path first, then paths where map keys are replaced by {@code *} (e.g. worlds.*.radius). */
    private static String lookup(Map<String, String> comments, String full) {
        String exact = comments.get(full);
        if (exact != null) {
            return exact;
        }
        String[] parts = full.split("\\.");
        for (Map.Entry<String, String> entry : comments.entrySet()) {
            String[] pattern = entry.getKey().split("\\.");
            if (pattern.length != parts.length) {
                continue;
            }
            boolean match = true;
            for (int i = 0; i < parts.length && match; i++) {
                match = pattern[i].equals("*") || pattern[i].equals(parts[i]);
            }
            if (match) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static List<String> reversed(Deque<String> path) {
        List<String> list = new ArrayList<>(path);
        java.util.Collections.reverse(list);
        return list;
    }

    private static int keyEnd(String trimmed) {
        boolean quoted = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '"' || c == '\'') {
                quoted = !quoted;
            } else if (c == ':' && !quoted && (i + 1 == trimmed.length() || trimmed.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String key) {
        String k = key.strip();
        if (k.length() >= 2 && (k.charAt(0) == '"' || k.charAt(0) == '\'') && k.charAt(k.length() - 1) == k.charAt(0)) {
            return k.substring(1, k.length() - 1);
        }
        return k;
    }

    private static void collect(Class<?> type, String prefix, Map<String, String> out, int depth) {
        if (depth > 8 || !type.isAnnotationPresent(ConfigSerializable.class)) {
            return;
        }
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int mod = field.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || field.isSynthetic()) {
                    continue;
                }
                String key = prefix + NamingSchemes.LOWER_CASE_DASHED.coerce(field.getName());
                Comment comment = field.getAnnotation(Comment.class);
                if (comment != null) {
                    out.put(key, comment.value());
                }
                collect(field.getType(), key + ".", out, depth + 1);
                // Map<String, X> of config objects: X's comments apply under every key.
                if (java.util.Map.class.isAssignableFrom(field.getType())
                        && field.getGenericType() instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getActualTypeArguments().length == 2
                        && pt.getActualTypeArguments()[1] instanceof Class<?> valueType) {
                    collect(valueType, key + ".*.", out, depth + 1);
                }
            }
        }
    }
}
