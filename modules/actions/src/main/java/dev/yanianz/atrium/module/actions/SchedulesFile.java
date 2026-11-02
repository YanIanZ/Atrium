package dev.yanianz.atrium.module.actions;

import dev.yanianz.atrium.core.util.Durations;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.slf4j.Logger;

/**
 * Reads {@code plugins/Atrium/modules/actions/schedules.yml}. Pure file parsing (no Bukkit), so the validation
 * is unit tested.
 *
 * <pre>
 * schedules:
 *   morning:
 *     enabled: true
 *     cron: "0 8 * * *"      # minute hour day-of-month month day-of-week
 *     # or: every: 10m
 *     min-players: 1
 *     max-players: -1
 *     actions:               # action lines, run as the console
 *       - "msg: &lt;prefix&gt;Good morning!"
 *     # or commands: ["say Good morning"]  (raw console commands)
 * </pre>
 */
final class SchedulesFile {
    /** One valid schedule. */
    record Job(String id, boolean enabled, @Nullable CronSchedule cron, @Nullable Duration every, int minPlayers,
            int maxPlayers, List<String> actions) {
        Job {
            actions = List.copyOf(actions);
        }

        boolean intervalBased() {
            return every != null;
        }

        /** Whether the current online count allows a run. */
        boolean allowsPlayers(int online) {
            return online >= minPlayers && (maxPlayers < 0 || online <= maxPlayers);
        }
    }

    private SchedulesFile() {}

    /** Commented example written when the file does not exist yet. No schedule is active by default. */
    private static final String EXAMPLE = """
            # Scheduled action lists, run by the actions module on the global scheduler.
            #
            # One entry per schedule. The id is used by /actions schedules run <id>.
            # Do not use on/off/yes/no/true/false as an id: YAML reads those as booleans.
            #   cron: "0 8 * * *"   minute hour day-of-month month day-of-week
            #                       (* any, 5 */5, 1-5 a range, 1,15 a list, MON..SUN / JAN..DEC names)
            #                       day-of-month and day-of-week: when both are set, either may match
            #   every: 10m          the alternative to cron: a fixed interval (minimum 10s)
            #   min-players: 0      skip the run when fewer players are online
            #   max-players: -1     skip the run when more players are online (-1 = no upper limit)
            #   actions: [...]      action lines (see modules/actions.yml), run as the console
            #   commands: [...]     raw console commands without a leading '/'
            #
            # A run missed while the server was down or lagging is skipped; it is never replayed.
            #
            # schedules:
            #   morning:
            #     enabled: true
            #     cron: "0 8 * * *"
            #     min-players: 1
            #     actions:
            #       - "msg: <prefix><success>Good morning!"
            #   restart-note:
            #     enabled: false
            #     every: 30m
            #     commands:
            #       - "say Remember to read /rules"
            """;

    /** Writes the commented example when the file does not exist. Blocking file I/O. */
    static void writeExampleIfMissing(Path file) throws IOException {
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, EXAMPLE);
    }

    /** Parses the file; a missing file returns an empty list (not an error). Blocking file I/O. */
    static List<Job> load(Path file, Logger logger) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) == 0) {
            return List.of();
        }
        ConfigurationNode root;
        try {
            root = YamlConfigurationLoader.builder().path(file).build().load();
        } catch (ConfigurateException e) {
            throw new IOException("cannot read " + file.getFileName() + ": " + e.getMessage(), e);
        }
        return parse(root, logger);
    }

    /** Validates a parsed tree; problems are logged and the schedule is skipped. */
    static List<Job> parse(ConfigurationNode root, Logger logger) {
        List<Job> jobs = new ArrayList<>();
        for (java.util.Map.Entry<Object, ? extends ConfigurationNode> entry
                : root.node("schedules").childrenMap().entrySet()) {
            String id = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                logger.warn("Schedule '{}' skipped: ids use a-z, 0-9, _ and -", id);
                continue;
            }
            try {
                Optional<Job> job = parseOne(id, entry.getValue());
                job.ifPresent(jobs::add);
            } catch (RuntimeException e) {
                logger.warn("Schedule '{}' skipped: {}", id, e.getMessage());
            }
        }
        return jobs;
    }

    private static void addActions(List<String> target, ConfigurationNode node, String key, boolean console) {
        List<String> lines;
        try {
            lines = node.node(key).getList(String.class, List.of());
        } catch (org.spongepowered.configurate.serialize.SerializationException e) {
            throw new IllegalArgumentException("'" + key + "' must be a list of lines");
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            target.add(console ? "console: " + line.strip() : line);
        }
    }

    private static Optional<Job> parseOne(String id, ConfigurationNode node) {
        boolean enabled = node.node("enabled").getBoolean(true);
        String cronText = node.node("cron").getString("");
        String everyText = node.node("every").getString("");
        CronSchedule cron = null;
        Duration every = null;
        if (cronText != null && !cronText.isBlank()) {
            if (everyText != null && !everyText.isBlank()) {
                throw new IllegalArgumentException("use either 'cron' or 'every', not both");
            }
            cron = CronSchedule.parse(cronText);
        } else if (everyText != null && !everyText.isBlank()) {
            every = Durations.parse(everyText)
                    .orElseThrow(() -> new IllegalArgumentException("'every' must be a duration such as 30s or 10m"));
            if (every.toSeconds() < 10) {
                throw new IllegalArgumentException("'every' must be at least 10 seconds");
            }
        } else {
            throw new IllegalArgumentException("'cron' or 'every' is required");
        }
        int minPlayers = node.node("min-players").getInt(0);
        int maxPlayers = node.node("max-players").getInt(-1);
        if (minPlayers < 0) {
            throw new IllegalArgumentException("'min-players' cannot be negative");
        }
        if (maxPlayers >= 0 && maxPlayers < minPlayers) {
            throw new IllegalArgumentException("'max-players' must be -1 or at least 'min-players'");
        }
        List<String> actions = new ArrayList<>();
        addActions(actions, node, "actions", false);
        addActions(actions, node, "commands", true);
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("'actions' or 'commands' must not be empty");
        }
        return Optional.of(new Job(id, enabled, cron, every, minPlayers, maxPlayers, actions));
    }
}
