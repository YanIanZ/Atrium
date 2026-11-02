package dev.yanianz.atrium.core.storage;

import java.util.List;
import java.util.function.Function;

/**
 * One schema step for a module. Versions start at 1 and never change once released. Statements may use
 * {@code {prefix}} which is replaced with the configured table prefix.
 */
public record Migration(int version, String description, Function<Dialect, List<String>> statements) {
    public static Migration of(int version, String description, String... statements) {
        List<String> list = List.of(statements);
        return new Migration(version, description, d -> list);
    }
}
