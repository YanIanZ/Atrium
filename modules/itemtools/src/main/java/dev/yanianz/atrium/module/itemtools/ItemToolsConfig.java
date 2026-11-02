package dev.yanianz.atrium.module.itemtools;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/itemtools.yml */
@ConfigSerializable
public class ItemToolsConfig {
    @Comment("Seconds an unanswered /donate offer stays valid (clamped to 5-600).")
    public int donateOfferSeconds = 60;

    @Comment("Maximum unanswered /donate offers one player may receive at once (clamped to 1-50).\n"
            + "A new offer from the same donor with a different item is refused instead of silently replacing it.")
    public int donateMaxPending = 10;

    @Comment("A /giveall amount above this needs the same command twice within confirmationSeconds (clamped to 1-2304).")
    public int giveallConfirmAbove = 64;

    @Comment("Seconds a destructive-command confirmation stays valid (clamped to 3-120).")
    public int confirmationSeconds = 15;

    @Comment("Upper limit for /giveall amounts (clamped to 1-3456).")
    public int giveallMaxAmount = 2304;

    @Comment("Skip condensing items that carry a custom name, lore, enchantments, CustomModelData,\n"
            + "persistent data, item flags, damage or a repair cost. This prevents losing customised items.")
    public boolean condenseSkipCustomised = true;

    @Comment("How far (in blocks) /itemframe looks for the frame the player is aiming at (clamped to 1-32).")
    public double frameRange = 10.0;

    int offerSeconds() {
        return Math.clamp(donateOfferSeconds, 5, 600);
    }

    int maxPending() {
        return Math.clamp(donateMaxPending, 1, 50);
    }

    int confirmAbove() {
        return Math.clamp(giveallConfirmAbove, 1, 2304);
    }

    int confirmationSeconds() {
        return Math.clamp(confirmationSeconds, 3, 120);
    }

    int maxGiveallAmount() {
        return Math.clamp(giveallMaxAmount, 1, 3456);
    }

    double frameRange() {
        return Math.clamp(Double.isFinite(frameRange) ? frameRange : 10.0, 1.0, 32.0);
    }
}
