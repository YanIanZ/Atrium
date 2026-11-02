package dev.yanianz.atrium.module.extras;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/extras.yml */
@ConfigSerializable
public class ExtrasConfig {
    @Comment("Read-only shulker box preview (sneak + right-click air while holding one).")
    public Shulker shulker = new Shulker();

    @ConfigSerializable
    public static class Shulker {
        @Comment("Enable the read-only shulker box preview.")
        public boolean enabled = true;
    }
}
