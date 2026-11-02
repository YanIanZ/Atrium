package dev.yanianz.atrium.module.skins;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Pure rules: which names may be looked up and when a cached texture is still usable. Unit tested. */
final class SkinNames {
    /** Minecraft account names. Anything else is rejected before any lookup happens. */
    static final Pattern ACCOUNT_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");
    /** Cached rows dated further in the future than this are treated as expired (clock changes). */
    static final long CLOCK_SKEW_MILLIS = 60_000L;

    private SkinNames() {}

    static boolean valid(@Nullable String name) {
        return name != null && ACCOUNT_NAME.matcher(name).matches();
    }

    /** Cache key: names are case-insensitive. */
    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** Whether a texture fetched at {@code fetchedAt} may still be used at {@code now} with time-to-live {@code ttl}. */
    static boolean fresh(long fetchedAt, long now, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || fetchedAt <= 0) {
            return false;
        }
        if (fetchedAt > now + CLOCK_SKEW_MILLIS) {
            return false;
        }
        return now - fetchedAt < ttl.toMillis();
    }
}
