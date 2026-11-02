package dev.yanianz.atrium.module.extras;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Pure part of the shulker preview: copies the contents into exactly one chest page.
 *
 * <p>Every entry is passed through the copier so the preview inventory never holds the same
 * {@code ItemStack} instance as the box. That is what makes the preview read-only: nothing in the copy can
 * change the real item, so there is no dupe or item-loss path. Extra contents beyond the page are dropped
 * and missing slots stay empty ({@code null}).
 */
final class ShulkerContents {
    /** A shulker box holds 27 stacks, exactly one chest row. */
    static final int SLOTS = 27;

    private ShulkerContents() {}

    static <T> List<T> copy(List<T> source, int size, UnaryOperator<T> copier) {
        int length = Math.max(1, size);
        List<T> result = new ArrayList<>(length);
        for (int index = 0; index < length; index++) {
            T value = source != null && index < source.size() ? source.get(index) : null;
            result.add(value == null ? null : copier.apply(value));
        }
        return result;
    }
}
