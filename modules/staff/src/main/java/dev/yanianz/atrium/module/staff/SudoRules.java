package dev.yanianz.atrium.module.staff;

/** Pure permission rules for {@code /sudo}. */
final class SudoRules {
    enum Decision {
        ALLOWED,
        /** The target is not a player (console, command block, entity): never allowed. */
        TARGET_NOT_PLAYER,
        TARGET_EXEMPT,
        TARGET_OPERATOR
    }

    private SudoRules() {
    }

    /**
     * Evaluated again on the target's own thread right before running, so a permission or op change in
     * between is honoured.
     *
     * @param senderIsConsole sender is the server console (the only sender allowed to sudo an operator)
     * @param targetIsPlayer  target is an online player
     * @param targetIsOp      target holds operator status
     * @param targetExempt    target has {@code atrium.sudo.exempt}
     */
    static Decision decide(boolean senderIsConsole, boolean targetIsPlayer, boolean targetIsOp,
            boolean targetExempt) {
        if (!targetIsPlayer) {
            return Decision.TARGET_NOT_PLAYER;
        }
        if (targetExempt) {
            return Decision.TARGET_EXEMPT;
        }
        if (targetIsOp && !senderIsConsole) {
            return Decision.TARGET_OPERATOR;
        }
        return Decision.ALLOWED;
    }
}
