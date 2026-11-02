package dev.yanianz.atrium.module.kits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Group weights: what is offered and what a lower tier resolves to. */
class KitGroupsTest {
    private static Kit kit(String name, String group, int weight, boolean showDespiteWeight) {
        return new Kit(name, 0, false, false, BigDecimal.ZERO, new byte[0], null, "",
                -1, group, weight, showDespiteWeight, 0, 0);
    }

    @Test
    void anUngroupedKitAlwaysResolvesToItself() {
        Kit solo = kit("solo", "", 0, false);
        assertEquals(solo, KitGroups.resolve(solo, List.of(solo)).orElseThrow());
    }

    @Test
    void theHighestAccessibleWeightWins() {
        Kit tier1 = kit("vip1", "vip", 1, false);
        Kit tier2 = kit("vip2", "vip", 2, false);
        Kit other = kit("vip3", "vip", 3, false);

        assertEquals(tier2, KitGroups.resolve(tier1, List.of(tier1, tier2)).orElseThrow(),
                "a lower tier is replaced by the highest one the player has");
        assertEquals(tier2, KitGroups.resolve(tier2, List.of(tier1, tier2)).orElseThrow());
        assertEquals(other, KitGroups.resolve(tier1, List.of(tier1, other)).orElseThrow());
        assertEquals(tier1, KitGroups.resolve(tier1, List.of(tier1)).orElseThrow(),
                "a player without the higher kit keeps the lower one");
    }

    @Test
    void anotherGroupNeverReplacesAKit() {
        Kit vip = kit("vip1", "vip", 1, false);
        Kit mvp = kit("mvp1", "mvp", 9, false);
        assertEquals(vip, KitGroups.resolve(vip, List.of(vip, mvp)).orElseThrow());
    }

    @Test
    void onlyTheTopTierAndOptedOutKitsAreListed() {
        Kit tier1 = kit("vip1", "vip", 1, false);
        Kit tier2 = kit("vip2", "vip", 2, false);
        Kit always = kit("vip-old", "vip", 1, true);
        Kit solo = kit("starter", "", 0, false);

        List<Kit> visible = KitGroups.visible(List.of(tier1, tier2, always, solo));
        assertEquals(List.of(tier2, always, solo), visible);
    }

    @Test
    void aGroupWhoseTopTierIsNotAccessibleListsTheBestOneThatIs() {
        Kit tier1 = kit("vip1", "vip", 1, false);
        Kit tier2 = kit("vip2", "vip", 2, false);

        assertEquals(List.of(tier1), KitGroups.visible(List.of(tier1)), "the caller only passes accessible kits");
        assertTrue(KitGroups.visible(List.of(tier2)).contains(tier2));
    }

    @Test
    void groupNamesAreListedOnceInOrder() {
        List<Kit> kits = List.of(kit("a", "vip", 1, false), kit("b", "vip", 2, false),
                kit("c", "mvp", 1, false), kit("d", "", 0, false));
        assertEquals(List.of("mvp", "vip"), KitGroups.groups(kits));
    }

    @Test
    void groupsAreValidatedAndNormalised() {
        assertTrue(Kit.validGroup("vip_plus-2"));
        assertTrue(Kit.validGroup(""), "an empty group is valid and means ungrouped");
        assertTrue(!Kit.validGroup("a".repeat(33)));
        assertTrue(!Kit.validGroup("vip plus"));
        assertEquals("vip", kit("a", "  VIP ", 1, false).group(), "stored groups are lower-case");
    }

    @Test
    void weightsAndGroupsSurviveTheWithers() {
        Kit base = kit("vip2", "vip", 2, true);
        Kit moved = base.withWeight(5, 1).withGroup("mvp", 1).withShowDespiteWeight(false, 1);
        assertEquals("mvp", moved.group());
        assertEquals(5, moved.weight());
        assertTrue(!moved.showDespiteWeight());
        assertEquals(2, base.weight(), "the original kit is unchanged");
        assertEquals("vip2", base.name());
    }
}
