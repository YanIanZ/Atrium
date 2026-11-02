package dev.yanianz.atrium.module.bossbar;

import java.util.ArrayDeque;
import java.util.Iterator;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

/**
 * Boss bars of one player: the current announcement and the active /bossbarmsg bars. Only used on that
 * player's thread. Expired message bars are simply hidden; nothing is ever executed when a bar ends.
 */
final class PlayerBars {
    /** A /bossbarmsg bar with a countdown. */
    record TimedBar(BossBar bar, long startNanos, long durationNanos) {
        boolean expired(long now) {
            return now - startNanos >= durationNanos;
        }

        float progress(long now) {
            double remaining = 1.0 - (double) (now - startNanos) / durationNanos;
            return (float) Math.clamp(remaining, 0.0, 1.0);
        }
    }

    BossBar announcement;
    long announcementSlot = -1;
    final ArrayDeque<TimedBar> messages = new ArrayDeque<>();

    void hideAnnouncement(Player player) {
        if (announcement != null) {
            player.hideBossBar(announcement);
            announcement = null;
            announcementSlot = -1;
        }
    }

    void addMessage(Player player, TimedBar bar, int max) {
        while (messages.size() >= max) {
            TimedBar oldest = messages.pollFirst();
            if (oldest != null) {
                player.hideBossBar(oldest.bar());
            }
        }
        messages.addLast(bar);
        player.showBossBar(bar.bar());
    }

    void tickMessages(Player player, long now) {
        Iterator<TimedBar> it = messages.iterator();
        while (it.hasNext()) {
            TimedBar timed = it.next();
            if (timed.expired(now)) {
                player.hideBossBar(timed.bar());
                it.remove();
            } else {
                float progress = timed.progress(now);
                if (Math.abs(timed.bar().progress() - progress) > 0.001f) {
                    timed.bar().progress(progress);
                }
            }
        }
    }

    void hideAll(Player player) {
        hideAnnouncement(player);
        for (TimedBar timed : messages) {
            player.hideBossBar(timed.bar());
        }
        messages.clear();
    }

    boolean isEmpty() {
        return announcement == null && messages.isEmpty();
    }
}
