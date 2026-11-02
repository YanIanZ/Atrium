package dev.yanianz.atrium.module.tablist;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Team names for the TAB engine's sorting. TAB sorts the tab list through scoreboard teams, and the client
 * orders teams by name, so Atrium's group order becomes a fixed-width team name of at most 16 characters
 * (TAB's limit):
 * <pre>
 *   GG KKKKKKKKKKKK DD
 *   │  │            └ disambiguator (base 36) among players with the same group and key
 *   │  └ sort key: lower-case name, [a-z0-9_] only, padded with '.' so shorter names sort first
 *   └ group position, 00 = first group; players without a group sort last
 * </pre>
 * Every online player gets a distinct name (TAB uses a forced team name as it is), as long as fewer than 1296
 * players share one group and key.
 */
final class TabTeamNames {
    static final int MAX_LENGTH = 16;
    static final int KEY_LENGTH = 12;
    private static final char PAD = '.';

    /** One player to sort: their group position (0 = top, {@code groupCount} = none) and sort key. */
    record Member(UUID id, int groupIndex, String key) {}

    private TabTeamNames() {}

    static Map<UUID, String> assign(Collection<Member> members) {
        List<Member> sorted = new ArrayList<>(members);
        // UUID order makes the disambiguator stable between cycles.
        sorted.sort(Comparator.comparing(Member::id));
        Map<String, Integer> used = new HashMap<>();
        Map<UUID, String> names = new HashMap<>(sorted.size() * 2);
        for (Member member : sorted) {
            String base = group(member.groupIndex()) + key(member.key());
            int index = used.merge(base, 1, Integer::sum) - 1;
            names.put(member.id(), base + suffix(index));
        }
        return names;
    }

    static String group(int groupIndex) {
        return String.format(Locale.ROOT, "%02d", Math.clamp(groupIndex, 0, 99));
    }

    static String key(String raw) {
        String lower = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        StringBuilder key = new StringBuilder(KEY_LENGTH);
        for (int i = 0; i < lower.length() && key.length() < KEY_LENGTH; i++) {
            char c = lower.charAt(i);
            key.append(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' ? c : '_');
        }
        while (key.length() < KEY_LENGTH) {
            key.append(PAD);
        }
        return key.toString();
    }

    /** Two base-36 characters; more than 1296 identical names wrap, which only affects their relative order. */
    static String suffix(int index) {
        String digits = Integer.toString(Math.floorMod(index, 36 * 36), 36);
        return digits.length() == 1 ? "0" + digits : digits;
    }
}
