package dev.yanianz.atrium.module.particles;

import java.util.OptionalInt;

/**
 * Pure resolution of the numeric slot permission. Kept separate from {@code Permissions} so the limit
 * rule can be unit-tested without a server.
 */
public final class SlotLimits {
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private SlotLimits() {}

    /**
     * @param fallback  configured default slots
     * @param unlimited the player has {@code atrium.particles.slots.unlimited}
     * @param highest   highest {@code atrium.particles.slots.<n>} granted, if any
     */
    public static int resolve(int fallback, boolean unlimited, OptionalInt highest) {
        if (unlimited) {
            return UNLIMITED;
        }
        int base = Math.max(0, fallback);
        return highest.isPresent() ? Math.max(base, highest.getAsInt()) : base;
    }
}
