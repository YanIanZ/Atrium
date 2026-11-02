package dev.yanianz.atrium.module.placeholders;

import dev.yanianz.atrium.core.util.Durations;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Pure value formatting and resolution of the {@code atrium} placeholders.
 *
 * <p>Every input is nullable: {@code null} means "the service that provides it is missing or has no
 * value", and the configured fallback is returned instead. Output is always plain text; values that
 * come from players (the nickname) are stripped of legacy colour codes, of {@code %} tokens and are
 * escaped for MiniMessage so a consumer that parses the result again cannot inject formatting
 * (second-order placeholder injection).
 */
final class PlaceholderValues {
    /** Placeholder names without the {@code atrium_} prefix, in documentation order. */
    static final List<String> KEYS = List.of(
            "balance", "balance_formatted", "balance_short",
            "afk", "vanished", "muted", "shadow_muted",
            "playtime_seconds", "playtime_formatted", "playtime_hours",
            "rank", "rank_next",
            "nickname", "name",
            "online_visible", "online_total",
            "warps_count",
            "health", "max_health", "food_level", "level", "gamemode", "world", "ping");

    private static final Pattern LEGACY = Pattern.compile("[\\u00a7&][0-9a-fk-orxA-FK-ORX]");
    private static final Pattern AMPERSAND_HEX = Pattern.compile("&#[0-9a-fA-F]{6}");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final String[] SUFFIXES = {"k", "m", "b", "t", "q"};

    private PlaceholderValues() {}

    /** Configurable texts for the resolver. */
    record Texts(String fallback, String trueText, String falseText) {
        Texts {
            fallback = fallback == null ? "" : fallback;
            trueText = trueText == null ? "" : trueText;
            falseText = falseText == null ? "" : falseText;
        }
    }

    /**
     * One consistent view of all placeholder inputs. Each component is {@code null} when its source is
     * missing or has no answer; the resolver then returns {@link Texts#fallback()}.
     */
    record Snapshot(
            String name,
            String nickname,
            BigDecimal balance,
            String balanceFormatted,
            Boolean afk,
            Boolean vanished,
            Boolean muted,
            Boolean shadowMuted,
            Long playtimeSeconds,
            String rank,
            String rankNext,
            Integer onlineVisible,
            Integer onlineTotal,
            Integer warpsCount,
            String world,
            String gameMode,
            Double health,
            Double maxHealth,
            Integer foodLevel,
            Integer experienceLevel,
            Integer ping) {}

    static String resolve(String key, Snapshot snapshot, Texts texts) {
        String fallback = texts.fallback();
        return switch (key) {
            case "balance" -> snapshot.balance() == null ? fallback : plain(snapshot.balance());
            case "balance_formatted" -> text(snapshot.balanceFormatted(), fallback);
            case "balance_short" -> snapshot.balance() == null ? fallback : shortNumber(snapshot.balance());
            case "afk" -> booleanText(snapshot.afk(), texts);
            case "vanished" -> booleanText(snapshot.vanished(), texts);
            case "muted" -> booleanText(snapshot.muted(), texts);
            case "shadow_muted" -> booleanText(snapshot.shadowMuted(), texts);
            case "playtime_seconds" ->
                    snapshot.playtimeSeconds() == null ? fallback : Long.toString(snapshot.playtimeSeconds());
            case "playtime_formatted" -> snapshot.playtimeSeconds() == null
                    ? fallback
                    : formatDuration(snapshot.playtimeSeconds());
            case "playtime_hours" -> snapshot.playtimeSeconds() == null
                    ? fallback
                    : playtimeHours(snapshot.playtimeSeconds());
            case "rank" -> text(snapshot.rank(), fallback);
            case "rank_next" -> text(snapshot.rankNext(), fallback);
            case "nickname" -> text(snapshot.nickname(), fallback);
            case "name" -> text(snapshot.name(), fallback);
            case "online_visible" ->
                    snapshot.onlineVisible() == null ? fallback : Integer.toString(snapshot.onlineVisible());
            case "online_total" ->
                    snapshot.onlineTotal() == null ? fallback : Integer.toString(snapshot.onlineTotal());
            case "warps_count" ->
                    snapshot.warpsCount() == null ? fallback : Integer.toString(snapshot.warpsCount());
            case "health" -> snapshot.health() == null ? fallback : decimal(snapshot.health(), 1);
            case "max_health" -> snapshot.maxHealth() == null ? fallback : decimal(snapshot.maxHealth(), 1);
            case "food_level" ->
                    snapshot.foodLevel() == null ? fallback : Integer.toString(snapshot.foodLevel());
            case "level" ->
                    snapshot.experienceLevel() == null ? fallback : Integer.toString(snapshot.experienceLevel());
            case "gamemode" -> text(snapshot.gameMode(), fallback);
            case "world" -> text(snapshot.world(), fallback);
            case "ping" -> snapshot.ping() == null ? fallback : Integer.toString(snapshot.ping());
            default -> fallback;
        };
    }

