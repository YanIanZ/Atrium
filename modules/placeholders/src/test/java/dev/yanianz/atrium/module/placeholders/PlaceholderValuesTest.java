package dev.yanianz.atrium.module.placeholders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PlaceholderValuesTest {
    private static final PlaceholderValues.Texts TEXTS = new PlaceholderValues.Texts("-", "yes", "no");

    private static PlaceholderValues.Snapshot snapshot(BigDecimal balance, Long playtime) {
        return new PlaceholderValues.Snapshot(
                "Alice", "Ali", balance, balance == null ? null : "1,234.50",
                null, null, null, null,
                playtime, "member", "vip",
                7, 9, 4,
                "world", "survival", 19.5, 20.0, 18, 12, 42);
    }

    @Test
    void formatsShortNumbersWithLocaleIndependentSuffixes() {
        assertEquals("0", PlaceholderValues.shortNumber(new BigDecimal("0")));
        assertEquals("999", PlaceholderValues.shortNumber(new BigDecimal("999")));
        assertEquals("1k", PlaceholderValues.shortNumber(new BigDecimal("1000")));
        assertEquals("1.2k", PlaceholderValues.shortNumber(new BigDecimal("1234")));
        assertEquals("1.5k", PlaceholderValues.shortNumber(new BigDecimal("1500")));
        assertEquals("1.3m", PlaceholderValues.shortNumber(new BigDecimal("1250000")));
        assertEquals("1m", PlaceholderValues.shortNumber(new BigDecimal("999950")));
        assertEquals("1b", PlaceholderValues.shortNumber(new BigDecimal("999999999")));
        assertEquals("-1.2k", PlaceholderValues.shortNumber(new BigDecimal("-1234")));
        assertEquals("12.34", PlaceholderValues.shortNumber(new BigDecimal("12.34")));
    }

    @Test
    void printsExactDecimalsWithoutAnExponent() {
        assertEquals("1234.5", PlaceholderValues.plain(new BigDecimal("1234.50")));
        assertEquals("1000", PlaceholderValues.plain(new BigDecimal("1E+3")));
        assertEquals("0", PlaceholderValues.plain(BigDecimal.ZERO));
        assertEquals("", PlaceholderValues.plain(null));
    }

    @Test
    void formatsPlaytimeForHumans() {
        assertEquals("0s", PlaceholderValues.formatDuration(0));
        assertEquals("59s", PlaceholderValues.formatDuration(59));
        assertEquals("1m", PlaceholderValues.formatDuration(60));
        assertEquals("1h 1m 1s", PlaceholderValues.formatDuration(3661));
        assertEquals("1d 1h 1m 1s", PlaceholderValues.formatDuration(90061));
        assertEquals("0s", PlaceholderValues.formatDuration(-5));
    }

    @Test
    void formatsPlaytimeHoursWithTwoDecimals() {
        assertEquals("0", PlaceholderValues.playtimeHours(0));
        assertEquals("0.02", PlaceholderValues.playtimeHours(59));
        assertEquals("1", PlaceholderValues.playtimeHours(3600));
        assertEquals("1.5", PlaceholderValues.playtimeHours(5400));
        assertEquals("25", PlaceholderValues.playtimeHours(90000));
    }

    @Test
    void stripsFormattingAndPlaceholderTokensFromPlayerText() {
        assertEquals("Bob", PlaceholderValues.sanitizePlayerText("&cBob"));
        assertEquals("Bob", PlaceholderValues.sanitizePlayerText("&#FF0000Bob"));
        assertEquals("100", PlaceholderValues.sanitizePlayerText("100%"));
        assertEquals("player_nameBob", PlaceholderValues.sanitizePlayerText("%player_name%Bob"));
        assertEquals("\\<red>Bob\\</red>", PlaceholderValues.sanitizePlayerText("<red>Bob</red>"));
        assertEquals("a\\<b", PlaceholderValues.sanitizePlayerText("a<b"));
        assertEquals("back\\\\slash", PlaceholderValues.sanitizePlayerText("back\\slash"));
        assertEquals("", PlaceholderValues.sanitizePlayerText(null));
    }

    @Test
    void resolvesEveryDocumentedKey() {
        PlaceholderValues.Snapshot full = snapshot(new BigDecimal("1234.5"), 3725L);
        assertEquals("1234.5", PlaceholderValues.resolve("balance", full, TEXTS));
        assertEquals("1,234.50", PlaceholderValues.resolve("balance_formatted", full, TEXTS));
        assertEquals("1.2k", PlaceholderValues.resolve("balance_short", full, TEXTS));
        assertEquals("3725", PlaceholderValues.resolve("playtime_seconds", full, TEXTS));
        assertEquals("1h 2m 5s", PlaceholderValues.resolve("playtime_formatted", full, TEXTS));
        assertEquals("1.03", PlaceholderValues.resolve("playtime_hours", full, TEXTS));
        assertEquals("Alice", PlaceholderValues.resolve("name", full, TEXTS));
        assertEquals("Ali", PlaceholderValues.resolve("nickname", full, TEXTS));
        assertEquals("member", PlaceholderValues.resolve("rank", full, TEXTS));
        assertEquals("vip", PlaceholderValues.resolve("rank_next", full, TEXTS));
        assertEquals("7", PlaceholderValues.resolve("online_visible", full, TEXTS));
        assertEquals("9", PlaceholderValues.resolve("online_total", full, TEXTS));
        assertEquals("4", PlaceholderValues.resolve("warps_count", full, TEXTS));
        assertEquals("19.5", PlaceholderValues.resolve("health", full, TEXTS));
        assertEquals("20", PlaceholderValues.resolve("max_health", full, TEXTS));
        assertEquals("18", PlaceholderValues.resolve("food_level", full, TEXTS));
        assertEquals("12", PlaceholderValues.resolve("level", full, TEXTS));
        assertEquals("survival", PlaceholderValues.resolve("gamemode", full, TEXTS));
        assertEquals("world", PlaceholderValues.resolve("world", full, TEXTS));
        assertEquals("42", PlaceholderValues.resolve("ping", full, TEXTS));
    }

    @Test
    void returnsTheFallbackWhenAServiceIsMissing() {
        PlaceholderValues.Snapshot empty = new PlaceholderValues.Snapshot(
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        for (String key : PlaceholderValues.KEYS) {
            assertEquals("-", PlaceholderValues.resolve(key, empty, TEXTS), key);
        }
        assertEquals("-", PlaceholderValues.resolve("not_a_placeholder", empty, TEXTS));
    }

    @Test
    void usesTheConfiguredBooleanTexts() {
        PlaceholderValues.Snapshot yes = new PlaceholderValues.Snapshot(
                null, null, null, null, true, true, true, false, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertEquals("yes", PlaceholderValues.resolve("afk", yes, TEXTS));
        assertEquals("yes", PlaceholderValues.resolve("vanished", yes, TEXTS));
        assertEquals("yes", PlaceholderValues.resolve("muted", yes, TEXTS));
        assertEquals("no", PlaceholderValues.resolve("shadow_muted", yes, TEXTS));

        PlaceholderValues.Texts custom = new PlaceholderValues.Texts("?", "on", "off");
        PlaceholderValues.Snapshot no = new PlaceholderValues.Snapshot(
                null, null, null, null, false, false, false, false, null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        assertEquals("off", PlaceholderValues.resolve("afk", no, custom));
    }
}
