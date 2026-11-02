package dev.yanianz.atrium.module.armorstand;

import java.util.ArrayList;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/armorstand.yml */
@ConfigSerializable
public class ArmorStandConfig {
    @Comment("How far (in blocks) a player can target an armor stand with /astand (clamped to 1-8).")
    public double reach = 5.0;

    @Comment("Pose step sizes in degrees the editor cycles through (each clamped to 0.1-180).")
    public List<Double> stepsDegrees = new ArrayList<>(List.of(1.0, 5.0, 15.0, 45.0, 90.0));

    @Comment("Also fire Bukkit's PlayerArmorStandManipulateEvent before every edit so protection plugins\n"
            + "(claims, regions) can deny it. Atrium's own ArmorStandEditEvent is always fired.")
    public boolean protectionCheck = true;

    @Comment("Ask Atrium's ProtectionService (WorldGuard, GriefPrevention, Lands, Towny) before every edit.\n"
            + "atrium.protection.bypass skips this check.")
    public boolean protectionServiceCheck = true;

    @Comment("Saved poses per player; atrium.armorstand.poses.<n> raises it, .unlimited removes it.")
    public int maxSavedPoses = 20;

    @Comment("Allow swapping a stand's equipment slot with the item in the player's main hand.")
    public boolean equipmentSwap = true;

    @Comment("Enable placement templates: /astand template save|give tags an armor stand item, and placing that\n"
            + "item creates a stand with the saved pose, flags, name and equipment. Off by default.")
    public boolean templatesEnabled = false;

    @Comment("How many placement templates may exist (1-500). atrium.armorstand.templates.<n> raises it.")
    public int maxTemplates = 50;

    @Comment("Give arms to an armor stand a player just placed (needs atrium.armorstand.arms).\n"
            + "Off by default, because vanilla stands have no arms.")
    public boolean autoArmsOnPlace = false;

    double reach() {
        return Math.max(1.0, Math.min(8.0, Double.isFinite(reach) ? reach : 5.0));
    }

    List<Double> steps() {
        List<Double> out = new ArrayList<>();
        for (Double step : stepsDegrees) {
            if (step != null && Double.isFinite(step) && step > 0) {
                out.add(Math.max(0.1, Math.min(PoseMath.MAX_STEP_DEGREES, step)));
            }
        }
        return out.isEmpty() ? List.of(1.0) : List.copyOf(out);
    }
}
