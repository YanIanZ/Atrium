package dev.yanianz.atrium.module.tablist;

import java.util.ArrayList;
import java.util.List;

/**
 * What the active tab list engine lets Atrium control. With the TAB engine each part depends on the matching
 * TAB feature: a part whose TAB feature is disabled in TAB's config is left to TAB.
 */
record TablistCapabilities(boolean headerFooter, boolean listNames, boolean groupSorting, boolean nameSorting,
        boolean perPlayerText) {

    enum Feature {
        HEADER_FOOTER("header-footer"),
        LIST_NAMES("list-names"),
        GROUP_SORTING("group-sorting"),
        NAME_SORTING("name-sorting"),
        PER_PLAYER_TEXT("per-player-text");

        final String key;

        Feature(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    static TablistCapabilities nativeEngine() {
        return new TablistCapabilities(true, true, true, true, true);
    }

    /**
     * TAB engine. Atrium still renders every text per player; TAB shows it. Sorting uses TAB's team-based
     * sorting, so it needs TAB's sorting feature for both group and name order.
     *
     * @param headerFooter TAB's header/footer feature is enabled
     * @param listNames    TAB's tablist name formatting is enabled
     * @param sorting      TAB's sorting (scoreboard teams) is enabled
     */
    static TablistCapabilities tab(boolean headerFooter, boolean listNames, boolean sorting) {
        return new TablistCapabilities(headerFooter, listNames, sorting, sorting, true);
    }

    boolean supports(Feature feature) {
        return switch (feature) {
            case HEADER_FOOTER -> headerFooter;
            case LIST_NAMES -> listNames;
            case GROUP_SORTING -> groupSorting;
            case NAME_SORTING -> nameSorting;
            case PER_PLAYER_TEXT -> perPlayerText;
        };
    }

    List<Feature> supported() {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (supports(feature)) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    List<Feature> missing() {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (!supports(feature)) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    /** Comma-separated stable keys, for /tablist engine and logs. */
    static String describe(List<Feature> features) {
        return features.stream().map(Feature::key).reduce((a, b) -> a + ", " + b).orElse("none");
    }

    @Override
    public String toString() {
        return "supported=" + describe(supported()) + "; missing=" + describe(missing());
    }
}
