package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.scoreboard.SidebarBackend.Kind;
import java.util.ArrayList;
import java.util.List;

/** What a sidebar backend offers, shown by {@code /sidebar engine}. */
record SidebarCapabilities(boolean folia, boolean perPlayerText, boolean hiddenNumbers,
        boolean respectsOtherScoreboards) {

    enum Feature {
        /** Works on Folia. */
        FOLIA("folia"),
        /** Atrium renders every line per player (placeholders, animations, conditions). */
        PER_PLAYER_TEXT("per-player-text"),
        /** Atrium hides the score numbers; with TAB, TAB's config decides. */
        HIDDEN_NUMBERS("hidden-numbers"),
        /** A player who has another plugin's scoreboard keeps it. */
        RESPECTS_OTHER_SCOREBOARDS("respects-other-scoreboards");

        final String key;

        Feature(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    static SidebarCapabilities of(Kind kind, boolean folia) {
        return switch (kind) {
            case NONE -> new SidebarCapabilities(false, false, false, false);
            case BUKKIT -> new SidebarCapabilities(false, true, true, true);
            // On Folia there is no scoreboard API to detect another plugin's scoreboard.
            case PACKETS -> new SidebarCapabilities(true, true, true, !folia);
            // TAB itself steps back when another plugin shows a sidebar; its number format is TAB's setting.
            case TAB -> new SidebarCapabilities(true, true, false, true);
        };
    }

    boolean supports(Feature feature) {
        return switch (feature) {
            case FOLIA -> folia;
            case PER_PLAYER_TEXT -> perPlayerText;
            case HIDDEN_NUMBERS -> hiddenNumbers;
            case RESPECTS_OTHER_SCOREBOARDS -> respectsOtherScoreboards;
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

    static String describe(List<Feature> features) {
        return features.stream().map(Feature::key).reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
