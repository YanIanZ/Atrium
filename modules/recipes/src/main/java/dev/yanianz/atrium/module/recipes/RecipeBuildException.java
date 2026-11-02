package dev.yanianz.atrium.module.recipes;

/** A recipe was parsed but cannot be turned into a Bukkit recipe/item on this server. */
public final class RecipeBuildException extends Exception {
    private static final long serialVersionUID = 1L;

    public RecipeBuildException(String message) {
        super(message);
    }

    public RecipeBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
