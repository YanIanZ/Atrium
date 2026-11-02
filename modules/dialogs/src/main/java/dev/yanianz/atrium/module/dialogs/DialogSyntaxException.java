package dev.yanianz.atrium.module.dialogs;

import java.util.List;

/** A dialog file is not valid. Carries every problem found, one per line, for the server log. */
final class DialogSyntaxException extends Exception {
    private static final long serialVersionUID = 1L;
    private final List<String> problems;

    DialogSyntaxException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    List<String> problems() {
        return problems;
    }
}
