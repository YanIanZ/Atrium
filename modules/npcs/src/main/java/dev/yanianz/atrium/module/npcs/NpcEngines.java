package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcType;

/** Backend selection. Pure so the rule is testable without a server. */
final class NpcEngines {
    private NpcEngines() {}

    /**
     * Resolves the configured engine. {@link NpcType#AUTO} uses Citizens when it is enabled, then FancyNpcs,
     * then the always-available native mannequin. An explicitly configured backend is kept even when the
     * plugin is missing, so the admin sees a clear error instead of a silent switch.
     */
    static NpcType resolve(NpcType configured, boolean citizens, boolean fancy) {
        NpcType value = configured == null ? NpcType.AUTO : configured;
        if (value != NpcType.AUTO) {
            return value;
        }
        if (citizens) {
            return NpcType.CITIZENS;
        }
        if (fancy) {
            return NpcType.FANCYNPC;
        }
        return NpcType.NATIVE;
    }

    /** True when the backend can be used right now. Native always can. */
    static boolean usable(NpcType type, boolean citizens, boolean fancy) {
        return switch (type) {
            case NATIVE -> true;
            case CITIZENS -> citizens;
            case FANCYNPC -> fancy;
            case AUTO -> true;
        };
    }
}
