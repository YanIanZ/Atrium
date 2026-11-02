package dev.yanianz.atrium.module.afk;

/** Immutable snapshot of the movement-related options, so event handlers do not rebuild it per move. */
record AfkRules(
        double movementThreshold,
        boolean countRotation,
        double rotationThresholdDegrees,
        boolean ignorePushed,
        double maxStep,
        boolean antiAfkPool,
        double poolBoxSize,
        long poolWindowMillis) {

    static AfkRules from(AfkConfig config) {
        return new AfkRules(
                config.movementThreshold,
                config.countRotationAsActivity,
                config.rotationThresholdDegrees,
                config.ignorePushed,
                config.maxMovementPerCheck,
                config.antiAfkPoolEnabled,
                config.antiAfkPoolBoxSize,
                Math.max(0, config.antiAfkPoolWindowSeconds) * 1000L);
    }
}
