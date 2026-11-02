package dev.yanianz.atrium.module.playtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, resumable delivery steps of one reward claim.
 *
 * <p>Money is always the first step: a failed deposit must abort the claim before any command runs, so a
 * retry cannot hand out the items a second time. Every finished step is persisted by the caller, so a
 * retry resumes after it instead of paying it again. Pure logic; {@link PlaytimeModule} performs the I/O.
 */
final class RewardDelivery {
    private RewardDelivery() {}

    enum Kind {
        MONEY,
        COMMAND
    }

    /** One unit of delivery. {@code commandIndex} is the index into the spec's command list, or -1. */
    record Step(Kind kind, int commandIndex) {
        static Step money() {
            return new Step(Kind.MONEY, -1);
        }

        static Step command(int index) {
            return new Step(Kind.COMMAND, index);
        }
    }

    /** Work left for this claim, in delivery order. Empty when nothing remains. */
    static List<Step> steps(PlaytimeRewards.Spec spec, PlaytimeStore.Progress progress) {
        List<Step> steps = new ArrayList<>();
        if (spec.money().signum() > 0 && !progress.moneyDone()) {
            steps.add(Step.money());
        }
        int first = Math.max(0, progress.commandsDone());
        for (int index = first; index < spec.commands().size(); index++) {
            steps.add(Step.command(index));
        }
        return steps;
    }

    /**
     * True when the money step of this reward cannot be paid at all (the economy module is absent).
     * Such a step is recorded as done so the claim is not retried every minute forever; the commands
     * of the same reward are still delivered exactly once.
     */
    static boolean moneyUnavailable(PlaytimeRewards.Spec spec, boolean economyAvailable) {
        return spec.money().signum() > 0 && !economyAvailable;
    }
}
