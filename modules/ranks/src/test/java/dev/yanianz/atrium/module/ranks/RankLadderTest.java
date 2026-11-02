package dev.yanianz.atrium.module.ranks;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RankLadderTest {
    private static RanksConfig config() {
        RanksConfig config = new RanksConfig();
        config.ranks = new java.util.ArrayList<>();
        config.ranks.add(RanksConfig.Rank.of("one", "One", "group-one", 0, "0", "", true));
        config.ranks.add(RanksConfig.Rank.of("two", "Two", "group-two", 3600, "100", "rank.two", true));
        config.ranks.add(RanksConfig.Rank.of("three", "Three", "", 7200, "0", "", false));
        config.startingRank = "one";
        return config;
    }

    @Test void followsConfiguredOrderAndStartingRank() {
        RankLadder ladder = RankLadder.from(config());
        assertEquals(java.util.List.of("one", "two", "three"), ladder.ids());
        assertEquals("one", ladder.startingRank());
        assertEquals("one", ladder.start().id());
        assertEquals("two", ladder.next(ladder.start()).orElseThrow().id());
        assertEquals("three", ladder.next(ladder.find("two").orElseThrow()).orElseThrow().id());
        assertTrue(ladder.next(ladder.find("three").orElseThrow()).isEmpty());
        assertEquals("one", ladder.resolve("missing").id(), "unknown stored ranks fall back to the start");
        assertEquals(java.util.List.of("group-one", "group-two"), ladder.managedGroups());
    }

    @Test void rejectsDuplicateIdsGroupsAndBadValues() {
        RanksConfig duplicateId = config();
        duplicateId.ranks.add(RanksConfig.Rank.of("ONE", "Other", "other", 0, "0", "", true));
        assertThrows(IllegalArgumentException.class, () -> RankLadder.from(duplicateId));

        RanksConfig duplicateGroup = config();
        duplicateGroup.ranks.add(RanksConfig.Rank.of("four", "Four", "group-two", 0, "0", "", true));
        assertThrows(IllegalArgumentException.class, () -> RankLadder.from(duplicateGroup));

        RanksConfig negative = config();
        negative.ranks.get(1).playtimeSeconds = -1;
        assertThrows(IllegalArgumentException.class, () -> RankLadder.from(negative));

        RanksConfig unknownStart = config();
        unknownStart.startingRank = "nope";
        assertThrows(IllegalArgumentException.class, () -> RankLadder.from(unknownStart));

        RanksConfig badCommand = config();
        badCommand.ranks.get(1).commands.add("say hi\nop {player}");
        assertThrows(IllegalArgumentException.class, () -> RankLadder.from(badCommand));
    }

    @Test void blankStartingRankMeansFirstRank() {
        RanksConfig config = config();
        config.startingRank = "";
        assertEquals("one", RankLadder.from(config).startingRank());
    }

    @Test void previousIsTheInverseOfNextAndDownCommandsAreParsed() {
        RanksConfig config = config();
        config.ranks.get(1).downCommands.add("say {player} left two");
        RankLadder ladder = RankLadder.from(config);
        RankLadder.Rank two = ladder.find("two").orElseThrow();
        assertEquals("one", ladder.previous(two).orElseThrow().id());
        assertEquals(java.util.List.of("say {player} left two"), two.downCommands());
        assertTrue(ladder.previous(ladder.start()).isEmpty());

        RanksConfig bad = config();
        bad.ranks.get(1).downCommands.add("say hi\nop {player}");
        assertThrows(IllegalArgumentException.class, () -> RankLadder.from(bad));
    }
}
