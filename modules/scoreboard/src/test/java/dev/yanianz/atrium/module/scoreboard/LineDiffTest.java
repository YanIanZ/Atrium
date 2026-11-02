package dev.yanianz.atrium.module.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.scoreboard.LineDiff.Change;
import dev.yanianz.atrium.module.scoreboard.LineDiff.Kind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineDiffTest {
    @Test
    void identicalLinesProduceNoChanges() {
        assertTrue(LineDiff.diff(List.of("a", "b"), List.of("a", "b")).isEmpty());
        assertTrue(LineDiff.diff(List.of(), List.of()).isEmpty());
    }

    @Test
    void onlyChangedLinesAreUpdated() {
        assertEquals(List.of(new Change(Kind.UPDATE, 1, 2)),
                LineDiff.diff(List.of("a", "b", "c"), List.of("a", "B", "c")));
    }

    @Test
    void firstRenderAddsEveryLineTopDown() {
        assertEquals(List.of(new Change(Kind.ADD, 0, 3), new Change(Kind.ADD, 1, 2), new Change(Kind.ADD, 2, 1)),
                LineDiff.diff(List.of(), List.of("a", "b", "c")));
    }

    @Test
    void growingRescoresUnchangedLines() {
        assertEquals(List.of(new Change(Kind.RESCORE, 0, 3), new Change(Kind.UPDATE, 1, 2), new Change(Kind.ADD, 2, 1)),
                LineDiff.diff(List.of("a", "b"), List.of("a", "x", "c")));
    }

    @Test
    void shrinkingRemovesTrailingEntries() {
        assertEquals(List.of(new Change(Kind.RESCORE, 0, 1), new Change(Kind.REMOVE, 1, 0), new Change(Kind.REMOVE, 2, 0)),
                LineDiff.diff(List.of("a", "b", "c"), List.of("a")));
        assertEquals(List.of(new Change(Kind.REMOVE, 0, 0)), LineDiff.diff(List.of("a"), List.of()));
    }

    @Test
    void duplicateBlankLinesUseDistinctEntries() {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < ScoreboardSettings.MAX_LINES; i++) {
            entries.add(LineDiff.entry(i));
        }
        assertEquals(ScoreboardSettings.MAX_LINES, entries.stream().distinct().count());
    }

    @Test
    void renderedComponentsAreDiffedWithTheSameMinimalPlan() {
        // The packet backend keeps the rendered Components, so a colour-only change must still be one update.
        var a = net.kyori.adventure.text.Component.text("Player: Alice");
        var b = net.kyori.adventure.text.Component.text("Rank: VIP");
        var b2 = net.kyori.adventure.text.Component.text("Rank: MVP");
        var c = net.kyori.adventure.text.Component.text("Online: 3/20");
        assertEquals(List.of(new Change(Kind.UPDATE, 1, 2)), LineDiff.diff(List.of(a, b, c), List.of(a, b2, c)));
        assertTrue(LineDiff.diff(List.of(a, b, c), List.of(a, b, c)).isEmpty());
    }

    @Test
    void boardsAreLimitedToFifteenLinesAndMatchedInOrder() {
        ScoreboardConfig config = new ScoreboardConfig();
        ScoreboardConfig.Board big = new ScoreboardConfig.Board();
        big.name = "big";
        big.permission = "atrium.board.big";
        for (int i = 0; i < 20; i++) {
            big.lines.add("line " + i);
        }
        config.boards.addFirst(big);
        List<String> problems = new ArrayList<>();
        ScoreboardSettings s = ScoreboardSettings.from(config, (o, p) -> problems.add(o));
        assertEquals(List.of("boards.big"), problems);
        assertEquals(15, s.boards().getFirst().lines().size());
        assertEquals("big", s.boardFor("world", "atrium.board.big"::equals).name());
        assertEquals("default", s.boardFor("world", p -> false).name());
    }
}
