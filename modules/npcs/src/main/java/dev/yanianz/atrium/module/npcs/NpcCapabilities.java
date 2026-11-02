package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.api.npcs.NpcType;
import java.util.List;

/**
 * What each backend can actually render. Commands consult this matrix before storing a value, so an
 * admin never thinks a setting took effect while the engine silently ignored it. Pure: no server
 * access, so {@code /npc engine} and the command checks share one tested source of truth.
 */
final class NpcCapabilities {
    private NpcCapabilities() {}

    enum Support {
        /** Applied in full. */
        YES,
        /** Only part of the setting is applied (a subset of values, or an approximation). */
        PARTIAL,
        /** Ignored by the engine; commands refuse the change. */
        NO;

        String messageKey() {
            return switch (this) {
                case YES -> "npcs.support-yes";
                case PARTIAL -> "npcs.support-partial";
                case NO -> "npcs.support-no";
            };
        }
    }

    enum Capability {
        POSE("npcs.cap-pose"),
        SCALE("npcs.cap-scale"),
        GLOW_COLOUR("npcs.cap-glow-colour"),
        NAMEPLATE("npcs.cap-nameplate"),
        SKIN("npcs.cap-skin"),
        EQUIPMENT("npcs.cap-equipment"),
        LOOK_AT("npcs.cap-look"),
        ACTIONS("npcs.cap-actions"),
        EXACT_SELECT("npcs.cap-select");

        private final String messageKey;

        Capability(String messageKey) {
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }

        Support on(NpcType type) {
            NpcType engine = type == null || type == NpcType.AUTO ? NpcType.NATIVE : type;
            return switch (this) {
                case POSE -> switch (engine) {
                    case NATIVE -> Support.YES;
                    // Citizens only stores a sneaking flag.
                    case CITIZENS -> Support.PARTIAL;
                    // FancyNpcs keeps no pose in its NPC data.
                    case FANCYNPC -> Support.NO;
                    case AUTO -> Support.YES;
                };
                case GLOW_COLOUR -> switch (engine) {
                    // Native mannequins and Citizens only expose "glowing" (white); only FancyNpcs stores a colour.
                    case NATIVE, CITIZENS -> Support.PARTIAL;
                    case FANCYNPC -> Support.YES;
                    case AUTO -> Support.PARTIAL;
                };
                // A nameplate hologram is applied by Atrium itself for every engine.
                case NAMEPLATE -> Support.YES;
                // FancyNpcs NPCs are packet-only, so an entity ray trace cannot hit them; /npc select falls back to the cone.
                case EXACT_SELECT -> engine == NpcType.FANCYNPC ? Support.PARTIAL : Support.YES;
                default -> Support.YES;
            };
        }
    }

    static Support support(NpcType type, Capability capability) {
        return capability.on(type);
    }

    static List<Capability> all() {
        return List.of(Capability.values());
    }
}
