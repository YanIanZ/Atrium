package dev.yanianz.atrium.module.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.scoreboard.TabSidebarPlan.AddLine;
import dev.yanianz.atrium.module.scoreboard.TabSidebarPlan.Op;
import dev.yanianz.atrium.module.scoreboard.TabSidebarPlan.RemoveLine;
import dev.yanianz.atrium.module.scoreboard.TabSidebarPlan.SetLine;
import dev.yanianz.atrium.module.scoreboard.TabSidebarPlan.Title;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TabSidebarPlanTest {
    /** Stands in for a TAB scoreboard: same operations, same index rules. */
    private static final class FakeBoard {
        String title;
        final List<String> lines;
        int calls;

        FakeBoard(String title, List<String> lines) {
            this.title = title;
            this.lines = new ArrayList<>(lines);
        }

        void apply(List<Op> ops) {
            for (Op op : ops) {
                calls++;
                switch (op) {
                    case Title t -> title = t.text();
                    case SetLine set -> lines.set(set.index(), set.text());
                    case AddLine add -> lines.add(add.text());
                    case RemoveLine remove -> lines.remove(remove.index());
                }
            }
        }
    }

    private static FakeBoard run(String title, List<String> lines, String newTitle, List<String> newLines) {
        FakeBoard board = new FakeBoard(title, lines);
        board.apply(TabSidebarPlan.plan(title, lines, newTitle, newLines));
        assertEquals(newTitle, board.title);
        assertEquals(newLines, board.lines);
        return board;
    }

    @Test
    void unchangedSidebarNeedsNoCall() {
        assertTrue(TabSidebarPlan.plan("t", List.of("a", "b"), "t", List.of("a", "b")).isEmpty());
        assertTrue(TabSidebarPlan.plan("", List.of(), "", List.of()).isEmpty());
    }

    @Test
    void onlyChangedLinesAndTitleAreSent() {
        assertEquals(List.of(new SetLine(1, "B")),
                TabSidebarPlan.plan("t", List.of("a", "b", "c"), "t", List.of("a", "B", "c")));
        assertEquals(List.of(new Title("T")), TabSidebarPlan.plan("t", List.of("a"), "T", List.of("a")));
        assertEquals(1, run("t", List.of("a", "b", "c"), "t", List.of("a", "x", "c")).calls);
    }

    @Test
    void growingAppendsWithoutTouchingUnchangedLines() {
        List<Op> ops = TabSidebarPlan.plan("t", List.of("a"), "t", List.of("a", "b", "c"));
        assertEquals(List.of(new AddLine("b"), new AddLine("c")), ops);
        run("t", List.of("a"), "t", List.of("a", "b", "c"));
    }

    @Test
    void shrinkingRemovesFromTheBottomUp() {
        List<Op> ops = TabSidebarPlan.plan("t", List.of("a", "b", "c", "d"), "t", List.of("a", "x"));
        assertEquals(List.of(new SetLine(1, "x"), new RemoveLine(3), new RemoveLine(2)), ops);
        run("t", List.of("a", "b", "c", "d"), "t", List.of("a", "x"));
        run("t", List.of("a", "b"), "t", List.of());
    }

    @Test
    void duplicateAndEmptyLinesStayDistinct() {
        run("", List.of("", "", "x"), "new", List.of("x", "", "", ""));
        run("t", List.of("same", "same"), "t", List.of("same", "other", "same"));
    }
}
