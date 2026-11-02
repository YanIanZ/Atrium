package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Action;
import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Observation;
import dev.yanianz.atrium.module.sourbycraft.OwnerDecision.Pending;
import dev.yanianz.atrium.module.sourbycraft.ProfileScopes.MissingState;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The reconciliation decision table of {@link OwnerDecision}, row by row. */
class OwnerDecisionTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final long SETTLE = 15_000;
    private static final long T = 1_000_000; // time of the restore

    private static Pending pending(@Nullable UUID tag, @Nullable UUID previous) {
        return new Pending(T, tag, previous);
    }

    private static Observation seen(@Nullable UUID active, long startedAt) {
        return new Observation(active, startedAt);
    }

    static Stream<Arguments> table() {
        return Stream.of(
                // name, owner, pending, observation, missing, expected
                Arguments.of("no active profile", A, null, seen(null, T), MissingState.EMPTY, Action.NONE),
                Arguments.of("no active profile, restore pending", B, pending(B, A), seen(null, T + 1), MissingState.EMPTY, Action.NONE),
                Arguments.of("observation older than the restore", B, pending(B, A), seen(A, T - 1), MissingState.EMPTY, Action.NONE),
                Arguments.of("restore landed on its tag", B, pending(B, A), seen(B, T), MissingState.EMPTY, Action.CONFIRM),
                Arguments.of("tagged restore, old profile still reported", B, pending(B, A), seen(A, T + 2_000), MissingState.EMPTY, Action.WAIT),
                Arguments.of("tagged restore, other profile after settle", B, pending(B, A), seen(C, T + SETTLE), MissingState.EMPTY, Action.CLEAR),
                Arguments.of("tagged restore, other profile after settle, KEEP", B, pending(B, A), seen(C, T + SETTLE), MissingState.KEEP, Action.ADOPT),
                Arguments.of("untagged restore, new profile reported", null, pending(null, A), seen(B, T + 1), MissingState.EMPTY, Action.ADOPT),
                Arguments.of("untagged restore, old profile within settle", null, pending(null, A), seen(A, T + 1), MissingState.EMPTY, Action.WAIT),
                Arguments.of("untagged restore, old profile after settle", null, pending(null, A), seen(A, T + SETTLE), MissingState.EMPTY, Action.ADOPT),
                Arguments.of("untagged restore without previous owner", null, pending(null, null), seen(A, T), MissingState.EMPTY, Action.ADOPT),
                Arguments.of("owner unknown (data predates tracking)", null, null, seen(A, T), MissingState.EMPTY, Action.ADOPT),
                Arguments.of("owner is the active profile", A, null, seen(A, T), MissingState.EMPTY, Action.NONE),
                Arguments.of("switched into a profile without data", A, null, seen(B, T), MissingState.EMPTY, Action.CLEAR),
                Arguments.of("switched into a profile without data, KEEP", A, null, seen(B, T), MissingState.KEEP, Action.ADOPT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("table")
    void decides(String name, @Nullable UUID owner, @Nullable Pending pending, Observation observation, MissingState missing,
            Action expected) {
        assertEquals(expected, OwnerDecision.decide(owner, pending, observation, missing, SETTLE));
    }
}
