package dev.yanianz.atrium.module.seen;

import dev.yanianz.atrium.api.user.PlayerRef;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Pure formatting and grouping logic (no Bukkit state), unit tested. */
final class SeenFormat {
    private SeenFormat() {}

    /** Builds the date formatter; throws {@link IllegalArgumentException} for an invalid pattern or zone. */
    static DateTimeFormatter formatter(String pattern, String zone) {
        ZoneId id;
        try {
            id = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid time zone '" + zone + "'", e);
        }
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(id);
    }

    static String absolute(Instant instant, DateTimeFormatter formatter) {
        return formatter.format(instant);
    }

    /** Time since {@code then}, never negative (clock skew between servers sharing a database). */
    static Duration since(Instant then, Instant now) {
        Duration d = Duration.between(then, now);
        return d.isNegative() ? Duration.ZERO : d;
    }

    /** Names of the other accounts, de-duplicated and sorted case-insensitively. */
    static List<String> otherNames(Collection<PlayerRef> accounts, UUID self) {
        Set<String> names = new LinkedHashSet<>();
        accounts.stream()
                .filter(ref -> !ref.uuid().equals(self))
                .map(PlayerRef::name)
                .filter(name -> name != null && !name.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(names::add);
        return List.copyOf(names);
    }

    /** One group of the player list; {@code group == null} collects players matching no configured group. */
    record Group<T>(@Nullable ListGroup group, List<T> members) {}

    /**
     * Puts every player into the first group whose permission they have (blank permission = everyone). Empty
     * groups are omitted; members are sorted by name, case-insensitively.
     */
    static <T> List<Group<T>> group(List<ListGroup> groups, Collection<T> players, BiPredicate<T, String> hasPermission,
            Function<T, String> name) {
        List<List<T>> buckets = new ArrayList<>();
        groups.forEach(g -> buckets.add(new ArrayList<>()));
        List<T> other = new ArrayList<>();
        for (T player : players) {
            boolean placed = false;
            for (int i = 0; i < groups.size() && !placed; i++) {
                String permission = groups.get(i).permission;
                if (permission == null || permission.isBlank() || hasPermission.test(player, permission.trim())) {
                    buckets.get(i).add(player);
                    placed = true;
                }
            }
            if (!placed) {
                other.add(player);
            }
        }
        Comparator<T> byName = Comparator.comparing(name, String.CASE_INSENSITIVE_ORDER);
        List<Group<T>> result = new ArrayList<>();
        for (int i = 0; i < groups.size(); i++) {
            if (!buckets.get(i).isEmpty()) {
                buckets.get(i).sort(byName);
                result.add(new Group<>(groups.get(i), List.copyOf(buckets.get(i))));
            }
        }
        if (!other.isEmpty()) {
            other.sort(byName);
            result.add(new Group<>(null, List.copyOf(other)));
        }
        return result;
    }
}
