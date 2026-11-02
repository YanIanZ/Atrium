package dev.yanianz.atrium.module.recipes;

import java.util.Locale;
import java.util.regex.Pattern;

/** Recipe ids become {@code atrium:<id>} namespaced keys. */
public final class RecipeId {
    public static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    public static final String RULE = "lowercase letters, digits, '_' and '-' (at most 64 characters, "
            + "starting with a letter or digit)";

    private RecipeId() {}

    public static boolean isValid(String id) {
        return id != null && PATTERN.matcher(id).matches();
    }

    /** Forgiving lookup for command input; file names must still match {@link #PATTERN}. */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
