package dev.yanianz.atrium.module.integrations;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Pure selection helpers, kept separate so they can be tested without Bukkit or a live server. */
final class HookSelection {
    private HookSelection() {}

    static <T> List<T> select(List<String> ids, Predicate<String> available, Function<String, T> factory,
            Consumer<String> failed) {
        java.util.ArrayList<T> selected = new java.util.ArrayList<>();
        for (String id : ids) {
            if (!available.test(id)) {
                continue;
            }
            try {
                T hook = factory.apply(id);
                if (hook != null) {
                    selected.add(hook);
                }
            } catch (RuntimeException | LinkageError e) {
                failed.accept(id);
            }
        }
        return List.copyOf(selected);
    }
}
