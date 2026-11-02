package dev.yanianz.atrium.module.moderation;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Server-wide chat mute ({@code /mutechat}). The state is a deadline, not a flag, so an expired mute never
 * needs a task to switch it off: every chat message compares the deadline with the current time.
 */
record MuteChatState(@Nullable Instant until, String reason, String actorName) {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MuteChatState.class);
    static final MuteChatState NONE = new MuteChatState(null, "", "");

    MuteChatState {
        reason = reason == null ? "" : reason;
        actorName = actorName == null ? "" : actorName;
        // The deadline is stored in milliseconds; normalising it here keeps memory and storage identical.
        until = until == null ? null : Instant.ofEpochMilli(until.toEpochMilli());
    }

    boolean muted(Instant now) {
        return until != null && until.isAfter(now);
    }

    /** Time left, empty when chat is not muted. */
    Optional<Duration> remaining(Instant now) {
        if (!muted(now)) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, until));
    }

    MuteChatState until(Instant deadline, String reason, String actorName) {
        return new MuteChatState(deadline, reason, actorName);
    }

    String reasonOr(String fallback) {
        return reason.isBlank() ? fallback : reason;
    }

    static MuteChatState decode(@Nullable String raw, @Nullable String reason, @Nullable String actorName) {
        Instant deadline = null;
        if (raw != null) {
            try {
                long millis = Long.parseLong(raw.strip());
                if (millis > 0) {
                    deadline = Instant.ofEpochMilli(millis);
                }
            } catch (NumberFormatException e) {
                // Unreadable deadline: treat the mute as lifted instead of muting forever.
                LOG.warn("Stored chat mute deadline '{}' is unreadable; chat stays unmuted", raw);
            }
        }
        return new MuteChatState(deadline, reason == null ? "" : reason, actorName == null ? "" : actorName);
    }

    static String normaliseReason(@Nullable String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String text = raw.strip();
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    static String stateKey() {
        return "mutechat";
    }
}
