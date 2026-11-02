package dev.yanianz.atrium.module.chatbubbles;

import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the per-group bubble settings (show time and scale) from the configured groups and the
 * permissions a player actually holds.
 *
 * <p>Nothing is cached: a rank change applies to the next bubble. A group without a permission matches
 * everyone, so a server can define one default group and any number of rank groups on top. The highest
 * priority wins; on a tie the alphabetically first group name wins so the result is stable.
 */
final class BubbleGroups {
    static final int MIN_SECONDS = 1;
    static final int MAX_SECONDS = 60;
    static final float MIN_SCALE = 0.1f;
    static final float MAX_SCALE = 4.0f;

    /** Effective look of one bubble: a clamped show time and a positive scale. */
    record Style(int showSeconds, float scale) {
        Style {
            showSeconds = Math.clamp(showSeconds, MIN_SECONDS, MAX_SECONDS);
            scale = Math.clamp(scale, MIN_SCALE, MAX_SCALE);
        }
    }

    private BubbleGroups() {}

    /** The highest-priority group {@code hasPermission} accepts, or {@code null} for the flat settings. */
    static ChatBubblesConfig.@Nullable BubbleGroup select(ChatBubblesConfig config,
            Predicate<String> hasPermission) {
        Map<String, ChatBubblesConfig.BubbleGroup> groups = config.groups;
        if (groups == null || groups.isEmpty()) {
            return null;
        }
        ChatBubblesConfig.BubbleGroup best = null;
        String bestName = null;
        for (Map.Entry<String, ChatBubblesConfig.BubbleGroup> entry : groups.entrySet()) {
            ChatBubblesConfig.BubbleGroup group = entry.getValue();
            if (group == null || !grants(group, hasPermission)) {
                continue;
            }
            if (best == null || group.priority > best.priority
                    || (group.priority == best.priority && entry.getKey().compareTo(bestName) < 0)) {
                best = group;
                bestName = entry.getKey();
            }
        }
        return best;
    }

    private static boolean grants(ChatBubblesConfig.BubbleGroup group, Predicate<String> hasPermission) {
        String permission = group.permission == null ? "" : group.permission.strip();
        return permission.isEmpty() || hasPermission.test(permission);
    }

    /** The flat duration and default size, or the values of {@code group} when it overrides them. */
    static Style style(ChatBubblesConfig config, ChatBubblesConfig.@Nullable BubbleGroup group) {
        int seconds = Math.clamp(config.durationSeconds, MIN_SECONDS, MAX_SECONDS);
        float scale = 1.0f;
        if (group != null) {
            if (group.showTimeSeconds >= MIN_SECONDS) {
                seconds = Math.clamp(group.showTimeSeconds, MIN_SECONDS, MAX_SECONDS);
            }
            if (group.scale > 0 && Double.isFinite(group.scale)) {
                scale = (float) group.scale;
            }
        }
        return new Style(seconds, scale);
    }
}