    /** Compact form of an amount: {@code 999}, {@code 1.2k}, {@code 3.4m}. Locale-independent. */
    static String shortNumber(BigDecimal value) {
        if (value == null) {
            return "";
        }
        if (value.abs().compareTo(THOUSAND) < 0) {
            return value.stripTrailingZeros().toPlainString();
        }
        BigDecimal scaled = value;
        int suffix = -1;
        while (scaled.abs().compareTo(THOUSAND) >= 0 && suffix + 1 < SUFFIXES.length) {
            scaled = scaled.divide(THOUSAND, 2, RoundingMode.HALF_UP);
            suffix++;
        }
        BigDecimal rounded = scaled.setScale(1, RoundingMode.HALF_UP);
        // 999,950 rounds to 1000.0k; promote it instead of printing "1000k".
        if (rounded.abs().compareTo(THOUSAND) >= 0 && suffix + 1 < SUFFIXES.length) {
            rounded = rounded.divide(THOUSAND, 1, RoundingMode.HALF_UP);
            suffix++;
        }
        return rounded.stripTrailingZeros().toPlainString() + SUFFIXES[suffix];
    }

    /** Exact decimal without an exponent: {@code 1234.5}, never {@code 1.2345E+3}. */
    static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    /** Compact duration, e.g. {@code 1d 2h 5m}; seconds when the total is under a minute. */
    static String formatDuration(long seconds) {
        return Durations.format(Duration.ofSeconds(Math.max(0L, seconds)), 4);
    }

    /** Playtime in hours with at most two decimals, never using a locale decimal comma. */
    static String playtimeHours(long seconds) {
        if (seconds <= 0) {
            return "0";
        }
        return BigDecimal.valueOf(seconds)
                .divide(BigDecimal.valueOf(3600L), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** A finite number with the given decimals and no trailing zeros. */
    static String decimal(double value, int scale) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        return BigDecimal.valueOf(value)
                .setScale(Math.max(0, scale), RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    static String booleanText(Boolean value, Texts texts) {
        if (value == null) {
            return texts.fallback();
        }
        return value ? texts.trueText() : texts.falseText();
    }

    private static String text(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /**
     * Makes player-controlled text safe to return from a placeholder.
     *
     * <ul>
     *   <li>Legacy colour codes ({@code &c}, {@code §c}, {@code &#RRGGBB}) are removed.</li>
     *   <li>{@code %} is removed so a second PlaceholderAPI pass cannot resolve a token hidden in a
     *       nickname (second-order placeholder injection).</li>
     *   <li>{@code <} and {@code \} are escaped for MiniMessage, so a consumer that parses the returned
     *       text again shows it literally instead of applying tags.</li>
     * </ul>
     */
    static String sanitizePlayerText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = AMPERSAND_HEX.matcher(text).replaceAll("");
        cleaned = LEGACY.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("%", "");
        return escapeMiniMessage(cleaned);
    }

    private static String escapeMiniMessage(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' || c == '<') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}
