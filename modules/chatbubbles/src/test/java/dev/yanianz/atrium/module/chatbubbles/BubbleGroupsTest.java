package dev.yanianz.atrium.module.chatbubbles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BubbleGroupsTest {
    private static ChatBubblesConfig config(int durationSeconds) {
        ChatBubblesConfig config = new ChatBubblesConfig();
        config.durationSeconds = durationSeconds;
        return config;
    }

    private static ChatBubblesConfig.BubbleGroup group(String permission, int priority, int seconds, double scale) {
        ChatBubblesConfig.BubbleGroup group = new ChatBubblesConfig.BubbleGroup();
        group.permission = permission;
        group.priority = priority;
        group.showTimeSeconds = seconds;
        group.scale = scale;
        return group;
    }

    private static void put(ChatBubblesConfig config, String name, ChatBubblesConfig.BubbleGroup group) {
        if (config.groups == null) {
            config.groups = new LinkedHashMap<>();
        }
        config.groups.put(name, group);
    }

    @Test
    void emptyGroupsUseTheFlatSettings() {
        ChatBubblesConfig config = config(6);
        assertNull(BubbleGroups.select(config, permission -> true));
        BubbleGroups.Style style = BubbleGroups.style(config, null);
        assertEquals(6, style.showSeconds());
        assertEquals(1.0f, style.scale());
    }

    @Test
    void wrongMapIsTreatedAsEmpty() {
        ChatBubblesConfig config = config(4);
        config.groups = null;
        assertNull(BubbleGroups.select(config, permission -> true));
    }

    @Test
    void highestPriorityWins() {
        ChatBubblesConfig config = config(6);
        ChatBubblesConfig.BubbleGroup member = group("group.member", 1, 5, 1.0);
        ChatBubblesConfig.BubbleGroup vip = group("group.vip", 10, 12, 1.5);
        put(config, "member", member);
        put(config, "vip", vip);
        Set<String> held = Set.of("group.member", "group.vip");
        assertSame(vip, BubbleGroups.select(config, held::contains));
        assertSame(member, BubbleGroups.select(config, "group.member"::equals));
    }

    @Test
    void tieIsBrokenByNameSoTheResultIsStable() {
        ChatBubblesConfig config = config(6);
        ChatBubblesConfig.BubbleGroup beta = group("group.a", 3, 8, 1.0);
        ChatBubblesConfig.BubbleGroup alpha = group("group.b", 3, 9, 1.0);
        put(config, "beta", beta);
        put(config, "alpha", alpha);
        assertSame(alpha, BubbleGroups.select(config, permission -> true));
    }

    @Test
    void blankPermissionMatchesEveryone() {
        ChatBubblesConfig config = config(6);
        ChatBubblesConfig.BubbleGroup all = group("  ", 0, 3, 1.0);
        put(config, "all", all);
        assertSame(all, BubbleGroups.select(config, permission -> false));
    }

    @Test
    void negativeOverridesFallBackToTheFlatSettings() {
        ChatBubblesConfig config = config(9);
        ChatBubblesConfig.BubbleGroup group = group("group.x", 1, -1, -1);
        BubbleGroups.Style style = BubbleGroups.style(config, group);
        assertEquals(9, style.showSeconds());
        assertEquals(1.0f, style.scale());
    }

    @Test
    void valuesAreClamped() {
        ChatBubblesConfig config = config(6);
        BubbleGroups.Style huge = BubbleGroups.style(config, group("group.x", 1, 999, 500));
        assertEquals(BubbleGroups.MAX_SECONDS, huge.showSeconds());
        assertEquals(BubbleGroups.MAX_SCALE, huge.scale());
        BubbleGroups.Style broken = BubbleGroups.style(config, group("group.x", 1, 60, 0));
        assertEquals(60, broken.showSeconds());
        assertEquals(1.0f, broken.scale(), "a zero scale keeps the default size");
        config.durationSeconds = 0;
        assertEquals(1, BubbleGroups.style(config, null).showSeconds());
        assertEquals(60, BubbleGroups.style(config, group("group.x", 1, 5000, 1)).showSeconds());
    }

    @Test
    void nonFiniteScaleIsIgnored() {
        ChatBubblesConfig config = config(6);
        BubbleGroups.Style style = BubbleGroups.style(config, group("group.x", 1, 6, Double.NaN));
        assertEquals(1.0f, style.scale());
    }

    @Test
    void nullEntriesAreSkipped() {
        ChatBubblesConfig config = config(6);
        Map<String, ChatBubblesConfig.BubbleGroup> groups = new LinkedHashMap<>();
        groups.put("broken", null);
        groups.put("ok", group("group.ok", 2, 7, 1.0));
        config.groups = groups;
        assertEquals("group.ok", BubbleGroups.select(config, permission -> true).permission);
    }
}
