package dev.yanianz.atrium.module.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure advertisement filter. It recognises IPv4 addresses (including the common {@code dot} spelling) and
 * domain names. The whitelist only covers a whole host or a real subdomain of a whitelisted host, so
 * {@code google.evil.com} is not whitelisted by {@code google.com}.
 */
final class AdFilter {
    private AdFilter() {}

    enum Action { CENSOR, BLOCK, WARN }

    private static final String TLDS = "com|net|org|edu|gov|mil|int|info|biz|name|pro|tel|travel|museum|coop|aero|"
            + "asia|jobs|mobi|cat|io|co|me|tv|gg|dev|app|xyz|online|site|store|tech|club|link|live|world|"
            + "id|my|sg|uk|us|de|nl|fr|jp|kr|cn|au|ca|ru|br|in";

    private static final Pattern IP = Pattern.compile(
            "(?i)(?<![\\w.])\\d{1,3}(?:\\.|\\s*dot\\s*|\\(dot\\))\\d{1,3}"
                    + "(?:\\.|\\s*dot\\s*|\\(dot\\))\\d{1,3}(?:\\.|\\s*dot\\s*|\\(dot\\))\\d{1,3}(?![\\w.])");

    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)(?<![\\w@.-])((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+(?:" + TLDS + "))(?![\\w-])");

    record Settings(boolean enabled, Action action, String replacement, Set<String> whitelist, boolean blockIps) {
        Settings {
            whitelist = whitelist == null ? Set.of() : Set.copyOf(whitelist);
            replacement = replacement == null ? "" : replacement;
        }

        static Settings of(ChatConfig.Ads config) {
            if (config == null) {
                return new Settings(false, Action.CENSOR, "***", Set.of(), true);
            }
            Action action;
            try {
                action = Action.valueOf(config.action == null ? "CENSOR" : config.action.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                action = Action.CENSOR;
            }
            Set<String> whitelist = new LinkedHashSet<>();
            if (config.whitelist != null) {
                for (String entry : config.whitelist) {
                    String normalised = normaliseHost(entry);
                    if (!normalised.isEmpty()) {
                        whitelist.add(normalised);
                    }
                }
            }
            return new Settings(config.enabled, action, config.replacement, whitelist, config.blockIps);
        }
    }

    record Match(int start, int end, String host, boolean ip) {}

    record Verdict(String text, boolean blocked, List<Match> matches) {
        Verdict {
            matches = List.copyOf(matches);
        }
    }

    static Verdict apply(String raw, Settings settings) {
        String text = raw == null ? "" : raw;
        if (settings == null || !settings.enabled() || text.isEmpty()) {
            return new Verdict(text, false, List.of());
        }
        List<Match> matches = new ArrayList<>();
        if (settings.blockIps()) {
            collect(IP.matcher(text), text, true, settings, matches);
        }
        collect(DOMAIN.matcher(text), text, false, settings, matches);
        if (matches.isEmpty()) {
            return new Verdict(text, false, List.of());
        }
        boolean blocked = settings.action() == Action.BLOCK;
        if (blocked) {
            return new Verdict(text, true, matches);
        }
        if (settings.action() == Action.WARN) {
            return new Verdict(text, false, matches);
        }
        return new Verdict(ChatFilter.censor(text, toFilterMatches(matches), settings.replacement()), false, matches);
    }

    private static void collect(Matcher matcher, String text, boolean ip, Settings settings, List<Match> out) {
        while (matcher.find()) {
            String host = ip ? normaliseIp(matcher.group()) : matcher.group(1).toLowerCase(Locale.ROOT);
            if (host.isEmpty() || whitelisted(host, settings.whitelist())) {
                continue;
            }
            out.add(new Match(matcher.start(ip ? 0 : 1), matcher.end(ip ? 0 : 1), host, ip));
        }
    }

    private static List<ChatFilter.Match> toFilterMatches(List<Match> matches) {
        List<ChatFilter.Match> converted = new ArrayList<>(matches.size());
        for (Match match : matches) {
            converted.add(new ChatFilter.Match("ad", ChatFilter.Action.CENSOR, match.start(), match.end()));
        }
        return converted;
    }

    static boolean whitelisted(String host, Set<String> whitelist) {
        String normalised = normaliseHost(host);
        if (normalised.isEmpty()) {
            return false;
        }
        for (String allowed : whitelist) {
            if (normalised.equals(allowed) || normalised.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    static String normaliseHost(String raw) {
        if (raw == null) {
            return "";
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        while (host.startsWith(".")) {
            host = host.substring(1);
        }
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    /** Turns the {@code 1 dot 2 dot 3 dot 4} spelling into a plain IPv4 string. */
    static String normaliseIp(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace("(dot)", ".")
                .replaceAll("\\s*dot\\s*", ".")
                .replaceAll("\\s+", "");
    }
}
