package dev.yanianz.atrium.module.rtp;

import java.util.Set;

/**
 * Biome rule for /rtp.
 *
 * <ul>
 *   <li>a non-empty {@code preferred} list wins over everything and also clears the blocked list, so an
 *       admin who lists preferred biomes is not silently blocked by the default ocean list,</li>
 *   <li>otherwise a non-empty {@code allowed} list is an allow list that still respects {@code blocked},</li>
 *   <li>an empty {@code allowed} list means every biome not in {@code blocked}.</li>
 * </ul>
 *
 * All sets contain normalised keys ({@code minecraft:plains}).
 */
final class BiomeFilter {
    private BiomeFilter() {}

    static boolean allows(Set<String> preferred, Set<String> allowed, Set<String> blocked, String biomeKey) {
        if (biomeKey == null) {
            return false;
        }
        if (preferred != null && !preferred.isEmpty()) {
            return preferred.contains(biomeKey);
        }
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(biomeKey)) {
            return false;
        }
        return blocked == null || !blocked.contains(biomeKey);
    }
}
