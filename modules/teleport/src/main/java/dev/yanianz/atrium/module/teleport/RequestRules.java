package dev.yanianz.atrium.module.teleport;

import java.util.List;

/** Pure rules for /tpa: reach and the item block list. */
final class RequestRules {
    private RequestRules() {}

    /**
     * Whether a request between two players {@code distance} blocks apart is allowed.
     *
     * @param limit largest allowed distance in blocks; {@code 0} or less means unlimited
     */
    static boolean withinDistance(double distance, int limit) {
        if (limit <= 0) {
            return true;
        }
        return Double.isFinite(distance) && distance <= limit;
    }

    /** Whether the held item blocks a request (see {@code requests.blocked-materials}). */
    static boolean blockedItem(List<String> blockedMaterials, String materialName) {
        return BlockPatterns.matches(blockedMaterials, materialName);
    }
}
