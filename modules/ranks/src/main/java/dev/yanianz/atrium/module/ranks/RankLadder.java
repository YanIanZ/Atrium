package dev.yanianz.atrium.module.ranks;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Validated, immutable rank ladder. The configured list order is the ladder order. */
final class RankLadder {
    record Rank(String id, String displayName, String group, long playtimeSeconds, BigDecimal money,
            String permission, List<String> commands, boolean autoRankup) {
        Rank {
            commands = List.copyOf(commands);
        }
    }

    private final List<Rank> ranks;
    private final String startingRank;
    private final Map<String, Rank> byId;

    private RankLadder(List<Rank> ranks, String startingRank) {
        this.ranks = List.copyOf(ranks);
        this.startingRank = startingRank;
        Map<String, Rank> index = new HashMap<>();
        for (Rank rank : this.ranks) {
            index.put(rank.id().toLowerCase(Locale.ROOT), rank);
        }
        this.byId = Map.copyOf(index);
    }

    static RankLadder from(RanksConfig config) {
        if (config.ranks == null || config.ranks.isEmpty()) {
            throw new IllegalArgumentException("At least one rank is required");
        }
        List<Rank> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (RanksConfig.Rank source : config.ranks) {
            String id = source.id == null ? "" : source.id.strip();
            if (!id.matches("[A-Za-z0-9_-]{1,32}")) {
                throw new IllegalArgumentException("Rank id must match [A-Za-z0-9_-]{1,32}: " + id);
            }
            if (!ids.add(id.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate rank id: " + id);
            }
            if (source.playtimeSeconds < 0) {
                throw new IllegalArgumentException("Rank " + id + " has negative playtime");
            }
            String group = source.group == null ? "" : source.group.strip();
            if (!group.isEmpty()) {
                if (!group.matches("[A-Za-z0-9_.-]{1,64}")) {
                    throw new IllegalArgumentException("Rank " + id + " has an invalid LuckPerms group: " + group);
                }
                if (!groups.add(group.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Two ranks share the LuckPerms group " + group);
                }
            }
            BigDecimal money;
            try {
                money = new BigDecimal(source.money == null || source.money.isBlank() ? "0" : source.money.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Rank " + id + " has invalid money: " + source.money);
            }
            if (money.signum() < 0) {
                throw new IllegalArgumentException("Rank " + id + " has negative money");
            }
            String permission = source.permission == null ? "" : source.permission.strip();
            if (!permission.isEmpty() && !permission.matches("[A-Za-z0-9_.*-]{1,128}")) {
                throw new IllegalArgumentException("Rank " + id + " has an invalid permission: " + permission);
            }
            List<String> commands = new ArrayList<>();
            if (source.commands != null) {
                for (String raw : source.commands) {
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    String command = raw.strip();
                    if (command.length() > 1_000 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                        throw new IllegalArgumentException("Rank " + id + " has an invalid command");
                    }
                    commands.add(command);
                }
            }
            String display = source.displayName == null || source.displayName.isBlank()
                    ? id : source.displayName.strip();
            if (display.length() > 64) {
                throw new IllegalArgumentException("Rank " + id + " has a display name longer than 64 characters");
            }
            parsed.add(new Rank(id, display, group, source.playtimeSeconds, money, permission, commands,
                    source.automatic));
        }
        String start = config.startingRank == null ? "" : config.startingRank.strip();
        RankLadder ladder = new RankLadder(parsed, start.isEmpty() ? parsed.getFirst().id() : start);
        if (ladder.find(ladder.startingRank).isEmpty()) {
            throw new IllegalArgumentException("starting-rank does not exist: " + config.startingRank);
        }
        return ladder;
    }

    List<Rank> ranks() {
        return ranks;
    }

    String startingRank() {
        return startingRank;
    }

    Optional<Rank> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.strip().toLowerCase(Locale.ROOT)));
    }

    /** The configured starting rank, or the first rank if the id was changed while data was stored. */
    Rank start() {
        return find(startingRank).orElse(ranks.getFirst());
    }

    Optional<Rank> next(Rank current) {
        int index = ranks.indexOf(current);
        if (index < 0) {
            return Optional.empty();
        }
        return index + 1 < ranks.size() ? Optional.of(ranks.get(index + 1)) : Optional.empty();
    }

    Optional<Rank> previous(Rank current) {
        int index = ranks.indexOf(current);
        return index > 0 ? Optional.of(ranks.get(index - 1)) : Optional.empty();
    }

    List<String> ids() {
        return ranks.stream().map(Rank::id).toList();
    }

    /** Groups owned by the ladder; a group change removes only these, never unrelated LuckPerms groups. */
    List<String> managedGroups() {
        return ranks.stream().map(Rank::group).filter(group -> !group.isEmpty()).toList();
    }

    Rank resolve(String storedId) {
        return find(storedId).orElse(start());
    }
}
