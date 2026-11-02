package dev.yanianz.atrium.module.playtime;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure reward schedule logic.
 *
 * <p>A watermark is the last delivered occurrence of one reward:
 * <ul>
 *   <li>{@code ONE_TIME}: {@code 1} once the threshold was paid.</li>
 *   <li>{@code REPEATING}: the last paid interval number ({@code total / interval}).</li>
 *   <li>{@code DAILY}: the epoch day of the last paid day.</li>
 * </ul>
 * Changing the interval or type of an existing reward id changes the meaning of its watermark; use a
 * new id when changing a released reward definition.
 */
final class PlaytimeRewards {
    private PlaytimeRewards() {}

    enum Type {
        ONE_TIME,
        REPEATING,
        DAILY;

        static Type parse(String input) {
            if (input == null) {
                throw new IllegalArgumentException("Reward type is missing");
            }
            String normalized = input.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "ONE_TIME", "ONETIME", "ONE" -> ONE_TIME;
                case "REPEATING", "REPEAT" -> REPEATING;
                case "DAILY" -> DAILY;
                default -> throw new IllegalArgumentException("Unknown reward type: " + input);
            };
        }
    }

    record Spec(String id, Type type, long seconds, String permission, List<String> commands, BigDecimal money) {
        Spec {
            commands = List.copyOf(commands);
        }
    }

    /** One delivery. {@code mark} is what is persisted as the reward's watermark after success. */
    record Grant(String rewardId, Type type, long mark) {}

    /** Grants to deliver now; {@code baseline} is stored as done without paying (repeating rewards). */
    record Plan(List<Grant> grants, Long baseline) {
        Plan {
            grants = List.copyOf(grants);
        }

        static final Plan EMPTY = new Plan(List.of(), null);

        static Plan baseline(long mark) {
            return new Plan(List.of(), mark);
        }

        boolean empty() {
            return grants.isEmpty() && baseline == null;
        }
    }

    static Spec spec(PlaytimeConfig.Reward reward) {
        String id = reward.id == null ? "" : reward.id.strip();
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Reward id must match [A-Za-z0-9_-]{1,64}: " + id);
        }
        Type type = Type.parse(reward.type);
        if (type == Type.DAILY) {
            if (reward.seconds < 0) {
                throw new IllegalArgumentException("Daily reward " + id + " needs seconds >= 0");
            }
        } else if (reward.seconds < 60) {
            // A tiny interval would create thousands of queued payouts; one minute is the useful floor.
            throw new IllegalArgumentException("Reward " + id + " needs seconds >= 60");
        }
        String permission = reward.permission == null ? "" : reward.permission.strip();
        if (!permission.isEmpty() && !permission.matches("[A-Za-z0-9_.*-]{1,128}")) {
            throw new IllegalArgumentException("Invalid permission for reward " + id + ": " + permission);
        }
        List<String> commands = new ArrayList<>();
        if (reward.commands != null) {
            for (String raw : reward.commands) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String command = raw.strip();
                if (command.length() > 1_000 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
                    throw new IllegalArgumentException("Reward " + id + " has an invalid command");
                }
                commands.add(command);
            }
        }
        BigDecimal money = BigDecimal.ZERO;
        String rawMoney = reward.money == null ? "" : reward.money.strip();
        if (!rawMoney.isEmpty()) {
            try {
                money = new BigDecimal(rawMoney);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid money amount for reward " + id + ": " + rawMoney);
            }
            if (money.signum() < 0) {
                throw new IllegalArgumentException("Reward " + id + " has negative money");
            }
        }
        return new Spec(id, type, reward.seconds, permission, commands, money);
    }

    /**
     * Work out what is due. {@code watermark} is {@code null} when the reward has never been claimed.
     * Repeating rewards that appear for the first time baseline to the current interval, so installing
     * the module (or adding a reward) never pays a backlog of thousands of commands.
     *
     * <p>At most {@code maxGrants} grants are returned; whatever is still owed stays behind the watermark
     * and is paid by a later check. That cap is the last line of defence against a stale watermark (for
     * example an import that was interrupted between the total write and the watermark advance).
     */
    static Plan plan(Spec spec, Long watermark, long totalSeconds, long secondsToday, LocalDate today,
            int maxGrants) {
        int cap = Math.max(1, maxGrants);
        return switch (spec.type()) {
            case ONE_TIME -> {
                if (totalSeconds < spec.seconds() || (watermark != null && watermark >= 1)) {
                    yield Plan.EMPTY;
                }
                yield new Plan(List.of(new Grant(spec.id(), spec.type(), 1)), null);
            }
            case REPEATING -> {
                long current = totalSeconds / spec.seconds();
                if (watermark == null) {
                    yield Plan.baseline(current);
                }
                if (current <= watermark) {
                    yield Plan.EMPTY;
                }
                List<Grant> grants = new ArrayList<>();
                // Pay the missed intervals, but never more than the configured cap in one check.
                for (long occurrence = watermark + 1; occurrence <= current && grants.size() < cap; occurrence++) {
                    grants.add(new Grant(spec.id(), spec.type(), occurrence));
                }
                yield new Plan(grants, null);
            }
            case DAILY -> {
                long todayMark = today.toEpochDay();
                if (secondsToday < spec.seconds() || (watermark != null && watermark >= todayMark)) {
                    yield Plan.EMPTY;
                }
                yield new Plan(List.of(new Grant(spec.id(), spec.type(), todayMark)), null);
            }
        };
    }

    /**
     * Watermark a repeating reward must have for {@code newTotalSeconds} to pay nothing. Returns
     * {@code null} for reward types that are not interval based.
     *
     * <p>Administrative writes (import, {@code setPlaytime}, {@code addPlaytime}) jump the total. Without
     * this advance the next check would see every interval between the old watermark and the new total as
     * unpaid and pay a backlog (an imported 2000 h would pay the hourly reward 2000 times).
     */
    static Long advanceTo(Spec spec, long newTotalSeconds) {
        if (spec.type() != Type.REPEATING) {
            return null;
        }
        return Math.max(0, newTotalSeconds) / spec.seconds();
    }
}
