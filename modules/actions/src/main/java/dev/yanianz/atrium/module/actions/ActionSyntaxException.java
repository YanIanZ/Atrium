package dev.yanianz.atrium.module.actions;

/** A line that cannot be parsed. The message is admin-facing and starts with {@code "line N: "}. */
final class ActionSyntaxException extends Exception {
    private static final long serialVersionUID = 1L;

    ActionSyntaxException(int line, String message) {
        super("line " + line + ": " + message);
    }
}
