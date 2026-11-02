package dev.yanianz.atrium.module.worldtools;

/**
 * Decides how many items a mirrored placement costs. Pure, so the survival rule is unit tested.
 *
 * <p>Mirrored blocks must never create items: in survival the same item is taken once per mirrored block,
 * all-or-nothing (nothing is placed when the inventory cannot pay for every copy). Creative mode, the
 * bypass permission and the two config switches can make mirroring free. Breaking a mirrored block never
 * drops items, so it can never be farmed either.
 */
final class MirrorCost {
    /** Returned when the player owns fewer items than the mirrored blocks that would be placed. */
    static final int NOT_ENOUGH = -1;

    private MirrorCost() {}

    /**
     * @return {@code 0} when the placement is free, the number of items to remove, or
     *         {@link #NOT_ENOUGH} when the player must not get the copies
     */
    static int itemsToConsume(boolean creative, boolean bypassCost, boolean chargeSurvival,
            boolean chargeCreative, int copies, int available) {
        if (copies <= 0) {
            return 0;
        }
        if (bypassCost || !chargeSurvival || (creative && !chargeCreative)) {
            return 0;
        }
        return available >= copies ? copies : NOT_ENOUGH;
    }
}
