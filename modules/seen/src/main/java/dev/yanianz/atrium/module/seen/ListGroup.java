package dev.yanianz.atrium.module.seen;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** One group of {@code /playerlist}. */
@ConfigSerializable
public class ListGroup {
    @Comment("Group name shown in the list (MiniMessage).")
    public String display = "<gray>Players";

    @Comment("Permission a player needs to be listed in this group; empty = everyone not listed in an earlier group.")
    public String permission = "";

    public ListGroup() {}

    ListGroup(String display, String permission) {
        this.display = display;
        this.permission = permission;
    }
}
