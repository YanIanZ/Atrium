package dev.yanianz.atrium.module.customtext;

/** A problem inside one text file; the file is logged and skipped, the other files still load. */
final class TextSyntaxException extends Exception {
    private static final long serialVersionUID = 1L;

    TextSyntaxException(String message) {
        super(message);
    }
}
