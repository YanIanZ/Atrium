package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.display.Animations;
import dev.yanianz.atrium.module.display.Targeting;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Immutable, validated view of {@link ScoreboardConfig}; replaced as a whole on reload. */
record ScoreboardSettings(BackendMode backend, int intervalTicks, List<BoardSpec> boards, Animations animations,
        TabSidebarSync.DefaultMode tabDefault) {
    static final int MAX_LINES = 15;

    record BoardSpec(String name, Targeting targeting, String title, List<String> lines) {
        BoardSpec {
            lines = List.copyOf(lines);
        }
    }

    static ScoreboardSettings from(ScoreboardConfig config, BiConsumer<String, String> problems) {
        int interval = clamp(config.updateIntervalTicks, 2, 1200, "update-interval-ticks", problems);
        List<BoardSpec> boards = new ArrayList<>();
        if (config.boards != null) {
            for (ScoreboardConfig.Board b : config.boards) {
                if (b == null) {
                    continue;
                }
                String name = b.name == null || b.name.isBlank() ? "board-" + boards.size() : b.name;
                List<String> lines = new ArrayList<>();
                if (b.lines != null) {
                    for (String line : b.lines) {
                        lines.add(line == null ? "" : line);
                    }
                }
                if (lines.size() > MAX_LINES) {
                    problems.accept("boards." + name, lines.size() + " lines; only the first " + MAX_LINES + " are shown");
                    lines = new ArrayList<>(lines.subList(0, MAX_LINES));
                }
                boards.add(new BoardSpec(name, new Targeting(b.worlds, b.permission),
                        b.title == null ? "" : b.title, lines));
            }
        }
        return new ScoreboardSettings(BackendMode.parse(config.backend, problems), interval, List.copyOf(boards),
                Animations.from(config.animations, (n, problem) -> problems.accept("animations." + n, problem)),
                TabSidebarSync.DefaultMode.parse(config.tabDefaultScoreboard, problems));
    }

    static int clamp(int value, int min, int max, String option, BiConsumer<String, String> problems) {
        if (value < min || value > max) {
            int fixed = Math.clamp(value, min, max);
            problems.accept(option, value + " is outside " + min + "-" + max + "; using " + fixed);
            return fixed;
        }
        return value;
    }

    /** First board for this world and permissions, or {@code null}. */
    BoardSpec boardFor(String world, Predicate<String> hasPermission) {
        for (BoardSpec board : boards) {
            if (board.targeting().matches(world, hasPermission)) {
                return board;
            }
        }
        return null;
    }
}
