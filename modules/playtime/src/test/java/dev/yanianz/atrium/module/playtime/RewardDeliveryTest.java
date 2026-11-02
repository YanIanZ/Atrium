package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the "commands ran, then the deposit failed, so the next check ran the commands
 * again" defect: money is always the first step, and finished steps are never repeated on a retry.
 */
class RewardDeliveryTest {
    private static final PlaytimeRewards.Spec MIXED = new PlaytimeRewards.Spec("mixed",
            PlaytimeRewards.Type.ONE_TIME, 3600, "",
            List.of("give {player} diamond 1", "say hi"), new BigDecimal("100"));
    private static final PlaytimeRewards.Spec COMMANDS_ONLY = new PlaytimeRewards.Spec("cmd",
            PlaytimeRewards.Type.ONE_TIME, 3600, "", List.of("give {player} diamond 1"), BigDecimal.ZERO);
    private static final PlaytimeRewards.Spec MONEY_ONLY = new PlaytimeRewards.Spec("money",
            PlaytimeRewards.Type.ONE_TIME, 3600, "", List.of(), new BigDecimal("100"));

    @Test void moneyIsDepositedBeforeAnyCommandRuns() {
        var steps = RewardDelivery.steps(MIXED, PlaytimeStore.Progress.EMPTY);
        assertTrue(steps.getFirst().kind() == RewardDelivery.Kind.MONEY,
                "a failed deposit must leave the commands untouched: " + steps);
        assertTrue(steps.stream().skip(1).allMatch(step -> step.kind() == RewardDelivery.Kind.COMMAND));
    }

    @Test void aRetrySkipsTheMoneyStepThatAlreadySucceeded() {
        var steps = RewardDelivery.steps(MIXED, new PlaytimeStore.Progress(true, 0));
        assertTrue(steps.stream().noneMatch(step -> step.kind() == RewardDelivery.Kind.MONEY));
        assertTrue(steps.stream().allMatch(step -> step.kind() == RewardDelivery.Kind.COMMAND));
    }

    @Test void aRetryResumesAtTheFirstUndeliveredCommand() {
        var steps = RewardDelivery.steps(MIXED, new PlaytimeStore.Progress(true, 1));
        assertTrue(steps.size() == 1);
        assertTrue(steps.getFirst().kind() == RewardDelivery.Kind.COMMAND);
        assertTrue(steps.getFirst().commandIndex() == 1, "command 0 was already dispatched");
        assertTrue(RewardDelivery.steps(MIXED, new PlaytimeStore.Progress(true, 2)).isEmpty());

        // If the money step did not finish, it is still first even when commands were partly dispatched.
        var mixed = RewardDelivery.steps(MIXED, new PlaytimeStore.Progress(false, 1));
        assertTrue(mixed.getFirst().kind() == RewardDelivery.Kind.MONEY);
        assertTrue(mixed.size() == 2);
    }

    @Test void aClaimWhoseCommandsAlreadyRanIsNotRepeated() {
        assertTrue(RewardDelivery.steps(COMMANDS_ONLY, new PlaytimeStore.Progress(false, 1)).isEmpty());
        assertTrue(RewardDelivery.steps(MONEY_ONLY, new PlaytimeStore.Progress(true, 0)).isEmpty());
    }

    @Test void commandOnlyRewardsHaveNoMoneyStep() {
        var steps = RewardDelivery.steps(COMMANDS_ONLY, PlaytimeStore.Progress.EMPTY);
        assertTrue(steps.size() == 1);
        assertTrue(steps.getFirst().kind() == RewardDelivery.Kind.COMMAND);
        assertTrue(!RewardDelivery.moneyUnavailable(COMMANDS_ONLY, false),
                "a missing economy must not affect a reward without money");
    }

    @Test void aMoneyRewardWithoutAnEconomyIsSkippedInsteadOfRetriedForever() {
        assertTrue(RewardDelivery.moneyUnavailable(MONEY_ONLY, false));
        assertTrue(!RewardDelivery.moneyUnavailable(MONEY_ONLY, true));
        assertTrue(!RewardDelivery.moneyUnavailable(COMMANDS_ONLY, false));
    }
}
