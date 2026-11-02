package dev.yanianz.atrium.module.recipes;

/** A recipe file (or one of its fields) is malformed. The message is shown in the console log. */
public final class RecipeFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public RecipeFormatException(String message) {
        super(message);
    }
}
