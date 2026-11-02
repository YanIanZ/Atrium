package dev.yanianz.atrium.module.scoreboard;

import java.util.ArrayList;
import java.util.List;

/**
 * The TAB scoreboard calls that turn the sidebar TAB currently holds into the new one. TAB numbers the lines
 * itself, so only text changes matter: changed lines are edited in place, new lines are appended and surplus
 * lines are removed from the bottom up (so the remaining indexes stay valid). An unchanged sidebar needs no call.
 */
final class TabSidebarPlan {
    sealed interface Op permits Title, SetLine, AddLine, RemoveLine {}

    record Title(String text) implements Op {}

    record SetLine(int index, String text) implements Op {}

    record AddLine(String text) implements Op {}

    record RemoveLine(int index) implements Op {}

    private TabSidebarPlan() {}

    static List<Op> plan(String shownTitle, List<String> shownLines, String title, List<String> lines) {
        List<Op> ops = new ArrayList<>();
        if (!title.equals(shownTitle)) {
            ops.add(new Title(title));
        }
        List<Op> removals = new ArrayList<>();
        for (LineDiff.Change change : LineDiff.diff(shownLines, lines)) {
            switch (change.kind()) {
                case UPDATE -> ops.add(new SetLine(change.index(), lines.get(change.index())));
                case ADD -> ops.add(new AddLine(lines.get(change.index())));
                case REMOVE -> removals.add(0, new RemoveLine(change.index()));
                case RESCORE -> {
                    // TAB scores the lines itself.
                }
            }
        }
        ops.addAll(removals);
        return List.copyOf(ops);
    }
}
