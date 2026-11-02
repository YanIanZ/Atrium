package dev.yanianz.atrium.module.deathmessages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/deathmessages.yml */
@ConfigSerializable
public class DeathMessagesConfig {
    @Comment("Replace vanilla death messages with the templates below.")
    public boolean enabled = true;

    @Comment("Per-world switch. A world listed here uses this value instead of 'enabled'.")
    public Map<String, Boolean> worlds = new LinkedHashMap<>();

    @Comment("0 broadcasts to every online player in every world. A positive number only reaches players\n"
            + "in the same world within that many blocks of the death location.")
    public int broadcastRadius = 0;

    @Comment("Send the death location to the dead player only.")
    public boolean sendLocationToVictim = true;

    @Comment("Component inserted for <weapon> when the killer has an empty hand (admin MiniMessage).")
    public String fistsText = "<gray>fists";

    @Comment("MiniMessage template per cause key. Placeholders: <victim> <killer> <weapon> <world> <x> <y> <z>.\n"
            + "Keys used by the selector: fall, lava, drowning, explosion, projectile, player, mob,\n"
            + "mob.<entity> (for example mob.zombie) and fallback. The first matching key wins.")
    public Map<String, String> templates = defaults();

    static Map<String, String> defaults() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("fall", "<victim> <gray>fell to their death");
        map.put("lava", "<victim> <gray>tried to swim in lava");
        map.put("drowning", "<victim> <gray>drowned");
        map.put("explosion", "<victim> <gray>blew up");
        map.put("projectile", "<victim> <gray>was shot");
        map.put("player", "<victim> <gray>was slain by <killer><gray> using <weapon>");
        map.put("mob", "<victim> <gray>was slain by <killer>");
        map.put("mob.zombie", "<victim> <gray>was slain by a zombie");
        map.put("fallback", "<victim> <gray>died");
        return map;
    }

    /** Whether custom messages apply in this world. */
    public boolean enabledIn(String world) {
        Boolean specific = worlds == null ? null : worlds.get(world);
        return specific == null ? enabled : specific;
    }

    /** First configured, non-blank template among {@code candidates}; hard fallback when all are blank. */
    public String templateFor(List<String> candidates) {
        if (templates != null) {
            for (String key : candidates) {
                String template = templates.get(key);
                if (template != null && !template.isBlank()) {
                    return template;
                }
            }
        }
        return "<victim> <gray>died";
    }
}
