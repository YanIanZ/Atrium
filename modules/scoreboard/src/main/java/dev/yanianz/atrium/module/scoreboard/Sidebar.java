package dev.yanianz.atrium.module.scoreboard;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

/**
 * One player's private sidebar. Created and used only on that player's thread. Lines are score entries with
 * a custom display name; the objective hides the numbers with {@link NumberFormat#blank()}.
 */
final class Sidebar {
    static final String OBJECTIVE = "atrium_sidebar";

    private final Scoreboard board;
    private final Objective objective;
    private Component title;
    private List<Component> lines = List.of();

    Sidebar(Scoreboard board, Component title) {
        this.board = board;
        this.title = title;
        this.objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, title);
        objective.numberFormat(NumberFormat.blank());
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    }

    Scoreboard board() {
        return board;
    }

    boolean isShownTo(Player player) {
        return player.getScoreboard() == board;
    }

    /** Applies only what changed. Returns the number of changed lines (title counts as one). */
    int update(Component newTitle, List<Component> newLines) {
        int changed = 0;
        if (!newTitle.equals(title)) {
            objective.displayName(newTitle);
            title = newTitle;
            changed++;
        }
        for (LineDiff.Change change : LineDiff.diff(lines, newLines)) {
            String entry = LineDiff.entry(change.index());
            switch (change.kind()) {
                case ADD, UPDATE -> {
                    Score score = objective.getScore(entry);
                    if (!score.isScoreSet() || score.getScore() != change.score()) {
                        score.setScore(change.score());
                    }
                    score.customName(newLines.get(change.index()));
                    changed++;
                }
                case RESCORE -> objective.getScore(entry).setScore(change.score());
                case REMOVE -> {
                    objective.getScore(entry).resetScore();
                    changed++;
                }
            }
        }
        lines = List.copyOf(newLines);
        return changed;
    }
}
